package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityBodyTestAccess;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Exercise the real server tracker, including its merged optimization-mod consumers. */
final class SyncStateParity {
    static void verify(GameTestHelper helper) {
        for (int count : new int[]{2, 8, 20}) {
            List<State> expected = run(helper, count, false), actual = run(helper, count, true);
            helper.assertValueEqual(actual, expected, "native sync/tracker sequence, entities=" + count);
        }
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_SYNC_STATE_PARITY entity_counts=2,8,20 phases=4 real_server_tracker=true result=passed");
    }

    private static List<State> run(GameTestHelper helper, int count, boolean enabled) {
        try (var scene = new InteractionScene(helper, enabled)) {
            List<LivingEntity> entities = new ArrayList<>();
            List<ServerEntity> trackers = new ArrayList<>();
            List<Sink> sinks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                var entity = (LivingEntity) scene.spawn(EntityTypes.ZOMBIE,
                        new Vec3(4.5 + i % 5 * .03, 1, 4.5 + i / 5 * .04));
                entity.setDeltaMovement(Vec3.ZERO);
                entity.needsSync = false;
                entities.add(entity);
                var sink = new Sink();
                var tracker = new ServerEntity(helper.getLevel(), entity, 1000, false, sink);
                tracker.sendChanges(); // Consume initial dirty data; subsequent updates need needsSync.
                sink.packets.clear();
                trackers.add(tracker);
                sinks.add(sink);
            }
            var source = entities.getFirst();
            List<State> states = new ArrayList<>();
            CollisionFrame.begin(helper.getLevel());
            try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
                helper.assertValueEqual(batch.size(), count - 1, "sync fixture native candidates");
                for (int phase = 0; phase < 4; phase++) {
                    for (int run = 0; run < 3; run++) {
                        if (enabled) batch.applyNativeRun(source, 0, batch.size());
                        else for (int i = 0; i < batch.size(); i++) batch.target(i).push(source);
                    }
                    for (int i = 0; i < count; i++) {
                        Entity entity = entities.get(i);
                        if (enabled) helper.assertTrue(!physicalSync(entity), "no Java sync publication");
                        if (phase == 1) entity.push(.125, 0, -.25); // Java also writes the same authority.
                        if (phase == 2) entity.needsSync = false; // Explicit consumption suppresses this update.
                        helper.assertValueEqual(((EntityBodyTestAccess) entity).eco$rawNeedsSync(), entity.needsSync,
                                "independent accessor shares the public field authority");
                        if (phase == 3) {
                            entity.needsSync = false;
                            ((EntityBodyTestAccess) entity).eco$rawNeedsSync(true);
                        }
                        boolean before = entity.needsSync;
                        trackers.get(i).sendChanges(); // FIRST velocity observer after the native run.
                        helper.assertTrue(!entity.needsSync, "server tracker consumes sync immediately");
                        states.add(new State(before, entity.needsSync, entity.getDeltaMovement(),
                                trackers.get(i).getLastSentMovement(), List.copyOf(sinks.get(i).packets)));
                        sinks.get(i).packets.clear();
                        trackers.get(i).sendChanges();
                        helper.assertTrue(!entity.needsSync, "later tracker/getter cannot resurrect sync");
                        helper.assertTrue(sinks.get(i).packets.isEmpty(), "no duplicate network publication");
                    }
                }
                if (enabled) batch.applyNativeRun(source, 0, batch.size());
                else for (int i = 0; i < batch.size(); i++) batch.target(i).push(source);
            }
            return states;
        }
    }

    private static boolean physicalSync(Entity entity) {
        try { return Entity.class.getField("needsSync").getBoolean(entity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private record State(boolean before, boolean after, Vec3 velocity, Vec3 tracked, List<Sent> packets) {}
    private record Sent(String type, Vec3 velocity) {}

    private static final class Sink implements ServerEntity.Synchronizer {
        final List<Sent> packets = new ArrayList<>();
        @Override public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
            packets.add(new Sent(packet.getClass().getName(),
                    packet instanceof ClientboundSetEntityMotionPacket motion ? motion.movement() : null));
        }
        @Override public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
            sendToTrackingPlayers(packet);
        }
        @Override public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet,
                                                           Predicate<ServerPlayer> predicate) {
            sendToTrackingPlayers(packet);
        }
    }
}

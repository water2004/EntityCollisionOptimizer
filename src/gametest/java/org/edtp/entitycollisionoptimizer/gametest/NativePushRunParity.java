package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityVelocityAccessor;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** Compare complete runs AND split-run commit boundaries to actual vanilla Entity.push(Entity). */
final class NativePushRunParity {
    static void verify(GameTestHelper helper) {
        Vec3[] initial = {new Vec3(0, -0.0, 0), new Vec3(.125, -.0, -.375),
                new Vec3(0x1.0p49, 0, -0x1.0p49), new Vec3(Double.MAX_VALUE, 0, -Double.MAX_VALUE),
                new Vec3(Double.NaN, 0, .125), new Vec3(.125, Double.POSITIVE_INFINITY, .25)};
        int cases = 0;
        for (int targets : new int[]{1, 7, 19}) for (Vec3 velocity : initial) for (int guards = 0; guards < 5; guards++) {
            compare(helper, targets, velocity, guards);
            cases++;
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_PUSH_RUN_PARITY cases={} entity_counts=2,8,20 full_and_split_runs=bitwise result=passed", cases);
    }

    private static void compare(GameTestHelper helper, int count, Vec3 initial, int guards) {
        try (var scene = new InteractionScene(helper)) {
            var source = (Zombie) scene.spawn(EntityType.ZOMBIE, new Vec3(4.5, 1, 4.5));
            List<Entity> entities = new ArrayList<>();
            entities.add(source);
            for (int i = 0; i < count; i++) {
                entities.add(scene.spawn(EntityType.ZOMBIE,
                        new Vec3(4.5 + (i % 5 - 2) * .06, 1, 4.5 + (i / 5 - 2) * .06)));
            }
            // Snapshot candidates before transitions: execution must read the live guards after collection.
            CollisionFrame.begin(helper.getLevel());
            try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
                helper.assertValueEqual(batch.size(), count, "native run fixture candidates");
                for (int i = 0; i < count; i++) {
                    helper.assertTrue(batch.usesNativePush(i), "run fixture must actually use native execution");
                    if (guards == 1 && i % 3 == 0) batch.target(i).noPhysics = true;
                    if (guards == 2 && i % 3 == 0) ((Zombie) batch.target(i)).setSleepingPos(batch.target(i).blockPosition());
                }
                if (guards == 3) source.setHealth(0);
                if (guards == 4) source.noPhysics = true;
                int split = Math.max(1, count / 2);
                reset(entities, initial);
                for (int i = 0; i < split; i++) batch.target(i).push(source);
                var expectedMiddle = snapshot(entities);
                for (int i = split; i < count; i++) batch.target(i).push(source);
                var expected = snapshot(entities);
                String label = "run n=" + count + " initial=" + initial + " guards=" + guards;
                reset(entities, initial);
                batch.applyNativeRun(source, 0, count);
                compare(helper, entities, expected, label + " full");
                reset(entities, initial);
                batch.applyNativeRun(source, 0, split);
                compare(helper, entities, expectedMiddle, label + " first commit");
                batch.applyNativeRun(source, split, count);
                compare(helper, entities, expected, label + " second commit");
                // A consumed sync flag must not be resurrected by a later getter or frame lifecycle.
                entities.forEach(entity -> entity.hasImpulse = false);
                CollisionFrame.end(helper.getLevel());
                for (Entity entity : entities) {
                    entity.getDeltaMovement();
                    helper.assertTrue(!entity.hasImpulse, "no deferred sync after run commit");
                }
            }
        }
    }

    private static void reset(List<Entity> entities, Vec3 velocity) {
        for (int i = 0; i < entities.size(); i++) {
            // Non-finite raw states are numerical guard probes, not a custom entity implementation.
            ((EntityVelocityAccessor) entities.get(i)).eco$rawVelocity(velocity);
            entities.get(i).hasImpulse = (i & 1) != 0;
        }
    }

    private static List<State> snapshot(List<Entity> entities) {
        return entities.stream().map(entity -> new State(((EntityVelocityAccessor) entity).eco$rawVelocity(), entity.hasImpulse)).toList();
    }

    private static void compare(GameTestHelper helper, List<Entity> entities, List<State> expected, String label) {
        for (int i = 0; i < entities.size(); i++) {
            NativeImpulseParity.exact(helper, ((EntityVelocityAccessor) entities.get(i)).eco$rawVelocity(),
                    expected.get(i).velocity, label + " raw velocity " + i);
            helper.assertValueEqual(entities.get(i).hasImpulse, expected.get(i).sync, label + " sync " + i);
        }
    }

    private record State(Vec3 velocity, boolean sync) {}
}

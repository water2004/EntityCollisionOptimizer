package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityVelocityAccessor;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** Repeat source/target roles and external changes against actual vanilla pushes, bit for bit. */
final class PersistentBodyParity {
    static void verify(GameTestHelper helper) {
        for (int count : new int[]{2, 8, 20}) {
            try (var scene = new InteractionScene(helper, true)) {
                List<LivingEntity> entities = new ArrayList<>();
                for (int i = 0; i < count; i++) entities.add((LivingEntity) scene.spawn(EntityTypes.ZOMBIE,
                        new Vec3(4.5 + (i % 5) * .025, 1, 4.5 + (i / 5) * .025)));
                for (int phase = 0; phase < 12; phase++) {
                    LivingEntity source = entities.get(phase % count);
                    if (phase % 3 == 0) CollisionFrame.begin(helper.getLevel());
                    try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
                        helper.assertValueEqual(batch.size(), count - 1, "persistent group candidates");
                        // Changes AFTER collection must be read when the native task actually executes.
                        for (int i = 0; i < count; i++) {
                            Entity entity = entities.get(i);
                            if (phase % 4 == 0) entity.setDeltaMovement(new Vec3(i * .015, -0.0, phase * -.01));
                            if (phase % 4 == 1) entity.addDeltaMovement(new Vec3(.03125, -.015625, .0625));
                            if (phase % 4 == 2) entity.setDeltaMovement(Double.NaN, 0, 0);
                            if (phase % 4 == 3) entity.push(.0625, -.03125, .015625);
                            // Raw position changes need not change the AABB: the stored candidates remain fixed.
                            if (i == phase % count) entity.setPosRaw(entity.getX() + .001, entity.getY(), entity.getZ());
                            entity.needsSync = (i + phase) % 3 == 0;
                        }
                        List<State> before = snapshot(entities);
                        for (int i = 0; i < batch.size(); i++) batch.target(i).push(source);
                        List<State> expected = snapshot(entities);
                        restore(entities, before);
                        batch.applyNativeRun(source, 0, batch.size());
                        List<State> actual = snapshot(entities);
                        for (int i = 0; i < count; i++) {
                            NativeImpulseParity.exact(helper, actual.get(i).velocity, expected.get(i).velocity,
                                    "persistent n=" + count + " phase=" + phase + " body=" + i);
                            helper.assertValueEqual(actual.get(i).sync, expected.get(i).sync, "persistent immediate sync");
                        }
                    }
                }
            }
        }
    }
    private static List<State> snapshot(List<LivingEntity> entities) {
        return entities.stream().map(e -> new State(((EntityVelocityAccessor) e).eco$rawVelocity(), e.needsSync)).toList();
    }
    private static void restore(List<LivingEntity> entities, List<State> states) {
        for (int i = 0; i < entities.size(); i++) {
            ((EntityVelocityAccessor) entities.get(i)).eco$rawVelocity(states.get(i).velocity);
            entities.get(i).needsSync = states.get(i).sync;
        }
    }
    private record State(Vec3 velocity, boolean sync) {}
}

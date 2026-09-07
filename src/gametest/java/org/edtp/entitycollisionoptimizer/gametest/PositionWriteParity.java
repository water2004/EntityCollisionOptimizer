package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityBodyTestAccess;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** Late source/target movement after candidate collection, compared to the actual vanilla push sequence. */
final class PositionWriteParity {
    static void verify(GameTestHelper helper) {
        for (int count : new int[]{2, 8, 20}) {
            try (var scene = new InteractionScene(helper, true)) {
                List<LivingEntity> entities = new ArrayList<>();
                List<Vec3> homes = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    var entity = (LivingEntity) scene.spawn(EntityTypes.ZOMBIE,
                            new Vec3(4.5 + i % 5 * .025, 1, 4.5 + i / 5 * .025));
                    entities.add(entity);
                    homes.add(entity.position());
                }
                for (int phase = 0; phase < 12; phase++) {
                    for (int i = 0; i < count; i++) {
                        entities.get(i).setPos(homes.get(i));
                        entities.get(i).setDeltaMovement(new Vec3(i * .03125, -0.0, i * -.015625));
                        ((EntityBodyTestAccess) entities.get(i)).eco$rawNeedsSync((i & 1) == 0);
                    }
                    CollisionFrame.begin(helper.getLevel());
                    LivingEntity source = entities.get(phase % count);
                    try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
                        helper.assertValueEqual(batch.size(), count - 1, "late movement candidate count");
                        for (int i = 0; i < count; i++) mutate(entities.get(i), phase, i);
                        if (phase >= 6) {
                            // Reentrant frame rebuilding must not poll/repair an outer batch's positions.
                            CollisionFrame.begin(helper.getLevel());
                            CollisionContractParity.ordered(helper, source, "nested query after late movement");
                        }
                        List<State> before = snapshot(entities);
                        for (int i = 0; i < batch.size(); i++) batch.target(i).push(source);
                        List<State> expected = snapshot(entities);
                        restore(entities, before);
                        int split = batch.size() / 2;
                        batch.applyNativeRun(source, 0, split);
                        batch.applyNativeRun(source, split, batch.size());
                        List<State> actual = snapshot(entities);
                        for (int i = 0; i < count; i++) {
                            String label = "n=" + count + " phase=" + phase + " entity=" + i;
                            NativeImpulseParity.exact(helper, actual.get(i).velocity, expected.get(i).velocity, "late position " + label);
                            helper.assertValueEqual(actual.get(i).sync, expected.get(i).sync, "late position sync " + label);
                        }
                    }
                }
            }
        }
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_POSITION_WRITE_PARITY entities=2,8,20 phases=12 raw_target=true box_independence=true split_runs=true nested_frames=true result=passed");
    }

    private static void mutate(Entity entity, int phase, int index) {
        double dx = (index % 3 - 1) * .0625, dz = (index % 2 == 0 ? .03125 : -.03125);
        switch (phase % 6) {
            case 0 -> entity.setPosRaw(entity.getX() + dx, entity.getY(), entity.getZ() + dz);
            case 1 -> entity.setPos(entity.getX() + dx + 4, entity.getY(), entity.getZ() + dz);
            case 2 -> entity.setBoundingBox(entity.getBoundingBox().move(dx, 0, dz));
            case 3 -> ((EntityBodyTestAccess) entity).eco$rawPosition(entity.position().add(dx, 0, dz));
            case 4 -> entity.move(MoverType.PISTON, new Vec3(dx, 0, dz));
            case 5 -> entity.setPosRaw(entity.getX() - 32 + dx, entity.getY() + .125, entity.getZ() + dz);
        }
    }

    private static List<State> snapshot(List<LivingEntity> entities) {
        return entities.stream().map(entity -> new State(entity.getDeltaMovement(),
                ((EntityBodyTestAccess) entity).eco$rawNeedsSync())).toList();
    }
    private static void restore(List<LivingEntity> entities, List<State> states) {
        for (int i = 0; i < entities.size(); i++) {
            entities.get(i).setDeltaMovement(states.get(i).velocity);
            ((EntityBodyTestAccess) entities.get(i)).eco$rawNeedsSync(states.get(i).sync);
        }
    }
    private record State(Vec3 velocity, boolean sync) {}
}

package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityBodyTestAccess;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.PushBatch;

import java.util.ArrayList;
import java.util.List;

/** Mutate already-cached inputs while retaining one candidate lease, then compare the real push sequence. */
final class PushStateParity {
    static void verify(GameTestHelper helper) {
        for (int count : new int[]{2, 8, 20}) compare(helper, count);
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_PUSH_STATE_PARITY entity_counts=2,8,20 cached_transitions=22 ancestor_vehicle_change=true raw_position_expiry=true result=passed");
    }

    private static void compare(GameTestHelper helper, int count) {
        try (var scene = new InteractionScene(helper, true)) {
            List<LivingEntity> entities = new ArrayList<>();
            for (int i = 0; i < count; i++) entities.add((LivingEntity) scene.spawn(EntityTypes.ZOMBIE,
                    new Vec3(4.4 + i % 5 * .04, 1, 4.4 + i / 5 * .04)));
            LivingEntity source = entities.getFirst(), target = entities.get(1);
            // Roots are outside the query. Only an ancestor changes when the two branches join.
            Entity firstRoot = scene.spawn(EntityTypes.OAK_BOAT, new Vec3(10, 1, 4));
            Entity secondRoot = scene.spawn(EntityTypes.OAK_BOAT, new Vec3(11, 1, 4));
            CollisionFrame.begin(helper.getLevel());
            try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
                helper.assertValueEqual(batch.size(), count - 1, "state fixture candidate count");
                for (int i = 0; i < batch.size(); i++) helper.assertTrue(batch.usesNativePush(i), "native guard path");
                assertRun(helper, entities, batch, "warm cache");
                target.noPhysics = true;
                assertRun(helper, entities, batch, "target noPhysics on");
                target.noPhysics = false;
                assertRun(helper, entities, batch, "target noPhysics off");
                source.noPhysics = true;
                assertRun(helper, entities, batch, "source noPhysics on");
                source.noPhysics = false;
                assertRun(helper, entities, batch, "source noPhysics off");
                target.setSleepingPos(target.blockPosition());
                assertRun(helper, entities, batch, "target sleep");
                target.clearSleepingPos();
                assertRun(helper, entities, batch, "target wake");
                source.setHealth(0);
                assertRun(helper, entities, batch, "source dead");
                source.setHealth(source.getMaxHealth());
                assertRun(helper, entities, batch, "source alive");
                scene.block(4, 1, 4, Blocks.SCAFFOLDING);
                assertRun(helper, entities, batch, "block changed before vanilla in-block cache expires");
                entities.forEach(Entity::baseTick);
                helper.assertTrue(source.onClimbable(), "block mutation actually changes climbing");
                assertRun(helper, entities, batch, "climbable block inserted");
                scene.block(4, 1, 4, Blocks.AIR);
                assertRun(helper, entities, batch, "block removed before vanilla in-block cache expires");
                entities.forEach(Entity::baseTick);
                helper.assertTrue(!source.onClimbable(), "block mutation restores ground state");
                assertRun(helper, entities, batch, "climbable block removed");
                scene.block(5, 1, 4, Blocks.SCAFFOLDING);
                Vec3 position = source.position(), moved = helper.absoluteVec(new Vec3(5.4, 1, 4.4));
                var box = source.getBoundingBox();
                source.setPosRaw(moved.x, moved.y, moved.z);
                helper.assertTrue(source.getBoundingBox() == box, "raw movement must not update AABB");
                helper.assertTrue(source.onClimbable(), "raw movement expires vanilla block cache");
                assertRun(helper, entities, batch, "raw position enters climbable block");
                source.setPosRaw(position.x, position.y, position.z);
                helper.assertTrue(!source.onClimbable(), "raw movement restores original block");
                assertRun(helper, entities, batch, "raw position leaves climbable block");
                helper.assertTrue(source.startRiding(firstRoot, true, true), "source branch mounted");
                helper.assertTrue(target.startRiding(secondRoot, true, true), "target branch mounted");
                assertRun(helper, entities, batch, "separate vehicle trees");
                helper.assertTrue(firstRoot.startRiding(secondRoot, true, true), "ancestor branch joined");
                helper.assertTrue(source.isPassengerOfSameVehicle(target), "ancestor change establishes shared root");
                assertRun(helper, entities, batch, "ancestor root joined");
                firstRoot.stopRiding();
                helper.assertTrue(!source.isPassengerOfSameVehicle(target), "ancestor change separates roots");
                assertRun(helper, entities, batch, "ancestor root separated");
                source.stopRiding();
                target.stopRiding();
                assertRun(helper, entities, batch, "branches dismounted");
                helper.assertTrue(target.startRiding(source, true, true), "source now carries target");
                assertRun(helper, entities, batch, "source is vehicle");
                target.stopRiding();
                assertRun(helper, entities, batch, "source no longer vehicle");
                target.setRemoved(Entity.RemovalReason.DISCARDED);
                assertRun(helper, entities, batch, "removed target in retained candidates");
                ((EntityBodyTestAccess) target).eco$unsetRemoved();
                assertRun(helper, entities, batch, "target removal cleared");
            }
        }
    }

    private static void assertRun(GameTestHelper helper, List<LivingEntity> entities, PushBatch batch, String label) {
        reset(entities);
        for (int i = 0; i < batch.size(); i++) batch.target(i).push(entities.getFirst());
        Vec3[] expected = entities.stream().map(Entity::getDeltaMovement).toArray(Vec3[]::new);
        boolean[] sync = new boolean[entities.size()];
        for (int i = 0; i < sync.length; i++) sync[i] = entities.get(i).needsSync;
        reset(entities);
        batch.applyNativeRun(entities.getFirst(), 0, batch.size());
        for (int i = 0; i < entities.size(); i++) {
            NativeImpulseParity.exact(helper, entities.get(i).getDeltaMovement(), expected[i], label + " body=" + i);
            helper.assertValueEqual(entities.get(i).needsSync, sync[i], label + " sync=" + i);
        }
    }

    private static void reset(List<LivingEntity> entities) {
        for (int i = 0; i < entities.size(); i++) {
            entities.get(i).setDeltaMovement(new Vec3(.125 * i, -.0, -.25 * i));
            entities.get(i).needsSync = false;
        }
    }
}

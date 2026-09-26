package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityVelocityAccessor;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Delay observation, not physics: compare repeated native runs to the actual vanilla push sequence. */
final class AuthoritativeVelocityParity {
    static void verify(GameTestHelper helper) {
        for (int count : new int[]{2, 8, 20}) compare(helper, count);
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_AUTHORITATIVE_VELOCITY entity_counts=2,8,20 unobserved_runs=7 accessor_first=true result=passed");
    }

    private static void compare(GameTestHelper helper, int count) {
        try (var scene = new InteractionScene(helper)) {
            List<LivingEntity> entities = new ArrayList<>();
            for (int i = 0; i < count; i++) entities.add((LivingEntity) scene.spawn(EntityType.ZOMBIE,
                    new Vec3(4.5 + i % 5 * .03, 1, 4.5 + i / 5 * .04)));
            LivingEntity source = entities.getFirst();
            reset(entities);
            Vec3[] before = snapshot(entities);
            Field storage = storageField();
            Object[] unpublished = entities.stream().map(entity -> stored(storage, entity)).toArray();
            CollisionFrame.begin(helper.getLevel());
            try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
                helper.assertValueEqual(batch.size(), count - 1, "authoritative batch candidates");
                for (int run = 0; run < 7; run++) {
                    for (int i = 0; i < batch.size(); i++) batch.target(i).push(source);
                }
                Vec3[] expected = snapshot(entities);
                boolean[] expectedSync = new boolean[count];
                for (int i = 0; i < count; i++) expectedSync[i] = entities.get(i).needsSync;
                // Use the exact original references, not just equal components.
                for (int i = 0; i < count; i++) {
                    entities.get(i).setDeltaMovement(before[i]);
                    entities.get(i).needsSync = false;
                }
                for (int run = 0; run < 7; run++) batch.applyNativeRun(source, 0, batch.size());
                // Reflection is deliberately NOT the public velocity contract. It checks that
                // this fixture truly skipped eager field publication; accessor checks follow.
                for (int i = 0; i < count; i++) {
                    helper.assertTrue(stored(storage, entities.get(i)) == unpublished[i], "no eager Vec3 publication");
                    helper.assertValueEqual(entities.get(i).needsSync, expectedSync[i], "sync is immediate");
                    entities.get(i).needsSync = false;
                    entities.get(i).setDeltaMovement(Double.NaN, 0, 0);
                    // Read through a separately merged raw field accessor FIRST, before the vanilla getter.
                    Vec3 actual = ((EntityVelocityAccessor) entities.get(i)).eco$rawVelocity();
                    NativeImpulseParity.exact(helper, actual, expected[i], "unobserved runs body=" + i);
                    helper.assertTrue(entities.get(i).getDeltaMovement() == actual, "same-version snapshot reused");
                    helper.assertTrue(!entities.get(i).needsSync, "reading does not resurrect consumed sync");
                    Vec3 write = new Vec3(.625, -.0, -.125);
                    ((EntityVelocityAccessor) entities.get(i)).eco$rawVelocity(write);
                    helper.assertTrue(entities.get(i).getDeltaMovement() == write, "raw accessor writes same authority");
                    entities.get(i).addDeltaMovement(new Vec3(.125, 0, .25));
                    NativeImpulseParity.exact(helper, entities.get(i).getDeltaMovement(),
                            new Vec3(.75, 0, .125), "Java add reads and writes shared velocity");
                }
            }
        }
    }

    private static void reset(List<LivingEntity> entities) {
        for (int i = 0; i < entities.size(); i++) {
            entities.get(i).setDeltaMovement(new Vec3(i * .125, -.0, -i * .25));
            entities.get(i).needsSync = false;
        }
    }
    private static Vec3[] snapshot(List<LivingEntity> entities) {
        return entities.stream().map(Entity::getDeltaMovement).toArray(Vec3[]::new);
    }
    private static Field storageField() {
        try {
            Field field = Entity.class.getDeclaredField("deltaMovement");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object stored(Field field, Entity entity) {
        try { return field.get(entity); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }
}

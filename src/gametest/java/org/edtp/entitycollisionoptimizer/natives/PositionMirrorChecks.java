package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityBodyTestAccess;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.edtp.entitycollisionoptimizer.natives.CollisionStateTable.STRIDE_BYTES;

/** Read the shared row immediately after writes, without querying, binding again or executing native. */
public final class PositionMirrorChecks {
    public static void verify(GameTestHelper helper) {
        Entity entity = new Zombie(helper.getLevel());
        EntityBodyTestAccess fields = (EntityBodyTestAccess) entity;
        entity.setPos(1, 2, 3);
        try (var table = new CollisionStateTable(); var next = new CollisionStateTable()) {
            int slot = table.slot(entity);
            check(helper, table, slot, entity, "initial binding");
            var memory = table.row(slot);
            memory.set(JAVA_DOUBLE, 0, -0.0);
            memory.set(JAVA_DOUBLE, 8, -13.0);
            memory.set(JAVA_DOUBLE, CollisionStateTable.Y_OFFSET, 67.5);
            memory.set(JAVA_LONG, CollisionStateTable.POSITION_VERSION_OFFSET,
                    memory.get(JAVA_LONG, CollisionStateTable.POSITION_VERSION_OFFSET) + 1);
            helper.assertValueEqual(Double.doubleToRawLongBits(entity.getX()), Long.MIN_VALUE, "shared X owns negative zero");
            helper.assertTrue(entity.getY() == 67.5 && entity.getZ() == -13, "all position getters read shared authority");
            helper.assertTrue(entity.position() == fields.eco$rawPosition(), "merged field reader uses authoritative cache");
            entity.setPosRaw(4, 5, 6);
            check(helper, table, slot, entity, "raw position write");
            for (Vec3 position : new Vec3[]{new Vec3(-0.0, 0, -0.0), new Vec3(-29_999_999, 64, 29_999_999),
                    new Vec3(Math.nextDown(1.0), -64, Math.nextUp(-1.0))}) {
                fields.eco$rawPosition(position);
                helper.assertTrue(fields.eco$rawPosition() == position && entity.position() == position,
                        "raw position write preserves Java reference identity");
                check(helper, table, slot, entity, "merged accessor write");
            }
            // Bounding boxes are not position: changing one must not overwrite the other.
            entity.setBoundingBox(entity.getBoundingBox().move(100, 10, 100));
            check(helper, table, slot, entity, "independent bounding box");
            table.borrow();
            try {
                int capacity = table.capacity();
                for (int i = 0; i <= capacity; i++) table.slot(new Zombie(helper.getLevel()));
                helper.assertTrue(table.capacity() > capacity && table.entity(slot) == entity, "borrowed row survives growth");
                entity.setPosRaw(7, 8, 9);
                check(helper, table, slot, entity, "write after arena replacement");
            } finally { table.release(); }
            int nextSlot = next.slot(entity); // Transfers ownership, including its current position.
            helper.assertTrue(!table.bound(slot) && next.bound(nextSlot), "one table owns the binding");
            entity.setPosRaw(10, 11, 12);
            check(helper, next, nextSlot, entity, "write after table transfer");
            helper.assertValueEqual(table.memory().get(JAVA_DOUBLE, (long) slot * STRIDE_BYTES), 7.0,
                    "former table no longer receives position writes");
            table.clear();
            entity.setPosRaw(13, 14, 15);
            check(helper, next, nextSlot, entity, "former clear cannot detach current owner");
            next.retire(entity);
            entity.setPosRaw(16, 17, 18);
            helper.assertTrue(!next.bound(nextSlot), "retired entity stays detached");
            int reused = next.slot(entity);
            helper.assertValueEqual(reused, nextSlot, "fixture reuses retired row");
            check(helper, next, reused, entity, "reused slot initializes latest position");
            next.clear();
            entity.setPosRaw(19, 20, 21);
            check(helper, next, next.slot(entity), entity, "disable/re-enable binding");
        }
        entity.setPosRaw(22, 23, 24);
        helper.assertTrue(entity.getX() == 22 && entity.getZ() == 24, "closed table receives no writes");
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_POSITION_MIRROR_CHECKS immediate_writes=true accessor=true growth=true transfer=true retirement=true result=passed");
    }

    private static void check(GameTestHelper helper, CollisionStateTable table, int slot, Entity entity, String label) {
        long offset = (long) slot * STRIDE_BYTES;
        helper.assertValueEqual(Double.doubleToRawLongBits(table.memory().get(JAVA_DOUBLE, offset)),
                Double.doubleToRawLongBits(entity.getX()), "shared X " + label);
        helper.assertValueEqual(Double.doubleToRawLongBits(table.memory().get(JAVA_DOUBLE, offset + Double.BYTES)),
                Double.doubleToRawLongBits(entity.getZ()), "shared Z " + label);
        helper.assertValueEqual(Double.doubleToRawLongBits(table.memory().get(JAVA_DOUBLE, offset + CollisionStateTable.Y_OFFSET)),
                Double.doubleToRawLongBits(entity.getY()), "shared Y " + label);
    }
}

package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.CollisionBodyAccess;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.edtp.entitycollisionoptimizer.natives.CollisionStateTable.*;

/** Table lifetime checks complement end-to-end vanilla oracles; no production diagnostic counters. */
public final class CollisionStateTableChecks {
    public static void verify(GameTestHelper helper) {
        try (var table = new CollisionStateTable()) {
            Entity first = new Zombie(helper.getLevel()), second = new Zombie(helper.getLevel());
            first.setPos(1, 2, 3);
            second.setPos(4, 5, 6);
            first.setDeltaMovement(new Vec3(.125, -.0, -.25));
            int firstSlot = table.slot(first), secondSlot = table.slot(second);
            helper.assertTrue(firstSlot != secondSlot && table.slot(first) == firstSlot, "persistent entity slots");
            ((CollisionBodyAccess) first).eco$bindBody(table, firstSlot);
            long velocityOffset = (long) firstSlot * STRIDE_BYTES + 16;
            // Native is authoritative: synchronizing an already-bound body must not overwrite it.
            table.memory().set(JAVA_DOUBLE, velocityOffset, 17.0);
            nativeVersion(table, firstSlot);
            helper.assertTrue(first.needsSync, "native sync is visible before any velocity getter");
            first.needsSync = false;
            helper.assertTrue(!table.needsSync(firstSlot), "Java consumes the authoritative sync bit");
            ((CollisionBodyAccess) first).eco$bindBody(table, firstSlot);
            helper.assertTrue(table.memory().get(JAVA_DOUBLE, velocityOffset) == 17, "unchanged body reused");
            helper.assertTrue(first.getDeltaMovement().x == 17, "native value visible without eager publication");
            first.setDeltaMovement(new Vec3(.125, -.0, -.25));
            ((CollisionBodyAccess) first).eco$bindBody(table, firstSlot);
            helper.assertTrue(table.memory().get(JAVA_DOUBLE, velocityOffset) == .125, "new reference refreshes body");
            table.borrow();
            try {
                table.prune(entity -> entity == second);
                helper.assertTrue(table.entity(firstSlot) == first, "borrowed candidate survives retirement");
                int oldCapacity = table.capacity();
                for (int i = 0; i <= oldCapacity; i++) table.slot(new Zombie(helper.getLevel()));
                helper.assertTrue(table.capacity() > oldCapacity, "table actually grows");
                helper.assertTrue(table.memory().get(JAVA_DOUBLE, velocityOffset) == .125, "growth preserves body contents");
                helper.assertTrue(table.slot(first) == firstSlot, "growth preserves IDs");
                helper.assertTrue(!first.needsSync, "growth preserves consumed sync");
            } finally { table.release(); }
            table.memory().set(JAVA_DOUBLE, velocityOffset, 19.0);
            nativeVersion(table, firstSlot);
            table.prune(entity -> entity == second);
            helper.assertTrue(first.getDeltaMovement().x == 19, "prune preserves an unobserved native velocity");
            helper.assertTrue(first.needsSync, "prune preserves pending native sync");
            int replacement = table.slot(first);
            ((CollisionBodyAccess) first).eco$bindBody(table, replacement);
            helper.assertTrue(table.memory().get(JAVA_DOUBLE, (long) replacement * STRIDE_BYTES) == first.getX(), "reused slot refreshed");
            helper.assertTrue(table.entity(secondSlot) == second, "prune preserves surviving IDs");
        }
        verifyOwnershipTransfer(helper);
    }

    private static void verifyOwnershipTransfer(GameTestHelper helper) {
        Entity entity = new Zombie(helper.getLevel());
        var access = (CollisionBodyAccess) entity;
        try (var former = new CollisionStateTable(); var current = new CollisionStateTable()) {
            int oldSlot = former.slot(entity), slot = current.slot(entity);
            access.eco$bindBody(former, oldSlot);
            former.memory().set(JAVA_DOUBLE, (long) oldSlot * STRIDE_BYTES + 16, 23.0);
            nativeVersion(former, oldSlot);
            access.eco$bindBody(current, slot);
            helper.assertTrue(current.velocity(slot).x == 23, "rebind reads the former native owner");
            helper.assertTrue(entity.needsSync && current.needsSync(slot), "rebind preserves pending sync");
            current.memory().set(JAVA_DOUBLE, (long) slot * STRIDE_BYTES + 16, 29.0);
            nativeVersion(current, slot);
            entity.needsSync = false;
            former.prune(candidate -> false);
            helper.assertTrue(entity.getDeltaMovement().x == 29, "former owner cannot detach current owner");
            helper.assertTrue(!entity.needsSync, "former owner cannot republish consumed sync");
            current.memory().set(JAVA_DOUBLE, (long) slot * STRIDE_BYTES + 16, 31.0);
            nativeVersion(current, slot);
        }
        helper.assertTrue(entity.getDeltaMovement().x == 31, "closing table preserves unobserved native velocity");
        helper.assertTrue(entity.needsSync, "closing table preserves unobserved native sync");
        entity.setDeltaMovement(new Vec3(37, 0, 0));
        helper.assertTrue(entity.getDeltaMovement().x == 37, "detached writes do not touch freed memory");
    }

    private static void nativeVersion(CollisionStateTable table, int slot) {
        long offset = (long) slot * STRIDE_BYTES + VERSION_OFFSET;
        table.memory().set(JAVA_LONG, offset, table.memory().get(JAVA_LONG, offset) + 1);
        table.needsSync(slot, true);
    }
}

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
            helper.assertValueEqual(((CollisionBodyAccess) first).eco$boundBodySlot(table), -1, "unbound body has no slot");
            int firstSlot = table.bindBody(first), secondSlot = table.bindBody(second);
            helper.assertTrue(firstSlot != secondSlot && table.bindBody(first) == firstSlot, "persistent entity slots");
            ((CollisionBodyAccess) first).eco$bindBody(table, firstSlot);
            long velocityOffset = (long) firstSlot * STRIDE_BYTES + 16;
            // Native is authoritative: synchronizing an already-bound body must not overwrite it.
            table.memory().set(JAVA_DOUBLE, velocityOffset, 17.0);
            nativeVersion(table, firstSlot);
            helper.assertTrue(first.needsSync, "native sync is visible before any velocity getter");
            first.needsSync = false;
            helper.assertTrue(!table.needsSync(firstSlot), "Java consumes the authoritative sync bit");
            helper.assertValueEqual(table.bindBody(first), firstSlot, "binding hit keeps the current slot");
            helper.assertTrue(table.memory().get(JAVA_DOUBLE, velocityOffset) == 17, "unchanged body reused");
            helper.assertTrue(first.getDeltaMovement().x == 17, "native value visible without eager publication");
            first.setDeltaMovement(new Vec3(.125, -.0, -.25));
            ((CollisionBodyAccess) first).eco$bindBody(table, firstSlot);
            helper.assertTrue(table.memory().get(JAVA_DOUBLE, velocityOffset) == .125, "new reference refreshes body");
            Entity deferred = new Zombie(helper.getLevel());
            int deferredSlot = table.bindBody(deferred);
            table.borrow();
            try {
                table.retire(deferred);
                helper.assertTrue(table.entity(deferredSlot) == deferred, "borrowed slot is not released or reused");
                table.borrow();
                try {
                    table.retire(first);
                    helper.assertValueEqual(table.bindBody(first), firstSlot,
                            "binding an owned body cancels its deferred retirement");
                } finally { table.release(); }
                int oldCapacity = table.capacity();
                for (int i = 0; i <= oldCapacity; i++) table.bindBody(new Zombie(helper.getLevel()));
                helper.assertTrue(table.capacity() > oldCapacity, "table actually grows");
                helper.assertTrue(table.memory().get(JAVA_DOUBLE, velocityOffset) == .125, "growth preserves body contents");
                helper.assertTrue(table.bindBody(first) == firstSlot, "growth preserves IDs");
                helper.assertTrue(!first.needsSync, "growth preserves consumed sync");
            } finally { table.release(); }
            helper.assertTrue(!table.bound(deferredSlot), "release applies the deferred retirement");
            helper.assertTrue(table.bound(firstSlot), "cancelled retirement survives the outer lease release");
            helper.assertValueEqual(((CollisionBodyAccess) deferred).eco$boundBodySlot(table), -1,
                    "retirement clears the entity binding");
            Entity reused = new Zombie(helper.getLevel());
            helper.assertValueEqual(table.bindBody(reused), deferredSlot, "retired slot reused by another entity");
            int rebound = table.bindBody(deferred);
            helper.assertTrue(rebound != deferredSlot && table.entity(deferredSlot) == reused,
                    "rebound body never returns another entity's recycled slot");
            table.memory().set(JAVA_DOUBLE, velocityOffset, 19.0);
            nativeVersion(table, firstSlot);
            table.retire(first);
            helper.assertTrue(first.getDeltaMovement().x == 19, "retire preserves an unobserved native velocity");
            helper.assertTrue(first.needsSync, "retire preserves pending native sync");
            int replacement = table.bindBody(first);
            ((CollisionBodyAccess) first).eco$bindBody(table, replacement);
            helper.assertTrue(table.memory().get(JAVA_DOUBLE, (long) replacement * STRIDE_BYTES) == first.getX(), "reused slot refreshed");
            helper.assertTrue(table.entity(secondSlot) == second, "retire leaves other slots untouched");
        }
        verifyOwnershipTransfer(helper);
        verifyIndexedSlotOwnership(helper);
        verifyIndexedSlotReuse(helper);
        verifyIndexedSlotGrowth(helper);
    }

    private static void verifyIndexedSlotOwnership(GameTestHelper helper) {
        var ids = new PersistentEntityIds();
        Entity entity = new Zombie(helper.getLevel());
        try (var former = new CollisionStateTable(); var current = new CollisionStateTable()) {
            int nativeId = ids.addEntity(entity);
            int formerSlot = former.bindBody(entity);
            ids.bodySlotsByNativeId(former)[nativeId] = formerSlot;
            helper.assertValueEqual(ids.bodySlotsByNativeId(former)[nativeId], formerSlot,
                    "stable association is reused");
            int currentSlot = current.bindBody(entity);
            helper.assertValueEqual(formerSlot, currentSlot, "fixture uses equal numeric slots in different tables");
            helper.assertValueEqual(ids.bodySlotsByNativeId(former)[nativeId], -1,
                    "ownership transfer invalidates the former association");
            ids.bodySlotsByNativeId(former)[nativeId] = former.bindBody(entity);
            helper.assertValueEqual(ids.bodySlotsByNativeId(current)[nativeId], -1,
                    "another table cannot use the same numeric slot");
        } finally { ids.clear(); }
    }

    private static void verifyIndexedSlotReuse(GameTestHelper helper) {
        var ids = new PersistentEntityIds();
        Entity original = new Zombie(helper.getLevel());
        try (var owner = new CollisionStateTable()) {
            int nativeId = ids.addEntity(original);
            int[] bodySlotsByNativeId = ids.bodySlotsByNativeId(owner);
            helper.assertValueEqual(bodySlotsByNativeId[nativeId], -1, "new spatial ID has no body association");
            helper.assertValueEqual(((CollisionBodyAccess) original).eco$boundBodySlot(owner), -1,
                    "indexing alone does not bind a body");
            int originalSlot = owner.bindBody(original);
            bodySlotsByNativeId[nativeId] = originalSlot;
            owner.borrow();
            int replacementSlot;
            try {
                ids.removeEntity(original);
                owner.retire(original);
                Entity replacement = new Zombie(helper.getLevel());
                helper.assertValueEqual(ids.addEntity(replacement), nativeId, "fixture reuses the spatial ID");
                helper.assertValueEqual(bodySlotsByNativeId[nativeId], -1,
                        "spatial ID reuse cannot retain the old body association");
                replacementSlot = owner.bindBody(replacement);
                helper.assertTrue(replacementSlot != originalSlot && owner.entity(originalSlot) == original,
                        "borrowed body survives spatial ID reuse");
                bodySlotsByNativeId[nativeId] = replacementSlot;
            } finally { owner.release(); }
            helper.assertValueEqual(ids.bodySlotsByNativeId(owner)[nativeId], replacementSlot,
                    "ordinary retirement does not invalidate unrelated live associations");
            helper.assertTrue(owner.bound(replacementSlot), "replacement remains bound after the old lease is released");
        } finally { ids.clear(); }
    }

    private static void verifyIndexedSlotGrowth(GameTestHelper helper) {
        var ids = new PersistentEntityIds();
        Entity entity = new Zombie(helper.getLevel());
        try (var owner = new CollisionStateTable()) {
            int nativeId = ids.addEntity(entity);
            int bodySlot = owner.bindBody(entity);
            int[] bodySlotsByNativeId = ids.bodySlotsByNativeId(owner);
            bodySlotsByNativeId[nativeId] = bodySlot;
            int oldCapacity = bodySlotsByNativeId.length;
            for (int i = 0; i <= oldCapacity; i++) ids.addEntity(new Zombie(helper.getLevel()));
            int[] grownBodySlots = ids.bodySlotsByNativeId(owner);
            helper.assertTrue(grownBodySlots.length > oldCapacity, "spatial index actually grows");
            helper.assertValueEqual(grownBodySlots[nativeId], bodySlot, "index growth preserves existing associations");
            helper.assertValueEqual(grownBodySlots[ids.nativeIdCapacity() - 1], -1,
                    "index growth leaves new entities unbound");
        } finally { ids.clear(); }
    }

    private static void verifyOwnershipTransfer(GameTestHelper helper) {
        Entity entity = new Zombie(helper.getLevel());
        var access = (CollisionBodyAccess) entity;
        try (var former = new CollisionStateTable(); var current = new CollisionStateTable()) {
            int oldSlot = former.bindBody(entity), slot = current.bindBody(entity);
            helper.assertValueEqual(oldSlot, slot, "fixture uses equal numeric slots in different tables");
            helper.assertValueEqual(access.eco$boundBodySlot(former), -1, "slot lookup is owner-qualified");
            helper.assertValueEqual(access.eco$boundBodySlot(current), slot, "current owner exposes its slot");
            helper.assertValueEqual(former.bindBody(entity), oldSlot, "transfer reuses the former table's retained slot");
            former.memory().set(JAVA_DOUBLE, (long) oldSlot * STRIDE_BYTES + 16, 23.0);
            nativeVersion(former, oldSlot);
            helper.assertValueEqual(current.bindBody(entity), slot, "transfer back restores the current table's slot");
            helper.assertTrue(current.velocity(slot).x == 23, "rebind reads the former native owner");
            helper.assertTrue(entity.needsSync && current.needsSync(slot), "rebind preserves pending sync");
            current.memory().set(JAVA_DOUBLE, (long) slot * STRIDE_BYTES + 16, 29.0);
            nativeVersion(current, slot);
            entity.needsSync = false;
            former.retire(entity);
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

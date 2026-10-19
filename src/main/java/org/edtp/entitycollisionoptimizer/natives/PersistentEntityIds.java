package org.edtp.entitycollisionoptimizer.natives;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.world.entity.Entity;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;

import java.util.Arrays;

/** Level-local spatial slots; body leases use their separate lifetime-managed slots. */
final class PersistentEntityIds {
    private Entity[] entities = new Entity[256];
    // Association between the two independently leased indices, not a copy of body state.
    private int[] bodySlotsByNativeId = new int[256];
    private CollisionStateTable bodyTable;
    private long bodyOwnershipTransferRevision;
    private final Reference2IntOpenHashMap<Entity> ids = new Reference2IntOpenHashMap<>();
    private final IntArrayFIFOQueue free = new IntArrayFIFOQueue();
    private int size;

    PersistentEntityIds() {
        ids.defaultReturnValue(-1);
        Arrays.fill(bodySlotsByNativeId, -1);
    }

    int addEntity(Entity entity) {
        int existingNativeId = ids.getInt(entity);
        if (existingNativeId >= 0) return existingNativeId;
        int nativeId = free.isEmpty() ? size++ : free.dequeueInt();
        if (size > entities.length) {
            int oldCapacity = entities.length;
            int capacity = size + (size >> 1);
            entities = Arrays.copyOf(entities, capacity);
            bodySlotsByNativeId = Arrays.copyOf(bodySlotsByNativeId, capacity);
            Arrays.fill(bodySlotsByNativeId, oldCapacity, capacity, -1);
        }
        entities[nativeId] = entity;
        bodySlotsByNativeId[nativeId] = -1;
        ids.put(entity, nativeId);
        ((CollisionCacheState) entity).entityCollisionOptimizer$setIndexed(true);
        return nativeId;
    }
    int removeEntity(Entity entity) {
        int nativeId = ids.removeInt(entity);
        if (nativeId < 0) return -1;
        entities[nativeId] = null;
        bodySlotsByNativeId[nativeId] = -1;
        free.enqueue(nativeId);
        ((CollisionCacheState) entity).entityCollisionOptimizer$setIndexed(false);
        return nativeId;
    }
    void clear() {
        for (Entity entity : entities) {
            if (entity != null) ((CollisionCacheState) entity).entityCollisionOptimizer$setIndexed(false);
        }
        Arrays.fill(entities, null);
        Arrays.fill(bodySlotsByNativeId, -1);
        bodyTable = null;
        ids.clear();
        free.clear();
        size = 0;
    }
    Entity getEntity(int nativeId) {
        return nativeId < 0 || nativeId >= size ? null : entities[nativeId];
    }
    int getNativeId(Entity entity) { return ids.getInt(entity); }
    boolean contains(Entity entity) { return ids.containsKey(entity); }
    int nativeIdCapacity() { return size; }

    /** Mutable associations (-1 when unresolved); borrow only during callback-free batch preparation. */
    int[] bodySlotsByNativeId(CollisionStateTable table) {
        long transferRevision = table.ownershipTransferRevision();
        if (bodyTable != table || bodyOwnershipTransferRevision != transferRevision) {
            // A body can transfer out without leaving this spatial index (e.g. a custom root).
            // Ordinary tracking removals clear only their own ID above, not the whole table.
            Arrays.fill(bodySlotsByNativeId, 0, size, -1);
            bodyTable = table;
            bodyOwnershipTransferRevision = transferRevision;
        }
        return bodySlotsByNativeId;
    }
}

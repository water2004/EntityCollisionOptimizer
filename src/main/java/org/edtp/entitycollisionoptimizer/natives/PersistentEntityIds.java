package org.edtp.entitycollisionoptimizer.natives;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.world.entity.Entity;

import java.util.Arrays;

/** Level-local spatial slots; body leases use their separate lifetime-managed slots. */
final class PersistentEntityIds {
    private Entity[] entities = new Entity[256];
    private final Reference2IntOpenHashMap<Entity> ids = new Reference2IntOpenHashMap<>();
    private final IntArrayFIFOQueue free = new IntArrayFIFOQueue();
    private int size;

    PersistentEntityIds() {
        ids.defaultReturnValue(-1);
    }

    int addEntity(Entity entity) {
        int existingNativeId = ids.getInt(entity);
        if (existingNativeId >= 0) return existingNativeId;
        int nativeId = free.isEmpty() ? size++ : free.dequeueInt();
        if (size > entities.length) entities = Arrays.copyOf(entities, size + (size >> 1));
        entities[nativeId] = entity;
        ids.put(entity, nativeId);
        return nativeId;
    }
    int removeEntity(Entity entity) {
        int nativeId = ids.removeInt(entity);
        if (nativeId < 0) return -1;
        entities[nativeId] = null;
        free.enqueue(nativeId);
        return nativeId;
    }
    Entity getEntity(int nativeId) {
        return nativeId < 0 || nativeId >= size ? null : entities[nativeId];
    }
    int getNativeId(Entity entity) { return ids.getInt(entity); }
    boolean contains(Entity entity) { return ids.containsKey(entity); }
    int nativeIdCapacity() { return size; }
}

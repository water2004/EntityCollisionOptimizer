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
        int old = ids.getInt(entity);
        if (old >= 0) return old;
        int id = free.isEmpty() ? size++ : free.dequeueInt();
        if (size > entities.length) entities = Arrays.copyOf(entities, size + (size >> 1));
        entities[id] = entity;
        ids.put(entity, id);
        return id;
    }
    int remove(Entity entity) {
        int id = ids.removeInt(entity);
        if (id < 0) return -1;
        entities[id] = null;
        free.enqueue(id);
        return id;
    }
    Entity getEntity(int id) { return id < 0 || id >= size ? null : entities[id]; }
    int getId(Entity entity) { return ids.getInt(entity); }
    boolean contains(Entity entity) { return ids.containsKey(entity); }
    int size() { return size; }
    void clear() {
        Arrays.fill(entities, 0, size, null);
        size = 0; ids.clear(); free.clear();
    }
}

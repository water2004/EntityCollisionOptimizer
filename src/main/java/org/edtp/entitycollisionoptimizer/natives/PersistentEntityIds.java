package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;

/** Level-local spatial slots; body leases use their separate lifetime-managed slots. */
final class PersistentEntityIds {
    private Entity[] entities = new Entity[256];
    private final IdentityHashMap<Entity, Integer> ids = new IdentityHashMap<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private int size;

    int addEntity(Entity entity) {
        Integer old = ids.get(entity);
        if (old != null) return old;
        int id = free.isEmpty() ? size++ : free.removeFirst();
        if (size > entities.length) entities = Arrays.copyOf(entities, size + (size >> 1));
        entities[id] = entity;
        ids.put(entity, id);
        return id;
    }
    int remove(Entity entity) {
        Integer id = ids.remove(entity);
        if (id == null) return -1;
        entities[id] = null;
        free.addLast(id);
        return id;
    }
    Entity getEntity(int id) { return id < 0 || id >= size ? null : entities[id]; }
    int getId(Entity entity) { return ids.getOrDefault(entity, -1); }
    boolean contains(Entity entity) { return ids.containsKey(entity); }
    int size() { return size; }
    void clear() {
        Arrays.fill(entities, 0, size, null);
        size = 0; ids.clear(); free.clear();
    }
}

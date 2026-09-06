package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;

import java.util.Arrays;
import java.util.IdentityHashMap;

/** Entity IDs local to one level's native collision frame. */
final class TempID {
    private Entity[] frameSnapshot = new Entity[10_000];
    private final IdentityHashMap<Entity, Integer> ids = new IdentityHashMap<>();
    private int currentIndex;

    void tickStart() {
        if (currentIndex > 0) {
            Arrays.fill(frameSnapshot, 0, currentIndex, null);
        }
        ids.clear();
        currentIndex = 0;
    }

    int addEntity(Entity entity) {
        if (currentIndex >= frameSnapshot.length) {
            resize();
        }
        int tempId = currentIndex++;
        frameSnapshot[tempId] = entity;
        ids.put(entity, tempId);
        return tempId;
    }

    Entity getEntity(int id) {
        if (id < 0 || id >= currentIndex) {
            return null;
        }
        return frameSnapshot[id];
    }

    int getId(Entity entity) {
        Integer id = ids.get(entity);
        return id == null ? -1 : id;
    }

    boolean contains(Entity entity) {
        int id = getId(entity);
        return id >= 0 && id < currentIndex && frameSnapshot[id] == entity;
    }

    int size() {
        return currentIndex;
    }

    private void resize() {
        int newSize = frameSnapshot.length + (frameSnapshot.length >> 1);
        frameSnapshot = Arrays.copyOf(frameSnapshot, newSize);
    }
}

package org.edtp.entitycollisionoptimizer.natives;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import net.minecraft.world.phys.AABB;
import static java.lang.foreign.ValueLayout.*;

/** Persistent movement geometry, kept separate from the densely accessed push rows. */
final class CollisionBounds {
    private static final int STRIDE = 56, VERSION = 48;
    private MemorySegment memory = MemorySegment.NULL;
    private AABB[] boxes = new AABB[0];
    private long[] versions = new long[0];

    void capacity(int capacity) {
        if (capacity <= boxes.length) return;
        MemorySegment next = Arena.ofAuto().allocate((long) capacity * STRIDE, 8);
        MemorySegment.copy(memory, 0, next, 0, memory.byteSize());
        memory = next;
        boxes = Arrays.copyOf(boxes, capacity);
        versions = Arrays.copyOf(versions, capacity);
    }

    void set(int slot, AABB box) {
        long offset = (long) slot * STRIDE;
        memory.set(JAVA_DOUBLE, offset, box.minX);
        memory.set(JAVA_DOUBLE, offset + 8, box.minY);
        memory.set(JAVA_DOUBLE, offset + 16, box.minZ);
        memory.set(JAVA_DOUBLE, offset + 24, box.maxX);
        memory.set(JAVA_DOUBLE, offset + 32, box.maxY);
        memory.set(JAVA_DOUBLE, offset + 40, box.maxZ);
        long version = memory.get(JAVA_LONG, offset + VERSION) + 1;
        memory.set(JAVA_LONG, offset + VERSION, version);
        boxes[slot] = box;
        versions[slot] = version;
    }

    AABB get(int slot) {
        long offset = (long) slot * STRIDE;
        long version = memory.get(JAVA_LONG, offset + VERSION);
        if (boxes[slot] != null && versions[slot] == version) return boxes[slot];
        AABB box = new AABB(memory.get(JAVA_DOUBLE, offset), memory.get(JAVA_DOUBLE, offset + 8),
                memory.get(JAVA_DOUBLE, offset + 16), memory.get(JAVA_DOUBLE, offset + 24),
                memory.get(JAVA_DOUBLE, offset + 32), memory.get(JAVA_DOUBLE, offset + 40));
        boxes[slot] = box;
        versions[slot] = version;
        return box;
    }

    MemorySegment row(int slot) { return memory.asSlice((long) slot * STRIDE, STRIDE); }
    void forget(int slot) { boxes[slot] = null; }
    void clear() { Arrays.fill(boxes, null); }
}

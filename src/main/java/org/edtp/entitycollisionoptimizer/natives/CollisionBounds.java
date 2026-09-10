package org.edtp.entitycollisionoptimizer.natives;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.world.phys.AABB;
import sun.misc.Unsafe;

/** Persistent movement geometry, kept separate from the densely accessed push rows. */
final class CollisionBounds {
    private static final int STRIDE = 56, VERSION = 48;
    private static final Unsafe UNSAFE = unsafe();
    private MemorySegment memory = MemorySegment.NULL;
    private long address;
    private AABB[] boxes = new AABB[0];
    private long[] versions = new long[0];

    void capacity(int capacity) {
        if (capacity <= boxes.length) return;
        MemorySegment next = Arena.ofAuto().allocate((long) capacity * STRIDE, 8);
        MemorySegment.copy(memory, 0, next, 0, memory.byteSize());
        memory = next;
        address = next.address();
        boxes = Arrays.copyOf(boxes, capacity);
        versions = Arrays.copyOf(versions, capacity);
    }

    void set(int slot, AABB box) {
        long offset = address + (long) slot * STRIDE;
        UNSAFE.putDouble(offset, box.minX);
        UNSAFE.putDouble(offset + 8, box.minY);
        UNSAFE.putDouble(offset + 16, box.minZ);
        UNSAFE.putDouble(offset + 24, box.maxX);
        UNSAFE.putDouble(offset + 32, box.maxY);
        UNSAFE.putDouble(offset + 40, box.maxZ);
        long version = UNSAFE.getLong(offset + VERSION) + 1;
        UNSAFE.putLong(offset + VERSION, version);
        boxes[slot] = box;
        versions[slot] = version;
    }

    AABB get(int slot) {
        // The shared row stays authoritative: external writers bump the
        // version, and a single raw load keeps this path inlinable.
        AABB box = boxes[slot];
        if (box != null && versions[slot] == UNSAFE.getLong(address + (long) slot * STRIDE + VERSION)) {
            return box;
        }
        return materialize(slot);
    }

    private AABB materialize(int slot) {
        long offset = address + (long) slot * STRIDE;
        AABB box = new AABB(UNSAFE.getDouble(offset), UNSAFE.getDouble(offset + 8),
                UNSAFE.getDouble(offset + 16), UNSAFE.getDouble(offset + 24),
                UNSAFE.getDouble(offset + 32), UNSAFE.getDouble(offset + 40));
        boxes[slot] = box;
        versions[slot] = UNSAFE.getLong(offset + VERSION);
        return box;
    }

    MemorySegment row(int slot) { return memory.asSlice((long) slot * STRIDE, STRIDE); }
    void forget(int slot) { boxes[slot] = null; }
    void clear() { Arrays.fill(boxes, null); }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
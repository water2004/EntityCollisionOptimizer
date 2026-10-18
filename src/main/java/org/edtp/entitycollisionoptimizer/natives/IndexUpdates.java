package org.edtp.entitycollisionoptimizer.natives;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import net.minecraft.world.phys.AABB;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Ordered index publications, consumed before native queries and membership changes.
 * Owned by one Context and accessed only under its monitor; this is not a concurrent queue.
 * Layout is shared with state/index_updates.h. No Java callbacks run while writing a record.
 */
final class IndexUpdates implements AutoCloseable {
    private static final int CAPACITY = 256, HEADER = 16, STRIDE = 56;
    private static final int BOUNDS = 1, SECTION = 2, STATE = 3, TEAM = 4,
            INVALIDATE_ENTITY = 5, INVALIDATE_FIELDS = 6;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment memory = arena.allocate(HEADER + (long) CAPACITY * STRIDE, 8);

    IndexUpdates() { memory.set(ADDRESS, 0, memory.asSlice(HEADER)); }

    MemorySegment header() { return memory.asSlice(0, HEADER); }
    boolean full() { return count() == CAPACITY; }
    private int count() { return memory.get(JAVA_INT, 8); }
    private void publish(int count) { memory.set(JAVA_INT, 8, count + 1); }

    private long record(int count, int kind, int nativeId) {
        long offset = HEADER + (long) count * STRIDE;
        memory.set(JAVA_INT, offset, kind);
        memory.set(JAVA_INT, offset + 4, nativeId);
        return offset + 8;
    }

    void bounds(int nativeId, AABB bounds) {
        int count = count();
        long offset = record(count, BOUNDS, nativeId);
        memory.set(JAVA_DOUBLE, offset, bounds.minX);
        memory.set(JAVA_DOUBLE, offset + 8, bounds.minY);
        memory.set(JAVA_DOUBLE, offset + 16, bounds.minZ);
        memory.set(JAVA_DOUBLE, offset + 24, bounds.maxX);
        memory.set(JAVA_DOUBLE, offset + 32, bounds.maxY);
        memory.set(JAVA_DOUBLE, offset + 40, bounds.maxZ);
        publish(count);
    }

    void section(int nativeId, int x, int y, int z) {
        int count = count();
        long offset = record(count, SECTION, nativeId);
        memory.set(JAVA_INT, offset, x);
        memory.set(JAVA_INT, offset + 4, y);
        memory.set(JAVA_INT, offset + 8, z);
        publish(count);
    }

    void state(int nativeId, boolean selectable, boolean passenger, boolean vanillaPush,
               boolean deferredVelocity, boolean hardCollidable) {
        int count = count();
        long offset = record(count, STATE, nativeId);
        int flags = (selectable ? 1 : 0) | (passenger ? 2 : 0) | (vanillaPush ? 4 : 0)
                | (deferredVelocity ? 8 : 0) | (hardCollidable ? 16 : 0);
        memory.set(JAVA_INT, offset, flags);
        publish(count);
    }

    void team(int nativeId, int teamId, int collisionRule, long queryEpoch) {
        int count = count();
        long offset = record(count, TEAM, nativeId);
        memory.set(JAVA_INT, offset, teamId);
        memory.set(JAVA_INT, offset + 4, collisionRule);
        memory.set(JAVA_LONG, offset + 8, queryEpoch);
        publish(count);
    }

    void invalidate(int nativeId) {
        int count = count();
        record(count, INVALIDATE_ENTITY, nativeId);
        publish(count);
    }

    void invalidateFields(int fields) {
        int count = count();
        long offset = record(count, INVALIDATE_FIELDS, -1);
        memory.set(JAVA_INT, offset, fields);
        publish(count);
    }

    @Override public void close() { arena.close(); }
}

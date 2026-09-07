package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.NativeVoxelAccess;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.*;

/** Per-call ordered references to reusable geometry. Reentrant calls own separate batches. */
public final class NativeShapeBatch implements AutoCloseable {
    private static final int STRIDE = 32;
    private static final ThreadLocal<ArrayDeque<Storage>> POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private final Storage storage;
    private int count;
    private boolean closed;

    public NativeShapeBatch() {
        Storage available = POOL.get().pollFirst();
        storage = available == null ? new Storage() : available;
    }

    private static final class Storage {
        MemorySegment references = MemorySegment.NULL;
        MemorySegment[] retained = new MemorySegment[0];
    }

    public void add(VoxelShape shape) { add(shape, 0, 0, 0, false); }
    public void addTranslated(VoxelShape shape, double x, double y, double z) { add(shape, x, y, z, true); }

    private void add(VoxelShape shape, double x, double y, double z, boolean translated) {
        if (closed) throw new IllegalStateException("Closed shape batch");
        if (count == storage.retained.length) {
            int capacity = count + (count >> 1) + 16;
            MemorySegment next = Arena.ofAuto().allocate((long) capacity * STRIDE, 8);
            if (count != 0) MemorySegment.copy(storage.references, 0, next, 0, (long) count * STRIDE);
            storage.references = next;
            storage.retained = Arrays.copyOf(storage.retained, capacity);
        }
        MemorySegment geometry = ((NativeVoxelAccess) shape).eco$nativeGeometry();
        storage.retained[count] = geometry; // Native pointers alone do not keep automatic arenas alive.
        long offset = (long) count++ * STRIDE;
        MemorySegment references = storage.references;
        // Native geometry is 8-byte aligned; encode the transform bit without a padded extra field.
        references.set(JAVA_LONG, offset, geometry.address() | (translated ? 1L : 0L));
        if (translated) {
            references.set(JAVA_DOUBLE, offset + 8, x);
            references.set(JAVA_DOUBLE, offset + 16, y);
            references.set(JAVA_DOUBLE, offset + 24, z);
        }
    }

    MemorySegment memory() { return storage.references; }
    int size() { return count; }
    @Override public void close() {
        if (closed) throw new IllegalStateException("Shape batch closed twice");
        closed = true;
        Arrays.fill(storage.retained, 0, count, null);
        count = 0;
        POOL.get().addFirst(storage);
    }
}

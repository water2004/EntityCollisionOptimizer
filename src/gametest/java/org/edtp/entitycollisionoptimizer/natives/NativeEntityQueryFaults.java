package org.edtp.entitycollisionoptimizer.natives;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Scoped mutation of actual native query results, without changing the index or production code. */
public final class NativeEntityQueryFaults implements AutoCloseable {
    public enum Fault { EMPTY, OMIT_FIRST, REVERSE }

    private final Field handle;
    private final MethodHandle original;
    private final Thread owner = Thread.currentThread();
    private final Fault fault;
    private int calls;

    public NativeEntityQueryFaults(Fault fault) {
        this.fault = fault;
        try {
            handle = FFMBackend.class.getDeclaredField("queryEntities");
            handle.setAccessible(true);
            original = (MethodHandle) handle.get(null);
            handle.set(null, MethodHandles.lookup().findVirtual(
                    NativeEntityQueryFaults.class, "query", original.type()).bindTo(this));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    public int calls() { return calls; }

    private int query(MemorySegment context, double minX, double minY, double minZ,
                      double maxX, double maxY, double maxZ, MemorySegment output, int capacity) throws Throwable {
        int count = (int) original.invokeExact(context, minX, minY, minZ, maxX, maxY, maxZ, output, capacity);
        if (Thread.currentThread() != owner) return count;
        calls++;
        if (count <= 0) return count;
        return switch (fault) {
            case EMPTY -> 0;
            case OMIT_FIRST -> {
                for (int i = 1; i < count; i++) output.setAtIndex(JAVA_INT, i - 1, output.getAtIndex(JAVA_INT, i));
                yield count - 1;
            }
            case REVERSE -> {
                for (int left = 0, right = count - 1; left < right; left++, right--) {
                    int value = output.getAtIndex(JAVA_INT, left);
                    output.setAtIndex(JAVA_INT, left, output.getAtIndex(JAVA_INT, right));
                    output.setAtIndex(JAVA_INT, right, value);
                }
                yield count;
            }
        };
    }

    @Override
    public void close() {
        try {
            handle.set(null, original);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }
}

package org.edtp.entitycollisionoptimizer.natives;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/** Test-only downcall observation: no production counter or alternate query implementation. */
public final class NativePushQueryObservation implements AutoCloseable {
    private final Field handle;
    private final MethodHandle original;
    private final Thread owner = Thread.currentThread();
    private int calls;

    public NativePushQueryObservation() {
        try {
            handle = FFMBackend.class.getDeclaredField("queryPushable");
            handle.setAccessible(true);
            original = (MethodHandle) handle.get(null);
            MethodHandle observer = MethodHandles.lookup().findVirtual(
                    NativePushQueryObservation.class, "observe", MethodType.methodType(int.class, int.class)).bindTo(this);
            handle.set(null, MethodHandles.filterReturnValue(original, observer));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    public int calls() { return calls; }

    private int observe(int result) {
        if (Thread.currentThread() == owner) calls++;
        return result;
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

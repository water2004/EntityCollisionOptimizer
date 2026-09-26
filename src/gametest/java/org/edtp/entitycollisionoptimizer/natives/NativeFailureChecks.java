package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/** Inject failures only in tests; never force an actual native allocation failure or corrupt a context. */
public final class NativeFailureChecks {
    public static void verify(GameTestHelper helper) {
        try (Arena arena = Arena.ofConfined()) {
            Field movement = handleField("movement");
            Field create = handleField("createContextHandle");
            Field detail = handleField("lastNativeExceptionHandle");
            MethodHandle originalMovement = (MethodHandle) movement.get(null);
            MethodHandle originalCreate = (MethodHandle) create.get(null);
            MethodHandle originalDetail = (MethodHandle) detail.get(null);
            try {
                MemorySegment message = arena.allocate(256);
                message.setString(0, "std::bad_alloc: failure fixture");
                detail.set(null, MethodHandles.constant(MemorySegment.class, message));
                movement.set(null, withArguments(MethodHandles.constant(int.class, -100), originalMovement));
                Error nativeFailure = expectError(helper, NativeFailureChecks::solve);
                helper.assertTrue(nativeFailure.getMessage().contains("std::bad_alloc: failure fixture"),
                        "native exception details reach Java");
                helper.assertTrue(nativeFailure.getMessage().contains("solve native movement"),
                        "native exception identifies its operation");

                create.set(null, MethodHandles.constant(MemorySegment.class, MemorySegment.NULL));
                Error creationFailure = expectError(helper, FFMBackend::createContext);
                helper.assertTrue(creationFailure.getMessage().contains("std::bad_alloc: failure fixture"),
                        "context creation preserves native exception details");

                Error fatal = new AssertionError("fatal fixture");
                movement.set(null, withArguments(
                        MethodHandles.throwException(int.class, Error.class).bindTo(fatal), originalMovement));
                helper.assertTrue(expectError(helper, NativeFailureChecks::solve) == fatal,
                        "fatal Java error propagates unchanged");

                RuntimeException recoverable = new IllegalArgumentException("recoverable fixture");
                movement.set(null, withArguments(MethodHandles.throwException(int.class, RuntimeException.class)
                        .bindTo(recoverable), originalMovement));
                try {
                    solve();
                    helper.fail("expected recoverable exception");
                } catch (IllegalStateException failure) {
                    helper.assertTrue(failure.getCause() == recoverable, "Java exception retains its cause");
                }

                // A successful downcall must not consult the diagnostic handle at all.
                movement.set(null, withArguments(MethodHandles.constant(int.class, 0), originalMovement));
                detail.set(null, MethodHandles.throwException(MemorySegment.class, Error.class).bindTo(fatal));
                solve();
            } finally {
                movement.set(null, originalMovement);
                create.set(null, originalCreate);
                detail.set(null, originalDetail);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field handleField(String name) throws ReflectiveOperationException {
        Field field = FFMBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static MethodHandle withArguments(MethodHandle replacement, MethodHandle original) {
        return MethodHandles.dropArguments(replacement, 0, original.type().parameterList());
    }

    private static void solve() {
        FFMBackend.solveMovement(MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, 0, 0);
    }

    private static Error expectError(GameTestHelper helper, Runnable action) {
        try {
            action.run();
        } catch (Error failure) {
            return failure;
        }
        throw helper.assertionException("expected fatal error");
    }
}

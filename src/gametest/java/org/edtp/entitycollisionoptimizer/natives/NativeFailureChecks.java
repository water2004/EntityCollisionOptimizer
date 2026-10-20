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
            Field query = handleField("queryHard");
            Field create = handleField("createContextHandle");
            Field detail = handleField("lastNativeExceptionHandle");
            Field flush = handleField("flushUpdates");
            MethodHandle originalQuery = (MethodHandle) query.get(null);
            MethodHandle originalCreate = (MethodHandle) create.get(null);
            MethodHandle originalDetail = (MethodHandle) detail.get(null);
            MethodHandle originalFlush = (MethodHandle) flush.get(null);
            try {
                MemorySegment message = arena.allocate(256);
                message.setString(0, "std::bad_alloc: failure fixture");
                detail.set(null, MethodHandles.constant(MemorySegment.class, message));
                try (var context = FFMBackend.createContext()) {
                    FFMBackend.insertEntity(context, 0, new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1),
                            0, 0, 0, false, false);
                    for (int index = 0; index < 256; index++) {
                        FFMBackend.invalidateEntityPushEligibilityCache(context, 0);
                    }
                    flush.set(null, withArguments(MethodHandles.constant(int.class, -100), originalFlush));
                    Error publicationFailure = expectError(helper,
                            () -> FFMBackend.invalidateEntityPushEligibilityCache(context, 0));
                    helper.assertTrue(publicationFailure.getMessage().contains("std::bad_alloc: failure fixture")
                                    && publicationFailure.getMessage().contains("shared index updates"),
                            "shared publication failure is fatal and retains native diagnostics");
                }
                flush.set(null, originalFlush);
                try (var context = FFMBackend.createContext()) {
                    query.set(null, withArguments(MethodHandles.constant(int.class, -100), originalQuery));
                    Error nativeFailure = expectError(helper, () -> query(context));
                    helper.assertTrue(nativeFailure.getMessage().contains("std::bad_alloc: failure fixture"),
                            "native exception details reach Java");
                    helper.assertTrue(nativeFailure.getMessage().contains("query hard collision entities"),
                            "native exception identifies its operation");

                    create.set(null, withArguments(
                            MethodHandles.constant(MemorySegment.class, MemorySegment.NULL), originalCreate));
                    Error creationFailure = expectError(helper, FFMBackend::createContext);
                    helper.assertTrue(creationFailure.getMessage().contains("std::bad_alloc: failure fixture"),
                            "context creation preserves native exception details");

                    Error fatal = new AssertionError("fatal fixture");
                    query.set(null, withArguments(
                            MethodHandles.throwException(int.class, Error.class).bindTo(fatal), originalQuery));
                    helper.assertTrue(expectError(helper, () -> query(context)) == fatal,
                            "fatal Java error propagates unchanged");

                    RuntimeException recoverable = new IllegalArgumentException("recoverable fixture");
                    query.set(null, withArguments(MethodHandles.throwException(int.class, RuntimeException.class)
                            .bindTo(recoverable), originalQuery));
                    try {
                        query(context);
                        helper.fail("expected recoverable exception");
                    } catch (IllegalStateException failure) {
                        helper.assertTrue(failure.getCause() == recoverable, "Java exception retains its cause");
                    }

                    // A successful downcall must not consult the diagnostic handle at all.
                    query.set(null, withArguments(MethodHandles.constant(int.class, 0), originalQuery));
                    detail.set(null, MethodHandles.throwException(MemorySegment.class, Error.class).bindTo(fatal));
                    query(context);
                }
            } finally {
                query.set(null, originalQuery);
                create.set(null, originalCreate);
                detail.set(null, originalDetail);
                flush.set(null, originalFlush);
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

    private static void query(FFMBackend.Context context) {
        FFMBackend.queryHard(context, new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1), -1, true, 1);
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

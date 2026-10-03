package org.edtp.entitycollisionoptimizer.gametest;

import java.util.function.Supplier;

/** Test-only scope: preserve Level's query semantics, but traverse the original section storage. */
public final class VanillaEntityQueries {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private VanillaEntityQueries() {}

    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    public static <T> T call(Supplier<T> query) {
        boolean nested = active();
        ACTIVE.set(true);
        try {
            return query.get();
        } finally {
            if (nested) ACTIVE.set(true);
            else ACTIVE.remove();
        }
    }

    static void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }
}

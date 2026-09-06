package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

/** Test-only evidence that the enabled call really enters the owned implementation. */
public final class MovementTakeoverCoverage {
    private static volatile Thread observer;
    private static int calls;

    static void begin() { calls = 0; observer = Thread.currentThread(); }
    public static void entered() { if (observer == Thread.currentThread()) calls++; }
    static void end(GameTestHelper helper) {
        observer = null;
        helper.assertValueEqual(calls, 1, "enabled movement must enter the owned implementation once");
    }
}

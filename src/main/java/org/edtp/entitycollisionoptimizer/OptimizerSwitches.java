package org.edtp.entitycollisionoptimizer;

/**
 * Runtime switches for the three independent optimized subsystems.
 *
 * <p>Each switch falls back to the untouched vanilla path instead of degrading some intermediate
 * behaviour, so a single in-game session can bisect a behavioural difference by turning one
 * subsystem off at a time. The switches are diagnostic: they exist so a reported symptom can be
 * attributed to the native movement solver, the native push run or the native spatial index
 * without rebuilding the mod.
 */
public final class OptimizerSwitches {
    private static volatile boolean movement = true;
    private static volatile boolean push = true;
    private static volatile boolean index = true;

    /** Native clipping and step solve behind {@code Entity.move}/{@code Entity.collide}. */
    public static boolean movement() {
        return movement;
    }

    /** Native push run plus native candidate selection behind {@code LivingEntity.pushEntities}. */
    public static boolean push() {
        return push;
    }

    /** Native spatial index behind box queries and entity-collision queries. */
    public static boolean index() {
        return index;
    }

    public static void movement(boolean enabled) {
        movement = enabled;
    }

    public static void push(boolean enabled) {
        push = enabled;
    }

    public static void index(boolean enabled) {
        index = enabled;
    }

    public static void all(boolean enabled) {
        movement = enabled;
        push = enabled;
        index = enabled;
    }

    public static String describe() {
        return "movement=" + movement + " push=" + push + " index=" + index;
    }

    private OptimizerSwitches() {
    }
}

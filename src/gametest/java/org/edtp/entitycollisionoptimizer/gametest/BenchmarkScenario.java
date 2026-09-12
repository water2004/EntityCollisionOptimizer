package org.edtp.entitycollisionoptimizer.gametest;

/** Workload hooks; timing, exclusivity and cleanup belong to the shared runner. */
abstract class BenchmarkScenario {
    int durationTicks() { return CollisionBenchmarkRunner.DURATION_TICKS; }
    abstract String name();
    abstract String description();
    abstract void start();
    boolean ready() { return true; }
    abstract void tick(int tick);
    abstract void population(int tick);
    abstract void verify(int tick);
    int drainTicks() { return 0; }
    void drain(int tick) {}
    void verifyDrain(int tick) {}
    abstract String summary();
    abstract void cleanup();
}

package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Serial workload timing; normal parity runs never start benchmark scenarios. */
public final class CollisionBenchmarkRunner {
    static final int DURATION_TICKS = 200;
    private static BenchmarkRun activeRun;
    private static final ArrayDeque<BenchmarkRun> pending = new ArrayDeque<>();

    static {
        ServerTickEvents.START_SERVER_TICK.register(CollisionBenchmarkRunner::onTickStart);
        ServerTickEvents.END_SERVER_TICK.register(CollisionBenchmarkRunner::onTickEnd);
    }

    static void run(GameTestHelper helper, BenchmarkScenario scenario) {
        if (!Boolean.getBoolean("entity_collision_optimizer.runBenchmark")) {
            helper.succeed();
            return;
        }
        BenchmarkRun run = new BenchmarkRun(helper, scenario);
        pending.addLast(run);
        helper.onEachTick(() -> {
            if (!run.done) return;
            if (run.failure != null) helper.fail(run.failure);
            else helper.succeed();
        });
    }

    private static void onTickStart(MinecraftServer server) {
        if (activeRun == null && !pending.isEmpty()) {
            activeRun = pending.removeFirst();
            try { activeRun.start(); }
            catch (RuntimeException | Error failure) { activeRun.fail(failure); return; }
        }
        BenchmarkRun run = activeRun;
        if (run == null || run.helper.getLevel().getServer() != server) return;
        try {
            if (!run.chamber.ready()) return;
            if (run.tick == 0) run.logWindow("start");
            MovementScanDiagnostics.beginTick(run.optimized, true);
            run.tickStartedAt = System.nanoTime();
            if (run.tick < run.durationTicks) run.chamber.tick(run.tick);
            else run.chamber.drain(run.drainTick);
        } catch (RuntimeException | Error failure) {
            run.fail(failure);
        }
    }

    private static void onTickEnd(MinecraftServer server) {
        BenchmarkRun run = activeRun;
        if (run == null || run.helper.getLevel().getServer() != server || run.tickStartedAt == 0L) return;
        try {
            run.samples.add((System.nanoTime() - run.tickStartedAt) / 1_000_000.0);
            run.tickStartedAt = 0L;
            MovementScanDiagnostics.endTick();
            run.tick++;
            if (run.tick <= run.durationTicks && run.tick % 20 == 0) run.chamber.population(run.tick);
            if (run.tick == run.durationTicks) {
                run.logWindow("end");
                run.chamber.verify(run.tick);
                run.report();
                if (run.chamber.drainTicks() == 0) {
                    run.cleanup();
                    activeRun = null;
                    run.done = true;
                }
            } else if (run.tick > run.durationTicks) {
                run.drainTick++;
                if (run.drainTick >= run.chamber.drainTicks()) {
                    run.chamber.verifyDrain(run.tick);
                    run.cleanup();
                    activeRun = null;
                    run.done = true;
                }
            }
        } catch (RuntimeException | Error failure) {
            run.fail(failure);
        }
    }

    private static final class BenchmarkRun {
        private final GameTestHelper helper;
        private final BenchmarkScenario chamber;
        private final boolean originalCollisionSetting = CollisionOptimizerConfig.enableEntityCollision;
        private final boolean optimized;
        private final int durationTicks;
        private final List<Double> samples;
        private int tick;
        private int drainTick;
        private long tickStartedAt;
        private boolean done;
        private String failure;

        private BenchmarkRun(GameTestHelper helper, BenchmarkScenario scenario) {
            this.helper = helper;
            chamber = scenario;
            durationTicks = chamber.durationTicks();
            samples = new ArrayList<>(durationTicks);
            String mode = System.getenv().getOrDefault("ECO_BENCHMARK_PROFILE", "optimized");
            if (!mode.equalsIgnoreCase("optimized") && !mode.equalsIgnoreCase("baseline")) {
                throw new IllegalArgumentException("ECO_BENCHMARK_PROFILE must be optimized or baseline");
            }
            optimized = mode.equalsIgnoreCase("optimized");
        }

        private void start() {
            CollisionOptimizerConfig.enableEntityCollision = optimized;
            MovementScanDiagnostics.start();
            chamber.start();
            EntityCollisionOptimizer.LOGGER.info("ECO_BENCHMARK_START scenario={} ticks={} profile_mode={} {}",
                    chamber.name(), durationTicks, optimized ? "optimized" : "baseline", chamber.description());
        }

        private void logWindow(String phase) {
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_MEASUREMENT_WINDOW phase={} pid={} trial=0 optimized={} epoch_ms={}",
                    phase, ProcessHandle.current().pid(), optimized, System.currentTimeMillis());
        }

        private void report() {
            if (samples.size() != durationTicks) throw new IllegalStateException("Incomplete benchmark ticks");
            List<Double> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            double mean = samples.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double median = (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;
            double p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
            EntityCollisionOptimizer.LOGGER.info(String.format(Locale.ROOT,
                    "ECO_BENCHMARK_RESULT scenario=%s mode=%s ticks=%d mean_mspt=%.3f median_mspt=%.3f p95_mspt=%.3f %s",
                    chamber.name(), optimized ? "optimized" : "baseline", tick, mean, median, p95, chamber.summary()));
        }

        private void fail(Throwable failure) {
            cleanup();
            activeRun = null;
            this.failure = chamber.name() + " benchmark failed: " + failure;
            done = true;
        }

        private void cleanup() {
            MovementScanDiagnostics.finish();
            chamber.cleanup();
            CollisionFrame.end(helper.getLevel());
            CollisionOptimizerConfig.enableEntityCollision = originalCollisionSetting;
        }
    }
}

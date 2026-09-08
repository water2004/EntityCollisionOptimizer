package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Single spawning workload: no stationary population or unmeasured warmup. */
public final class ZombieCollisionBenchmark {
    static final int DURATION_TICKS = 200;
    private static BenchmarkRun activeRun;

    static {
        ServerTickEvents.START_SERVER_TICK.register(ZombieCollisionBenchmark::onTickStart);
        ServerTickEvents.END_SERVER_TICK.register(ZombieCollisionBenchmark::onTickEnd);
    }

    @GameTest(maxTicks = 240, padding = 96)
    public void fallingZombies(GameTestHelper helper) {
        if (!Boolean.getBoolean("entity_collision_optimizer.runBenchmark")) {
            helper.succeed();
            return;
        }
        if (activeRun != null) {
            helper.fail("A collision benchmark is already running");
            return;
        }
        BenchmarkRun run = new BenchmarkRun(helper);
        try {
            run.start();
            activeRun = run;
            helper.onEachTick(() -> {
                if (!run.done) return;
                if (run.failure != null) helper.fail(run.failure);
                else helper.succeed();
            });
        } catch (RuntimeException | Error failure) {
            run.cleanup();
            throw failure;
        }
    }

    private static void onTickStart(MinecraftServer server) {
        BenchmarkRun run = activeRun;
        if (run == null || run.helper.getLevel().getServer() != server) return;
        try {
            if (run.tick == 0) run.logWindow("start");
            MovementScanDiagnostics.beginTick(run.optimized, true);
            run.tickStartedAt = System.nanoTime();
            run.chamber.tickConnection();
            run.chamber.spawnWave();
            run.chamber.attackIfReady(run.tick);
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
            if (run.tick % 20 == 0) run.chamber.reportPopulation(run.tick);
            if (run.tick == DURATION_TICKS) {
                run.logWindow("end");
                run.chamber.verify(run.tick);
                run.report();
                run.cleanup();
                activeRun = null;
                run.done = true;
            }
        } catch (RuntimeException | Error failure) {
            run.fail(failure);
        }
    }

    private static final class BenchmarkRun {
        private final GameTestHelper helper;
        private final ZombieBenchmarkChamber chamber;
        private final boolean originalCollisionSetting = CollisionOptimizerConfig.enableEntityCollision;
        private final boolean optimized;
        private final List<Double> samples = new ArrayList<>(DURATION_TICKS);
        private int tick;
        private long tickStartedAt;
        private boolean done;
        private String failure;

        private BenchmarkRun(GameTestHelper helper) {
            this.helper = helper;
            chamber = new ZombieBenchmarkChamber(helper);
            String mode = System.getenv().getOrDefault("ECO_BENCHMARK_PROFILE", "optimized");
            if (!mode.equalsIgnoreCase("optimized") && !mode.equalsIgnoreCase("baseline")) {
                throw new IllegalArgumentException("ECO_BENCHMARK_PROFILE must be optimized or baseline");
            }
            optimized = mode.equalsIgnoreCase("optimized");
        }

        private void start() {
            CollisionOptimizerConfig.enableEntityCollision = optimized;
            MovementScanDiagnostics.start();
            chamber.build();
            chamber.spawnPlayer();
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_BENCHMARK_START scenario=falling_zombies ticks={} spawn_per_tick={} "
                            + "total_spawned={} drop_height={} chamber=3x3 spawn_pedestal=1x1 health=default players=1 "
                            + "player=invulnerable_survival weapon=diamond_sword knockback_level=2 "
                            + "attack_interval_ticks={} profile_mode={} warmup_ticks=0",
                    DURATION_TICKS, ZombieBenchmarkChamber.SPAWN_PER_TICK,
                    DURATION_TICKS * ZombieBenchmarkChamber.SPAWN_PER_TICK,
                    ZombieBenchmarkChamber.DROP_HEIGHT, ZombieBenchmarkChamber.ATTACK_INTERVAL_TICKS,
                    optimized ? "optimized" : "baseline");
        }

        private void logWindow(String phase) {
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_MEASUREMENT_WINDOW phase={} pid={} trial=0 optimized={} epoch_ms={}",
                    phase, ProcessHandle.current().pid(), optimized, System.currentTimeMillis());
        }

        private void report() {
            if (samples.size() != DURATION_TICKS) throw new IllegalStateException("Incomplete benchmark ticks");
            List<Double> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            double mean = samples.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double median = (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;
            double p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
            EntityCollisionOptimizer.LOGGER.info(String.format(Locale.ROOT,
                    "ECO_BENCHMARK_RESULT scenario=falling_zombies mode=%s ticks=%d spawned=%d "
                            + "mean_mspt=%.3f median_mspt=%.3f p95_mspt=%.3f "
                            + "attacks=%d accepted_attacks=%d observed_knockbacks=%d sweep_attacks=%d sweep_victims=%d",
                    optimized ? "optimized" : "baseline", tick, chamber.spawned,
                    mean, median, p95, chamber.attacks, chamber.acceptedAttacks, chamber.observedKnockbacks,
                    chamber.sweepAttacks, chamber.sweepVictims));
        }

        private void fail(Throwable failure) {
            cleanup();
            activeRun = null;
            this.failure = "Falling zombie benchmark failed: " + failure;
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

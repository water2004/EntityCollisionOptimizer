package org.edtp.entitycollisionoptimizer.gametest;

import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.gametest.mixin.RangedAttributeAccessor;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.monster.zombie.Zombie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Opt-in integration benchmark. Run with {@code ./gradlew runGameTest -Pbenchmark}.
 */
public final class ZombieCollisionBenchmark {
    private static final int LAYER_COUNT = 3;
    private static final int ENTITY_COUNT = LAYER_COUNT * ZombieBenchmarkLayer.ENTITY_COUNT;
    private static BenchmarkRun activeRun;

    static {
        ServerTickEvents.START_SERVER_TICK.register(ZombieCollisionBenchmark::onTickStart);
        ServerTickEvents.END_SERVER_TICK.register(ZombieCollisionBenchmark::onTickEnd);
    }

    // Keep neighbouring GameTest structures out of the large requested movement sweep.
    @GameTest(maxTicks = 1_200, padding = 96)
    public void stackedZombies(GameTestHelper helper) {
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
        } catch (RuntimeException | Error failure) {
            run.cleanup();
            throw failure;
        }
        activeRun = run;

        int expectedPairs = LAYER_COUNT * ZombieBenchmarkLayer.ENTITY_COUNT
                * (ZombieBenchmarkLayer.ENTITY_COUNT - 1) / 2;
        int detectedPairs = detectAllPairs(run.zombies);
        if (detectedPairs != expectedPairs) {
            run.cleanup();
            activeRun = null;
            helper.fail("Initial collision population returned " + detectedPairs
                    + " pairs; expected " + expectedPairs);
            return;
        }
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_BENCHMARK_START entities={} scenario=natural_enclosed_zombies "
                        + "layers={} players={} entities_per_layer={} vertical_stride={} "
                        + "chamber=1x2x1 health={} samples_per_mode={} verified_initial_pairs={} "
                        + "attacker=survival_player weapon=diamond_sword knockback_level=2 "
                        + "attack_interval_ticks={} profile_mode={}",
                ENTITY_COUNT,
                LAYER_COUNT,
                LAYER_COUNT,
                ZombieBenchmarkLayer.ENTITY_COUNT,
                ZombieBenchmarkLayer.VERTICAL_STRIDE,
                (long) ZombieBenchmarkLayer.BENCHMARK_HEALTH,
                run.samplesPerMode(),
                detectedPairs,
                ZombieBenchmarkLayer.ATTACK_INTERVAL_TICKS,
                run.profileMode
        );

        helper.onEachTick(() -> {
            BenchmarkRun current = activeRun;
            if (current == null || !current.done) {
                return;
            }

            current.cleanup();
            activeRun = null;
            helper.succeed();
        });
    }

    private static void onTickStart(MinecraftServer server) {
        BenchmarkRun run = activeRun;
        if (run == null || run.done) {
            return;
        }

        CollisionOptimizerConfig.enableEntityCollision = run.currentTrial().optimized;
        if (run.trialTick == 1 && run.profileOnly) {
            EntityCollisionOptimizer.LOGGER.info("ECO_PROFILE_WARMUP pid={} trial={} epoch_ms={}",
                    ProcessHandle.current().pid(), run.trialIndex, System.currentTimeMillis());
        }
        if (run.trialTick == run.currentTrial().warmupTicks) {
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_MEASUREMENT_WINDOW phase=start pid={} trial={} optimized={} epoch_ms={}",
                    ProcessHandle.current().pid(), run.trialIndex, run.currentTrial().optimized,
                    System.currentTimeMillis());
        }
        MovementScanDiagnostics.beginTick(run.currentTrial().optimized,
                run.trialTick >= run.currentTrial().warmupTicks);
        run.tickStartedAt = System.nanoTime();
        run.attackIfReady();
    }

    private static void onTickEnd(MinecraftServer server) {
        BenchmarkRun run = activeRun;
        if (run == null || run.done || run.tickStartedAt == 0L) {
            return;
        }

        Trial trial = run.currentTrial();
        double elapsedMs = (System.nanoTime() - run.tickStartedAt) / 1_000_000.0;
        MovementScanDiagnostics.endTick();
        if (run.trialTick >= trial.warmupTicks) {
            (trial.optimized ? run.optimizedSamples : run.baselineSamples).add(elapsedMs);
        }

        run.trialTick++;
        if (run.trialTick < trial.totalTicks()) {
            return;
        }

        EntityCollisionOptimizer.LOGGER.info(
                "ECO_MEASUREMENT_WINDOW phase=end pid={} trial={} optimized={} epoch_ms={}",
                ProcessHandle.current().pid(), run.trialIndex, trial.optimized, System.currentTimeMillis());

        run.verifyPopulation();
        List<Double> samples = trial.optimized ? run.optimizedSamples : run.baselineSamples;
        Stats trialStats = Stats.of(samples.subList(samples.size() - trial.measuredTicks, samples.size()));
        EntityCollisionOptimizer.LOGGER.info(String.format(Locale.ROOT,
                "ECO_BENCHMARK_TRIAL index=%d mode=%s mean_mspt=%.3f median_mspt=%.3f p95_mspt=%.3f",
                run.trialIndex, trial.optimized ? "optimized" : "baseline",
                trialStats.mean, trialStats.median, trialStats.p95));
        run.trialIndex++;
        if (run.trialIndex == run.trials.length) {
            run.finish();
            return;
        }

        run.trialTick = 0;
        run.replacePopulation();
    }

    private static int detectAllPairs(List<Zombie> zombies) {
        return detectDirectedEdges(zombies) / 2;
    }

    private static int detectDirectedEdges(List<Zombie> zombies) {
        Set<Zombie> expectedTargets = Collections.newSetFromMap(new IdentityHashMap<>());
        expectedTargets.addAll(zombies);
        CollisionFrame.begin((net.minecraft.server.level.ServerLevel) zombies.getFirst().level());
        try {
            int directedEdges = 0;
            for (Zombie zombie : zombies) {
                FFMBackend.QueryResult result = CollisionFrame.query(zombie);
                for (int index = 0; index < result.size(); index++) {
                    if (expectedTargets.contains(CollisionFrame.entity(zombie, result.get(index)))) {
                        directedEdges++;
                    }
                }
            }
            return directedEdges;
        } finally {
            CollisionFrame.end((net.minecraft.server.level.ServerLevel) zombies.getFirst().level());
        }
    }

    private record Trial(boolean optimized, int warmupTicks, int measuredTicks) {
        private int totalTicks() {
            return warmupTicks + measuredTicks;
        }
    }

    private static final class BenchmarkRun {
        private final GameTestHelper helper;
        private final List<Zombie> zombies = new ArrayList<>(ENTITY_COUNT);
        private final boolean originalCollisionSetting;
        private final RangedAttributeAccessor maxHealthAttribute;
        private final double originalMaxHealthLimit;
        private final boolean profileOnly;
        private final boolean profileOptimized;
        private final String profileMode;
        private final List<Double> baselineSamples = new ArrayList<>(400);
        private final List<Double> optimizedSamples = new ArrayList<>(400);
        private final Trial[] trials;

        private final List<ZombieBenchmarkLayer> layers = new ArrayList<>(LAYER_COUNT);
        private int trialIndex;
        private int trialTick;
        private long tickStartedAt;
        private boolean done;
        private boolean healthLimitRaised;

        private BenchmarkRun(GameTestHelper helper) {
            this.helper = helper;
            this.originalCollisionSetting = CollisionOptimizerConfig.enableEntityCollision;
            RangedAttribute maxHealth = (RangedAttribute) Attributes.MAX_HEALTH.value();
            this.maxHealthAttribute = (RangedAttributeAccessor) (Object) maxHealth;
            this.originalMaxHealthLimit = maxHealthAttribute.entityCollisionOptimizer$getMaxValue();
            this.profileMode = System.getenv().getOrDefault("ECO_BENCHMARK_PROFILE", "off");
            this.profileOnly = "optimized".equalsIgnoreCase(profileMode)
                    || "baseline".equalsIgnoreCase(profileMode);
            this.profileOptimized = "optimized".equalsIgnoreCase(profileMode);
            this.trials = profileOnly
                    ? new Trial[] {new Trial(profileOptimized, 120, 600)}
                    : new Trial[] {
                            new Trial(false, 80, 200),
                            new Trial(true, 80, 200),
                            new Trial(true, 80, 200),
                            new Trial(false, 80, 200)
                    };
        }

        private void start() {
            MovementScanDiagnostics.start();
            maxHealthAttribute.entityCollisionOptimizer$setMaxValue(ZombieBenchmarkLayer.BENCHMARK_HEALTH);
            healthLimitRaised = true;
            CollisionOptimizerConfig.enableEntityCollision = currentTrial().optimized;
            for (int index = 0; index < LAYER_COUNT; index++) {
                ZombieBenchmarkLayer layer = new ZombieBenchmarkLayer(helper, index);
                layers.add(layer);
                layer.buildChamber();
                layer.spawnPlayer();
            }
            spawnPopulation();
        }

        private Trial currentTrial() {
            return trials[trialIndex];
        }

        private int samplesPerMode() {
            return profileOnly ? 600 : 400;
        }

        private void replacePopulation() {
            discardPopulation();
            CollisionOptimizerConfig.enableEntityCollision = currentTrial().optimized;
            layers.forEach(ZombieBenchmarkLayer::resetPlayerForTrial);
            spawnPopulation();
        }

        private void attackIfReady() {
            layers.forEach(layer -> layer.attackIfReady(trialTick));
        }

        private void spawnPopulation() {
            for (ZombieBenchmarkLayer layer : layers) {
                layer.spawnPopulation();
                zombies.addAll(layer.zombies);
            }
            MovementScanDiagnostics.population(zombies);
        }

        private void verifyPopulation() {
            for (ZombieBenchmarkLayer layer : layers) {
                layer.verifyPopulation();
                EntityCollisionOptimizer.LOGGER.info(
                        "ECO_BENCHMARK_LAYER index={} entities={} attacks={} accepted_attacks={} observed_knockbacks={}",
                        layer.index, layer.zombies.size(), layer.trialAttacks,
                        layer.trialAcceptedAttacks, layer.trialObservedKnockbacks);
            }
        }

        private void discardPopulation() {
            layers.forEach(ZombieBenchmarkLayer::discardPopulation);
            zombies.clear();
        }

        private void finish() {
            MovementScanDiagnostics.finish();
            CollisionOptimizerConfig.enableEntityCollision = originalCollisionSetting;
            discardPopulation();
            restoreHealthLimit();

            if (profileOnly) {
                Stats profile = Stats.of(profileOptimized ? optimizedSamples : baselineSamples);
                EntityCollisionOptimizer.LOGGER.info(String.format(
                        Locale.ROOT,
                        "ECO_PROFILE_RESULT entities=%d mode=%s mean_mspt=%.3f "
                                + "median_mspt=%.3f p95_mspt=%.3f attacks=%d accepted_attacks=%d "
                                + "observed_knockbacks=%d",
                        ENTITY_COUNT,
                        profileOptimized ? "optimized" : "baseline",
                        profile.mean,
                        profile.median,
                        profile.p95,
                        layers.stream().mapToInt(layer -> layer.totalAttacks).sum(),
                        layers.stream().mapToInt(layer -> layer.totalAcceptedAttacks).sum(),
                        layers.stream().mapToInt(layer -> layer.totalObservedKnockbacks).sum()
                ));
                done = true;
                return;
            }

            Stats optimized = Stats.of(optimizedSamples);
            Stats baseline = Stats.of(baselineSamples);
            double saved = baseline.mean - optimized.mean;
            double improvement = saved / baseline.mean * 100.0;
            double speedup = baseline.mean / optimized.mean;

            EntityCollisionOptimizer.LOGGER.info(String.format(
                    Locale.ROOT,
                    "ECO_BENCHMARK_RESULT entities=%d baseline_mean_mspt=%.3f baseline_median_mspt=%.3f "
                            + "baseline_p95_mspt=%.3f optimized_mean_mspt=%.3f optimized_median_mspt=%.3f "
                            + "optimized_p95_mspt=%.3f saved_mspt=%.3f improvement_percent=%.2f speedup=%.2fx "
                            + "attacks=%d accepted_attacks=%d observed_knockbacks=%d",
                    ENTITY_COUNT,
                    baseline.mean,
                    baseline.median,
                    baseline.p95,
                    optimized.mean,
                    optimized.median,
                    optimized.p95,
                    saved,
                    improvement,
                    speedup,
                    layers.stream().mapToInt(layer -> layer.totalAttacks).sum(),
                    layers.stream().mapToInt(layer -> layer.totalAcceptedAttacks).sum(),
                    layers.stream().mapToInt(layer -> layer.totalObservedKnockbacks).sum()
            ));
            done = true;
        }

        private void cleanup() {
            CollisionOptimizerConfig.enableEntityCollision = originalCollisionSetting;
            discardPopulation();
            layers.forEach(ZombieBenchmarkLayer::cleanup);
            restoreHealthLimit();
        }

        private void restoreHealthLimit() {
            if (!healthLimitRaised) {
                return;
            }
            maxHealthAttribute.entityCollisionOptimizer$setMaxValue(originalMaxHealthLimit);
            healthLimitRaised = false;
        }
    }

    private record Stats(double mean, double median, double p95) {
        private static Stats of(List<Double> samples) {
            if (samples.isEmpty()) {
                throw new IllegalStateException("No benchmark samples were recorded");
            }

            List<Double> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            double sum = 0.0;
            for (double sample : sorted) {
                sum += sample;
            }
            int medianIndex = sorted.size() / 2;
            double median = sorted.size() % 2 == 0
                    ? (sorted.get(medianIndex - 1) + sorted.get(medianIndex)) / 2.0
                    : sorted.get(medianIndex);
            int p95Index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1);
            return new Stats(sum / sorted.size(), median, sorted.get(p95Index));
        }
    }
}

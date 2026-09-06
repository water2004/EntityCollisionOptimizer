package com.wiyuka.acceleratedrecoiling.gametest;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.CollisionMapData;
import com.wiyuka.acceleratedrecoiling.natives.FFMBackend;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in integration benchmark. Run with {@code ./gradlew runGameTest -Pbenchmark}.
 */
public final class ZombieCollisionBenchmark {
    private static final int ENTITY_COUNT = 1_000;
    private static BenchmarkRun activeRun;

    static {
        ServerTickEvents.START_SERVER_TICK.register(ZombieCollisionBenchmark::onTickStart);
        ServerTickEvents.END_SERVER_TICK.register(ZombieCollisionBenchmark::onTickEnd);
    }

    @GameTest(maxTicks = 1_200, padding = 2)
    public void stackedZombies(GameTestHelper helper) {
        if (activeRun != null) {
            helper.fail("A collision benchmark is already running");
            return;
        }

        Vec3 anchor = helper.absoluteVec(new Vec3(0.5, 1.0, 0.5));
        List<Zombie> zombies = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new Vec3(0.5, 1.0, 0.5));
            zombie.setNoGravity(true);
            zombie.setInvulnerable(true);
            zombie.setSilent(true);
            zombie.setPersistenceRequired();
            zombies.add(zombie);
        }

        activeRun = new BenchmarkRun(zombies, anchor);
        FoldConfig.maxCollision = 0;
        FFMBackend.applyConfig();
        int expectedPairs = ENTITY_COUNT * (ENTITY_COUNT - 1) / 2;
        int detectedPairs = detectAllPairs(zombies);
        if (detectedPairs != expectedPairs) {
            helper.fail("Unlimited collision mode returned " + detectedPairs
                    + " pairs; expected " + expectedPairs);
            return;
        }
        AcceleratedRecoiling.LOGGER.info(
                "AR_BENCHMARK_START entities={} scenario=stacked_zombies samples_per_mode=400 "
                        + "max_collision=0(unlimited) verified_pairs={} profile_mode={}",
                ENTITY_COUNT,
                detectedPairs,
                activeRun.profileMode
        );

        helper.onEachTick(() -> {
            BenchmarkRun run = activeRun;
            if (run == null || !run.done) {
                return;
            }

            for (Zombie zombie : zombies) {
                zombie.discard();
            }
            activeRun = null;
            helper.succeed();
        });
    }

    private static void onTickStart(MinecraftServer server) {
        BenchmarkRun run = activeRun;
        if (run == null || run.done) {
            return;
        }

        Phase phase = run.phases[run.phaseIndex];
        FoldConfig.enableEntityCollision = phase.optimized;
        if (phase.optimized && !run.directedEdgesVerified) {
            CollisionMapData.resetLastNonEmptyDirectedEdgeCount();
        }
        for (Zombie zombie : run.zombies) {
            if (!zombie.isRemoved()) {
                zombie.setPos(run.anchor);
                zombie.setDeltaMovement(Vec3.ZERO);
                zombie.setRemainingFireTicks(0);
            }
        }
        run.tickStartedAt = System.nanoTime();
    }

    private static int detectAllPairs(List<Zombie> zombies) {
        double[] boxes = new double[zombies.size() * 6];
        for (int i = 0; i < zombies.size(); i++) {
            Zombie zombie = zombies.get(i);
            AABB box = zombie.getBoundingBox().inflate(1.0E-7);
            int boxOffset = i * 6;
            boxes[boxOffset] = box.minX;
            boxes[boxOffset + 1] = box.minY;
            boxes[boxOffset + 2] = box.minZ;
            boxes[boxOffset + 3] = box.maxX;
            boxes[boxOffset + 4] = box.maxY;
            boxes[boxOffset + 5] = box.maxZ;
        }
        return FFMBackend.push(boxes, zombies.size()).size();
    }

    private static void onTickEnd(MinecraftServer server) {
        BenchmarkRun run = activeRun;
        if (run == null || run.done || run.tickStartedAt == 0L) {
            return;
        }

        Phase phase = run.phases[run.phaseIndex];
        double elapsedMs = (System.nanoTime() - run.tickStartedAt) / 1_000_000.0;
        if (phase.optimized && !run.directedEdgesVerified) {
            int expectedDirectedEdges = ENTITY_COUNT * (ENTITY_COUNT - 1);
            int actualDirectedEdges = CollisionMapData.lastNonEmptyDirectedEdgeCount();
            if (actualDirectedEdges != expectedDirectedEdges) {
                throw new IllegalStateException("Optimized collision map contained "
                        + actualDirectedEdges + " directed edges; expected " + expectedDirectedEdges);
            }
            run.directedEdgesVerified = true;
            AcceleratedRecoiling.LOGGER.info(
                    "AR_BENCHMARK_COLLISION_MAP verified_directed_edges={}",
                    actualDirectedEdges
            );
        }
        if (phase.measured) {
            (phase.optimized ? run.optimizedSamples : run.baselineSamples).add(elapsedMs);
        }

        run.phaseTick++;
        if (run.phaseTick < phase.ticks) {
            return;
        }

        run.phaseTick = 0;
        run.phaseIndex++;
        if (run.phaseIndex == run.phases.length) {
            run.finish();
        }
    }

    private record Phase(boolean optimized, boolean measured, int ticks) {
    }

    private static final class BenchmarkRun {
        private final List<Zombie> zombies;
        private final Vec3 anchor;
        private final boolean originalCollisionSetting;
        private final int originalMaxCollision;
        private final boolean profileOnly;
        private final boolean profileOptimized;
        private final String profileMode;
        private final List<Double> baselineSamples = new ArrayList<>(400);
        private final List<Double> optimizedSamples = new ArrayList<>(400);
        private final Phase[] phases;

        private int phaseIndex;
        private int phaseTick;
        private long tickStartedAt;
        private boolean done;
        private boolean directedEdgesVerified;

        private BenchmarkRun(List<Zombie> zombies, Vec3 anchor) {
            this.zombies = zombies;
            this.anchor = anchor;
            this.originalCollisionSetting = FoldConfig.enableEntityCollision;
            this.originalMaxCollision = FoldConfig.maxCollision;
            this.profileMode = System.getenv().getOrDefault("AR_BENCHMARK_PROFILE", "off");
            this.profileOnly = "optimized".equalsIgnoreCase(profileMode)
                    || "baseline".equalsIgnoreCase(profileMode);
            this.profileOptimized = "optimized".equalsIgnoreCase(profileMode);
            this.phases = profileOnly
                    ? new Phase[] {
                            new Phase(profileOptimized, false, 120),
                            new Phase(profileOptimized, true, 600)
                    }
                    : new Phase[] {
                            new Phase(false, false, 80),
                            new Phase(false, true, 200),
                            new Phase(true, false, 80),
                            new Phase(true, true, 200),
                            new Phase(true, false, 40),
                            new Phase(true, true, 200),
                            new Phase(false, false, 80),
                            new Phase(false, true, 200)
                    };
        }

        private void finish() {
            FoldConfig.enableEntityCollision = originalCollisionSetting;
            FoldConfig.maxCollision = originalMaxCollision;
            FFMBackend.applyConfig();

            if (profileOnly) {
                Stats profile = Stats.of(profileOptimized ? optimizedSamples : baselineSamples);
                AcceleratedRecoiling.LOGGER.info(String.format(
                        Locale.ROOT,
                        "AR_PROFILE_RESULT entities=%d mode=%s mean_mspt=%.3f "
                                + "median_mspt=%.3f p95_mspt=%.3f",
                        ENTITY_COUNT,
                        profileOptimized ? "optimized" : "baseline",
                        profile.mean,
                        profile.median,
                        profile.p95
                ));
                done = true;
                return;
            }

            Stats optimized = Stats.of(optimizedSamples);
            Stats baseline = Stats.of(baselineSamples);
            double saved = baseline.mean - optimized.mean;
            double improvement = saved / baseline.mean * 100.0;
            double speedup = baseline.mean / optimized.mean;

            AcceleratedRecoiling.LOGGER.info(String.format(
                    Locale.ROOT,
                    "AR_BENCHMARK_RESULT entities=%d baseline_mean_mspt=%.3f baseline_median_mspt=%.3f "
                            + "baseline_p95_mspt=%.3f optimized_mean_mspt=%.3f optimized_median_mspt=%.3f "
                            + "optimized_p95_mspt=%.3f saved_mspt=%.3f improvement_percent=%.2f speedup=%.2fx",
                    ENTITY_COUNT,
                    baseline.mean,
                    baseline.median,
                    baseline.p95,
                    optimized.mean,
                    optimized.median,
                    optimized.p95,
                    saved,
                    improvement,
                    speedup
            ));
            done = true;
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

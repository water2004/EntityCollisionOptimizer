package org.edtp.entitycollisionoptimizer.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.gametest.mixin.RangedAttributeAccessor;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in integration benchmark. Run with {@code ./gradlew runGameTest -Pbenchmark}.
 */
public final class ZombieCollisionBenchmark {
    private static final int ENTITY_COUNT = 1_000;
    private static final float BENCHMARK_HEALTH = 100_000_000.0F;
    private static final int ATTACK_INTERVAL_TICKS = 13;
    private static final int CHAMBER_MIN = 1;
    private static final int CHAMBER_MAX = 3;
    private static final int CHAMBER_FLOOR_Y = 0;
    private static final int CHAMBER_CEILING_Y = 3;
    private static final Vec3 CHAMBER_CENTER = new Vec3(2.5, 1.0, 2.5);
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

        buildStoneChamber(helper);
        BenchmarkRun run = new BenchmarkRun(helper);
        try {
            run.start();
        } catch (RuntimeException | Error failure) {
            run.cleanup();
            throw failure;
        }
        activeRun = run;

        int expectedPairs = ENTITY_COUNT * (ENTITY_COUNT - 1) / 2;
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
                        + "chamber=1x2x1 health={} samples_per_mode={} verified_initial_pairs={} "
                        + "attacker=survival_player weapon=diamond_sword knockback_level=2 "
                        + "attack_interval_ticks={} profile_mode={}",
                ENTITY_COUNT,
                (long) BENCHMARK_HEALTH,
                run.samplesPerMode(),
                detectedPairs,
                ATTACK_INTERVAL_TICKS,
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

    private static void buildStoneChamber(GameTestHelper helper) {
        for (int y = CHAMBER_FLOOR_Y; y <= CHAMBER_CEILING_Y; y++) {
            for (int x = CHAMBER_MIN; x <= CHAMBER_MAX; x++) {
                for (int z = CHAMBER_MIN; z <= CHAMBER_MAX; z++) {
                    boolean boundary = y == CHAMBER_FLOOR_Y
                            || y == CHAMBER_CEILING_Y
                            || x == CHAMBER_MIN
                            || x == CHAMBER_MAX
                            || z == CHAMBER_MIN
                            || z == CHAMBER_MAX;
                    helper.setBlock(new BlockPos(x, y, z), boundary ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
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

        private ServerPlayer player;
        private EmbeddedChannel playerChannel;
        private int trialIndex;
        private int trialTick;
        private int trialAttacks;
        private int trialAcceptedAttacks;
        private int trialObservedKnockbacks;
        private int totalAttacks;
        private int totalAcceptedAttacks;
        private int totalObservedKnockbacks;
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
            maxHealthAttribute.entityCollisionOptimizer$setMaxValue(BENCHMARK_HEALTH);
            healthLimitRaised = true;
            CollisionOptimizerConfig.enableEntityCollision = currentTrial().optimized;
            spawnPlayer();
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
            resetPlayerForTrial();
            spawnPopulation();
        }

        private void spawnPlayer() {
            GameProfile profile = new GameProfile(UUID.randomUUID(), "eco-benchmark-player");
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(
                    helper.getLevel().getServer(),
                    helper.getLevel(),
                    profile,
                    ClientInformation.createDefault()
            );
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            playerChannel = new EmbeddedChannel(connection);
            helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(GameType.SURVIVAL);
            if (player.gameMode() != GameType.SURVIVAL) {
                throw new IllegalStateException("Failed to create a survival benchmark player");
            }
            AttributeInstance maxHealth = Objects.requireNonNull(
                    player.getAttribute(Attributes.MAX_HEALTH),
                    "Player max-health attribute"
            );
            maxHealth.setBaseValue(BENCHMARK_HEALTH);

            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            var knockback = helper.getLevel()
                    .registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.KNOCKBACK);
            sword.enchant(knockback, 2);
            if (sword.getEnchantments().getLevel(knockback) != 2) {
                throw new IllegalStateException("Failed to equip Knockback II for the benchmark player");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, sword);
            resetPlayerForTrial();
        }

        private void resetPlayerForTrial() {
            player.setHealth(BENCHMARK_HEALTH);
            player.setPos(helper.absoluteVec(CHAMBER_CENTER));
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetAttackStrengthTicker();
            trialAttacks = 0;
            trialAcceptedAttacks = 0;
            trialObservedKnockbacks = 0;
        }

        private void attackIfReady() {
            if (trialTick == 0 || trialTick % ATTACK_INTERVAL_TICKS != 0) {
                return;
            }

            Zombie target = zombies.get(trialAttacks % zombies.size());
            Vec3 movementBefore = target.getDeltaMovement();
            player.attack(target);
            Vec3 movementAfter = target.getDeltaMovement();

            trialAttacks++;
            totalAttacks++;
            if (target.getLastHurtByPlayer() == player) {
                trialAcceptedAttacks++;
                totalAcceptedAttacks++;
            }
            double horizontalChange = Math.abs(movementAfter.x - movementBefore.x)
                    + Math.abs(movementAfter.z - movementBefore.z);
            if (horizontalChange > 1.0E-9) {
                trialObservedKnockbacks++;
                totalObservedKnockbacks++;
            }
        }

        private void spawnPopulation() {
            for (int index = 0; index < ENTITY_COUNT; index++) {
                Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, CHAMBER_CENTER);
                AttributeInstance maxHealth = Objects.requireNonNull(
                        zombie.getAttribute(Attributes.MAX_HEALTH),
                        "Zombie max-health attribute"
                );
                maxHealth.setBaseValue(BENCHMARK_HEALTH);
                zombie.setHealth(BENCHMARK_HEALTH);
                zombie.setPersistenceRequired();
                zombies.add(zombie);
            }
            MovementScanDiagnostics.population(zombies);
        }

        private void verifyPopulation() {
            long alive = zombies.stream().filter(zombie -> !zombie.isRemoved() && zombie.isAlive()).count();
            if (alive != ENTITY_COUNT) {
                throw new IllegalStateException(
                        "Benchmark population changed: " + alive + " of " + ENTITY_COUNT + " zombies remain"
                );
            }
            if (player == null || player.isRemoved() || !player.isAlive()) {
                throw new IllegalStateException("Benchmark player did not survive the trial");
            }
            if (trialAttacks == 0 || trialAcceptedAttacks == 0) {
                throw new IllegalStateException(
                        "None of the " + trialAttacks + " player attacks were accepted by the vanilla damage path"
                );
            }
            if (trialObservedKnockbacks != trialAcceptedAttacks) {
                throw new IllegalStateException(
                        trialObservedKnockbacks + " of " + trialAcceptedAttacks
                                + " accepted Knockback II attacks changed horizontal velocity"
                );
            }
        }

        private void discardPopulation() {
            for (Zombie zombie : zombies) {
                zombie.discard();
            }
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
                        totalAttacks,
                        totalAcceptedAttacks,
                        totalObservedKnockbacks
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
                    totalAttacks,
                    totalAcceptedAttacks,
                    totalObservedKnockbacks
            ));
            done = true;
        }

        private void cleanup() {
            CollisionOptimizerConfig.enableEntityCollision = originalCollisionSetting;
            discardPopulation();
            if (player != null) {
                helper.getLevel().getServer().getPlayerList().remove(player);
                player = null;
            }
            if (playerChannel != null) {
                playerChannel.close();
                playerChannel = null;
            }
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

package org.edtp.entitycollisionoptimizer.integration;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Cross-process parity fixture shared by the vanilla-only and optimized GameTest runs.
 * The vanilla process records the trace; the optimized process must reproduce every byte.
 */
public final class ZombieCrammingIntegrationGameTests {
    private static final int TRACE_MAGIC = 0x45434F5A;
    private static final int TRACE_VERSION = 1;
    private static final int ENTITY_COUNT = 320;
    private static final int TICKS = 200;
    private static final int MAX_ENTITY_CRAMMING = 24;
    private static final int CHAMBER_SIZE = 3;
    private static final int CHAMBER_HEIGHT = 4;
    private static final double SPAWN_JITTER = 0.36;
    private static final long LEVEL_SEED = 0x5EED_EC02_62L;
    private static final long ENTITY_SEED_STEP = 0x9E37_79B9_7F4A_7C15L;
    private static final long SPAWN_SEED = 0xEC020026L;
    private static final Vec3 SCENE_ORIGIN = new Vec3(1025.0, 80.0, 1025.0);

    @GameTest(maxTicks = 400, padding = 48)
    public void crowdedChamberMatchesVanilla(GameTestHelper helper) {
        ScenarioRun run = new ScenarioRun(helper);
        run.start();
        helper.onEachTick(run::captureTick);
    }

    private static final class ScenarioRun {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final int previousCramming;
        private final List<Zombie> zombies = new ArrayList<>(ENTITY_COUNT);
        private final IntegrationArena arena;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024 * 1024);
        private final DataOutputStream output = new DataOutputStream(bytes);
        private int tick;
        private boolean finished;
        private boolean cleanedUp;

        private ScenarioRun(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            previousCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
            arena = new IntegrationArena(level);
        }

        private void start() {
            try {
                level.getGameRules().set(
                        GameRules.MAX_ENTITY_CRAMMING,
                        MAX_ENTITY_CRAMMING,
                        level.getServer()
                );
                level.getRandom().setSeed(LEVEL_SEED);
                arena.buildStoneRoom(SCENE_ORIGIN, CHAMBER_SIZE, CHAMBER_HEIGHT, CHAMBER_SIZE);

                Random spawnRandom = new Random(SPAWN_SEED);
                for (int index = 0; index < ENTITY_COUNT; index++) {
                    Zombie zombie = arena.track(helper.spawn(EntityTypes.ZOMBIE, new Vec3(1.5, 2.0, 1.5)));
                    ZombieTrace.normalize(
                            zombie,
                            spawnPosition(spawnRandom),
                            LEVEL_SEED + ENTITY_SEED_STEP * index
                    );
                    zombies.add(zombie);
                }
                level.getRandom().setSeed(LEVEL_SEED);

                output.writeInt(TRACE_MAGIC);
                output.writeInt(TRACE_VERSION);
                output.writeInt(ENTITY_COUNT);
                output.writeInt(TICKS);
                ZombieTrace.writeFrame(output, zombies, SCENE_ORIGIN, 0);
            } catch (IOException failure) {
                cleanup();
                throw new IllegalStateException("Cannot initialize cramming parity trace", failure);
            } catch (RuntimeException | Error failure) {
                cleanup();
                throw failure;
            }
        }

        private void captureTick() {
            if (finished) return;
            try {
                tick++;
                ZombieTrace.writeFrame(output, zombies, SCENE_ORIGIN, tick);
                if (tick == TICKS) finish();
            } catch (IOException failure) {
                cleanup();
                finished = true;
                throw new IllegalStateException("Cannot encode cramming parity trace", failure);
            } catch (RuntimeException | Error failure) {
                cleanup();
                finished = true;
                throw failure;
            }
        }

        private void finish() throws IOException {
            for (Zombie zombie : zombies) {
                output.writeLong(zombie.getRandom().nextLong());
            }
            output.close();
            byte[] actual = bytes.toByteArray();
            try {
                CrossProcessTrace.verify(helper, "zombie-cramming", actual);
            } finally {
                cleanup();
                finished = true;
            }
            helper.succeed();
        }

        private void cleanup() {
            if (cleanedUp) return;
            cleanedUp = true;
            arena.close();
            level.getGameRules().set(
                    GameRules.MAX_ENTITY_CRAMMING,
                    previousCramming,
                    level.getServer()
            );
        }
    }

    private static Vec3 spawnPosition(Random random) {
        Vec3 center = SCENE_ORIGIN.add(CHAMBER_SIZE / 2.0, 0.0, CHAMBER_SIZE / 2.0);
        return center.add(
                (random.nextDouble() - 0.5) * SPAWN_JITTER,
                0.0,
                (random.nextDouble() - 0.5) * SPAWN_JITTER
        );
    }
}

package org.edtp.entitycollisionoptimizer.integration;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Real TNT explosion in a deterministic zombie crowd, compared across separate processes. */
public final class TntCrowdIntegrationGameTests {
    private static final int TRACE_MAGIC = 0x45434F54;
    private static final int TRACE_VERSION = 1;
    private static final int ENTITY_COUNT = 96;
    private static final int TICKS = 100;
    private static final int ROOM_SIZE = 9;
    private static final int ROOM_HEIGHT = 4;
    private static final int TNT_FUSE = 20;
    private static final int POSITION_SCALE = 4096;
    private static final long LEVEL_SEED = 0x5EED_EC02_7AL;
    private static final long ENTITY_SEED_STEP = 0x9E37_79B9_7F4A_7C15L;
    private static final long SPAWN_SEED = 0xEC02007AL;
    private static final int ENTITY_ID_BASE = 2_000_000;
    private static final long DAY_TIME = 18_000L;
    private static final long GAME_TIME = 1_000L;
    private static final Vec3 SCENE_ORIGIN = new Vec3(-5_909_900.0, -57.0, -9_908_000.0);

    @GameTest(
            maxTicks = 1000,
            padding = 48,
            environment = "entity_collision_optimizer:integration"
    )
    public void tntExplosionInZombieCrowdMatchesVanilla(GameTestHelper helper) {
        ScenarioRun run = new ScenarioRun(helper);
        helper.onEachTick(run::captureTick);
    }

    private static final class ScenarioRun {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final Vec3 sceneOrigin;
        private final IntegrationArena arena;
        private final long previousDayTime;
        private final long previousGameTime;
        private final List<Zombie> zombies = new ArrayList<>(ENTITY_COUNT);
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(3 * 1024 * 1024);
        private final DataOutputStream output = new DataOutputStream(bytes);
        private PrimedTnt tnt;
        private int tick;
        private boolean prepared;
        private boolean started;
        private boolean finished;
        private boolean cleanedUp;

        private ScenarioRun(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            sceneOrigin = SCENE_ORIGIN;
            arena = new IntegrationArena(level, ENTITY_ID_BASE, LEVEL_SEED);
            previousDayTime = arena.defaultClockTime();
            previousGameTime = arena.gameTime();
        }

        private void start() {
            try {
                arena.buildStoneRoom(sceneOrigin, ROOM_SIZE, ROOM_HEIGHT, ROOM_SIZE);
                prepared = true;
            } catch (RuntimeException | Error failure) {
                cleanup();
                throw failure;
            }
        }

        private void captureTick() {
            if (finished) return;
            if (!IntegrationSequence.isCrammingComplete()) return;
            try {
                if (!prepared) {
                    start();
                    return;
                }
                if (!started) {
                    if (!arena.isReadyForEntityTicks()
                            || !arena.isDarkRoom(sceneOrigin, ROOM_SIZE, ROOM_HEIGHT, ROOM_SIZE)) return;
                    initializeTrace();
                    started = true;
                    return;
                }
                tick++;
                writeFrame(tick);
                if (tick == TICKS) finish();
            } catch (IOException failure) {
                cleanup();
                finished = true;
                throw new IllegalStateException("Cannot encode TNT crowd trace", failure);
            } catch (RuntimeException | Error failure) {
                cleanup();
                finished = true;
                throw failure;
            }
        }

        private void initializeTrace() throws IOException {
            arena.setDefaultClockTime(DAY_TIME);
            arena.setGameTime(GAME_TIME);
            level.getRandom().setSeed(LEVEL_SEED);
            Random spawnRandom = new Random(SPAWN_SEED);
            for (int index = 0; index < ENTITY_COUNT; index++) {
                Zombie zombie = arena.spawn(EntityTypes.ZOMBIE, spawnPosition(sceneOrigin, spawnRandom));
                ZombieTrace.normalize(
                        zombie,
                        LEVEL_SEED + ENTITY_SEED_STEP * index
                );
                zombies.add(zombie);
            }

            tnt = arena.spawn(EntityTypes.TNT, roomCenter(sceneOrigin));
            tnt.setOldPosAndRot();
            tnt.setDeltaMovement(Vec3.ZERO);
            tnt.setOnGround(true);
            tnt.setFuse(TNT_FUSE);
            tnt.tickCount = 0;
            tnt.getRandom().setSeed(LEVEL_SEED ^ 0x544E54L);
            level.getRandom().setSeed(LEVEL_SEED);

            output.writeInt(TRACE_MAGIC);
            output.writeInt(TRACE_VERSION);
            output.writeInt(ENTITY_COUNT);
            output.writeInt(TICKS);
            writeFrame(0);
        }

        private void writeFrame(int frameTick) throws IOException {
            output.writeInt(frameTick);
            writeTntState(output, tnt, sceneOrigin);
            for (int index = 0; index < zombies.size(); index++) {
                ZombieTrace.writeState(output, zombies.get(index), sceneOrigin, index);
            }
        }

        private void finish() throws IOException {
            output.writeLong(tnt.getRandom().nextLong());
            for (Zombie zombie : zombies) {
                output.writeLong(zombie.getRandom().nextLong());
            }
            output.close();
            byte[] actual = bytes.toByteArray();
            try {
                CrossProcessTrace.verify(helper, "tnt-zombie-crowd", actual);
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
            arena.setDefaultClockTime(previousDayTime);
            arena.setGameTime(previousGameTime);
        }
    }

    private static Vec3 roomCenter(Vec3 sceneOrigin) {
        return sceneOrigin.add(ROOM_SIZE / 2.0, 0.0, ROOM_SIZE / 2.0);
    }

    private static Vec3 spawnPosition(Vec3 sceneOrigin, Random random) {
        int margin = POSITION_SCALE / 2;
        int usable = (ROOM_SIZE - 1) * POSITION_SCALE;
        return sceneOrigin.add(
                (margin + random.nextInt(usable + 1)) / (double) POSITION_SCALE,
                0.0,
                (margin + random.nextInt(usable + 1)) / (double) POSITION_SCALE
        );
    }

    private static void writeTntState(DataOutputStream output, PrimedTnt tnt, Vec3 origin) throws IOException {
        output.writeInt(tnt.getFuse());
        writeVec(output, tnt.position().subtract(origin));
        writeVec(output, tnt.getDeltaMovement());
        AABB bounds = tnt.getBoundingBox();
        ZombieTrace.writeDouble(output, bounds.minX - origin.x);
        ZombieTrace.writeDouble(output, bounds.minY - origin.y);
        ZombieTrace.writeDouble(output, bounds.minZ - origin.z);
        ZombieTrace.writeDouble(output, bounds.maxX - origin.x);
        ZombieTrace.writeDouble(output, bounds.maxY - origin.y);
        ZombieTrace.writeDouble(output, bounds.maxZ - origin.z);
        output.writeInt(tnt.tickCount);
        output.writeBoolean(tnt.isRemoved());
        output.writeBoolean(tnt.onGround());
    }

    private static void writeVec(DataOutputStream output, Vec3 value) throws IOException {
        ZombieTrace.writeDouble(output, value.x);
        ZombieTrace.writeDouble(output, value.y);
        ZombieTrace.writeDouble(output, value.z);
    }
}

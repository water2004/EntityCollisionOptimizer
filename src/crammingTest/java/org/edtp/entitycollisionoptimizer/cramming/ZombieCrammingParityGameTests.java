package org.edtp.entitycollisionoptimizer.cramming;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Cross-process parity fixture shared by the vanilla-only and optimized GameTest runs.
 * The vanilla process records the trace; the optimized process must reproduce every byte.
 */
public final class ZombieCrammingParityGameTests {
    private static final String MODE_PROPERTY = "entity_collision_optimizer.cramming.mode";
    private static final String TRACE_PROPERTY = "entity_collision_optimizer.cramming.trace";
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
    public void realZombieCrammingParity(GameTestHelper helper) {
        ScenarioRun run = new ScenarioRun(helper);
        run.start();
        helper.onEachTick(run::captureTick);
    }

    private static final class ScenarioRun {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final int previousCramming;
        private final List<Zombie> zombies = new ArrayList<>(ENTITY_COUNT);
        private final Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024 * 1024);
        private final DataOutputStream output = new DataOutputStream(bytes);
        private int tick;
        private boolean finished;
        private boolean cleanedUp;

        private ScenarioRun(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            previousCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        }

        private void start() {
            try {
                level.getGameRules().set(
                        GameRules.MAX_ENTITY_CRAMMING,
                        MAX_ENTITY_CRAMMING,
                        level.getServer()
                );
                level.getRandom().setSeed(LEVEL_SEED);
                level.getChunkAt(BlockPos.containing(SCENE_ORIGIN));
                buildChamber(level, originalBlocks);

                Random spawnRandom = new Random(SPAWN_SEED);
                for (int index = 0; index < ENTITY_COUNT; index++) {
                    Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new Vec3(1.5, 2.0, 1.5));
                    normalize(zombie, spawnPosition(spawnRandom), index);
                    zombies.add(zombie);
                }
                level.getRandom().setSeed(LEVEL_SEED);

                output.writeInt(TRACE_MAGIC);
                output.writeInt(TRACE_VERSION);
                output.writeInt(ENTITY_COUNT);
                output.writeInt(TICKS);
                writeFrame(output, zombies, SCENE_ORIGIN, 0);
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
                writeFrame(output, zombies, SCENE_ORIGIN, tick);
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
            String mode = System.getProperty(MODE_PROPERTY);
            Path tracePath = Path.of(System.getProperty(TRACE_PROPERTY));

            try {
                if ("record".equals(mode)) {
                    Files.createDirectories(tracePath.getParent());
                    Files.write(tracePath, actual);
                } else if ("compare".equals(mode)) {
                    byte[] expected = Files.readAllBytes(tracePath);
                    helper.assertTrue(
                            Arrays.equals(actual, expected),
                            mismatchMessage(expected, actual)
                    );
                } else {
                    throw new IllegalStateException("Unknown cramming trace mode: " + mode);
                }
            } finally {
                cleanup();
                finished = true;
            }
            helper.succeed();
        }

        private void cleanup() {
            if (cleanedUp) return;
            cleanedUp = true;
            for (Zombie zombie : zombies) {
                zombie.discard();
            }
            restoreChamber(level, originalBlocks);
            level.getGameRules().set(
                    GameRules.MAX_ENTITY_CRAMMING,
                    previousCramming,
                    level.getServer()
            );
        }
    }

    private static void buildChamber(
            ServerLevel level,
            Map<BlockPos, BlockState> originalBlocks
    ) {
        BlockPos origin = BlockPos.containing(SCENE_ORIGIN);
        for (int y = -1; y <= CHAMBER_HEIGHT; y++) {
            for (int x = -1; x <= CHAMBER_SIZE; x++) {
                for (int z = -1; z <= CHAMBER_SIZE; z++) {
                    BlockPos position = origin.offset(x, y, z);
                    originalBlocks.put(position, level.getBlockState(position));
                    boolean boundary = y == -1 || y == CHAMBER_HEIGHT
                            || x == -1 || x == CHAMBER_SIZE
                            || z == -1 || z == CHAMBER_SIZE;
                    level.setBlockAndUpdate(position, boundary
                            ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void restoreChamber(
            ServerLevel level,
            Map<BlockPos, BlockState> originalBlocks
    ) {
        for (Map.Entry<BlockPos, BlockState> entry : originalBlocks.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
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

    private static void normalize(Zombie zombie, Vec3 position, int index) {
        zombie.setPos(position);
        zombie.setOldPosAndRot();
        zombie.setDeltaMovement(Vec3.ZERO);
        zombie.setOnGround(true);
        zombie.setNoAi(false);
        zombie.setNoGravity(false);
        zombie.setSilent(false);
        zombie.setInvulnerable(false);
        zombie.setPersistenceRequired();
        zombie.setCanPickUpLoot(false);
        zombie.setBaby(false);
        zombie.setHealth(zombie.getMaxHealth());
        zombie.setAbsorptionAmount(0.0F);
        zombie.clearFire();
        zombie.setAirSupply(zombie.getMaxAirSupply());
        zombie.setYRot(0.0F);
        zombie.setXRot(0.0F);
        zombie.setYHeadRot(0.0F);
        zombie.setYBodyRot(0.0F);
        zombie.yRotO = 0.0F;
        zombie.xRotO = 0.0F;
        zombie.yHeadRotO = 0.0F;
        zombie.yBodyRotO = 0.0F;
        zombie.hurtTime = 0;
        zombie.hurtDuration = 0;
        zombie.deathTime = 0;
        zombie.invulnerableTime = 0;
        zombie.fallDistance = 0.0;
        zombie.tickCount = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            zombie.setItemSlot(slot, ItemStack.EMPTY);
        }
        zombie.getRandom().setSeed(LEVEL_SEED + ENTITY_SEED_STEP * index);
    }

    private static void writeFrame(
            DataOutputStream output,
            List<Zombie> zombies,
            Vec3 origin,
            int tick
    ) throws IOException {
        output.writeInt(tick);
        for (int index = 0; index < zombies.size(); index++) {
            writeState(output, zombies.get(index), origin, index);
        }
    }

    private static void writeState(
            DataOutputStream output,
            Zombie zombie,
            Vec3 origin,
            int index
    ) throws IOException {
        output.writeInt(index);
        writeVec(output, zombie.position().subtract(origin));
        writeVec(output, zombie.getDeltaMovement());

        AABB bounds = zombie.getBoundingBox();
        writeDouble(output, bounds.minX - origin.x);
        writeDouble(output, bounds.minY - origin.y);
        writeDouble(output, bounds.minZ - origin.z);
        writeDouble(output, bounds.maxX - origin.x);
        writeDouble(output, bounds.maxY - origin.y);
        writeDouble(output, bounds.maxZ - origin.z);

        writeDouble(output, zombie.xo - origin.x);
        writeDouble(output, zombie.yo - origin.y);
        writeDouble(output, zombie.zo - origin.z);
        writeDouble(output, zombie.xOld - origin.x);
        writeDouble(output, zombie.yOld - origin.y);
        writeDouble(output, zombie.zOld - origin.z);
        writeFloat(output, zombie.getHealth());
        writeFloat(output, zombie.getAbsorptionAmount());
        writeDouble(output, zombie.fallDistance);
        writeFloat(output, zombie.getYRot());
        writeFloat(output, zombie.getXRot());
        writeFloat(output, zombie.getYHeadRot());
        writeFloat(output, zombie.yBodyRot);
        writeFloat(output, zombie.yRotO);
        writeFloat(output, zombie.xRotO);
        writeFloat(output, zombie.yHeadRotO);
        writeFloat(output, zombie.yBodyRotO);

        output.writeInt(zombie.tickCount);
        output.writeInt(zombie.hurtTime);
        output.writeInt(zombie.hurtDuration);
        output.writeInt(zombie.deathTime);
        output.writeInt(zombie.invulnerableTime);
        output.writeInt(zombie.getAirSupply());
        output.writeInt(zombie.getRemainingFireTicks());
        output.writeInt(zombie.getTicksFrozen());
        output.writeInt(zombie.getNoActionTime());
        output.writeInt(zombie.getPose().ordinal());
        output.writeInt(flags(zombie));
    }

    private static int flags(Zombie zombie) {
        int flags = 0;
        if (zombie.onGround()) flags |= 1;
        if (zombie.horizontalCollision) flags |= 1 << 1;
        if (zombie.verticalCollision) flags |= 1 << 2;
        if (zombie.verticalCollisionBelow) flags |= 1 << 3;
        if (zombie.minorHorizontalCollision) flags |= 1 << 4;
        if (zombie.hurtMarked) flags |= 1 << 5;
        if (zombie.isAlive()) flags |= 1 << 7;
        if (zombie.isDeadOrDying()) flags |= 1 << 8;
        if (zombie.isRemoved()) flags |= 1 << 9;
        if (zombie.isBaby()) flags |= 1 << 10;
        if (zombie.isNoGravity()) flags |= 1 << 11;
        if (zombie.isSilent()) flags |= 1 << 12;
        if (zombie.noPhysics) flags |= 1 << 13;
        if (zombie.isInWater()) flags |= 1 << 14;
        if (zombie.isInLava()) flags |= 1 << 15;
        return flags;
    }

    private static void writeVec(DataOutputStream output, Vec3 value) throws IOException {
        writeDouble(output, value.x);
        writeDouble(output, value.y);
        writeDouble(output, value.z);
    }

    private static void writeDouble(DataOutputStream output, double value) throws IOException {
        output.writeLong(Double.doubleToRawLongBits(value));
    }

    private static void writeFloat(DataOutputStream output, float value) throws IOException {
        output.writeInt(Float.floatToRawIntBits(value));
    }

    private static String mismatchMessage(byte[] expected, byte[] actual) {
        int common = Math.min(expected.length, actual.length);
        int offset = 0;
        while (offset < common && expected[offset] == actual[offset]) {
            offset++;
        }
        if (offset == common && expected.length == actual.length) {
            return "zombie cramming traces differ";
        }
        return "zombie cramming trace differs at byte " + offset
                + " (vanilla length=" + expected.length
                + ", optimized length=" + actual.length + ")";
    }
}

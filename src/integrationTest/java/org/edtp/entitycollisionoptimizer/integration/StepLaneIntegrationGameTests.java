package org.edtp.entitycollisionoptimizer.integration;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A mob pressed against a half-block step must step up exactly like vanilla, including when a
 * collidable vehicle hovers above its head.
 *
 * <p>1.21.11 queries entity colliders once with {@code boundingBox.expandTowards(movement)} and
 * reuses that list for the step attempt; only block colliders are re-queried with the step box.
 * An entity collider that is entirely above the movement query therefore never participates. Any
 * implementation that widens the entity query by {@code maxUpStep} manufactures a phantom collider
 * in that band and pins the mob against the step, which is the reported "stuck and jittering
 * animals around boats" behaviour.
 */
public final class StepLaneIntegrationGameTests {
    private static final int TRACE_MAGIC = 0x45434F53;
    private static final int TRACE_VERSION = 1;
    private static final int ZOMBIE_COUNT = 2;
    private static final int TICKS = 120;
    private static final int ROOM_SIZE = 9;
    private static final int ROOM_HEIGHT = 8;
    private static final double SLAB_X = 6.0;
    private static final double LANE_START_X = 2.5;
    private static final double PUSH_SPEED = 0.3;
    /** Gravity applied by LivingEntity.travel while standing on the floor. */
    private static final double FALL_SPEED = -0.0784;
    private static final double HOVER_Y = 2.1;
    private static final long LEVEL_SEED = 0x5EED_EC02_8CL;
    private static final long ENTITY_SEED_STEP = 0x9E37_79B9_7F4A_7C15L;
    private static final int ENTITY_ID_BASE = 4_000_000;
    private static final long DAY_TIME = 18_000L;
    private static final long GAME_TIME = 1_000L;
    private static final Vec3 SCENE_ORIGIN = new Vec3(-5_909_600.0, -57.0, -9_908_300.0);

    @GameTest(
            maxTicks = 1000,
            environment = "entity_collision_optimizer:integration"
    )
    public void stepLaneMatchesVanilla(GameTestHelper helper) {
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
        private final List<Zombie> zombies = new ArrayList<>(ZOMBIE_COUNT);
        private final List<AbstractBoat> hovering = new ArrayList<>(1);
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024 * 1024);
        private final DataOutputStream output = new DataOutputStream(bytes);
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
                // A single half-block step spans both lanes (z 3.0 and 3.7).
                arena.setBlock(sceneOrigin.add(SLAB_X, 0.0, 3.0), Blocks.STONE_SLAB.defaultBlockState());
                arena.awaitReadyRoom(sceneOrigin, ROOM_SIZE, ROOM_HEIGHT, ROOM_SIZE);
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
                    if (!arena.isReadyForEntityTicks()) return;
                    initializeTrace();
                    started = true;
                    return;
                }
                // Deterministic drivers: the mobs keep pressing into the step and the vehicle keeps
                // hovering, so both processes replay the identical input sequence. Entity.move is
                // called directly because Mob.travel is skipped while AI is disabled.
                for (Zombie zombie : zombies) {
                    zombie.setDeltaMovement(PUSH_SPEED, FALL_SPEED, 0.0);
                    zombie.move(MoverType.SELF, zombie.getDeltaMovement());
                }
                for (AbstractBoat boat : hovering) {
                    boat.setPos(sceneOrigin.x + SLAB_X + 0.25, sceneOrigin.y + HOVER_Y, sceneOrigin.z + 3.5);
                    boat.setDeltaMovement(Vec3.ZERO);
                }
                tick++;
                writeFrame(tick);
                if (tick == TICKS) finish();
            } catch (IOException failure) {
                cleanup();
                finished = true;
                throw new IllegalStateException("Cannot encode step lane trace", failure);
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
            long seed = LEVEL_SEED;

            AbstractBoat boat = arena.spawn(EntityType.OAK_BOAT,
                    sceneOrigin.add(SLAB_X + 0.25, HOVER_Y, 3.5));
            boat.setOldPosAndRot();
            boat.setDeltaMovement(Vec3.ZERO);
            boat.setOnGround(false);
            boat.tickCount = 0;
            boat.getRandom().setSeed(seed += ENTITY_SEED_STEP);
            hovering.add(boat);

            double[] lanes = {3.0, 3.7};
            for (int index = 0; index < ZOMBIE_COUNT; index++) {
                Zombie zombie = arena.spawn(EntityType.ZOMBIE,
                        sceneOrigin.add(LANE_START_X, 0.0, lanes[index]));
                ZombieTrace.normalize(zombie, seed += ENTITY_SEED_STEP);
                zombie.setNoAi(true);
                zombie.setDeltaMovement(PUSH_SPEED, FALL_SPEED, 0.0);
                zombies.add(zombie);
            }
            level.getRandom().setSeed(LEVEL_SEED);

            output.writeInt(TRACE_MAGIC);
            output.writeInt(TRACE_VERSION);
            output.writeInt(ZOMBIE_COUNT);
            output.writeInt(TICKS);
            writeFrame(0);
        }

        private void writeFrame(int frameTick) throws IOException {
            output.writeInt(frameTick);
            for (AbstractBoat boat : hovering) writeVehicle(output, boat, sceneOrigin);
            for (int index = 0; index < zombies.size(); index++) {
                ZombieTrace.writeState(output, zombies.get(index), sceneOrigin, index);
            }
        }

        private void finish() throws IOException {
            for (AbstractBoat boat : hovering) output.writeLong(boat.getRandom().nextLong());
            for (Zombie zombie : zombies) output.writeLong(zombie.getRandom().nextLong());
            output.close();
            byte[] actual = bytes.toByteArray();
            try {
                CrossProcessTrace.verify(helper, "step-lane", actual);
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

    private static void writeVehicle(DataOutputStream output, Entity entity, Vec3 origin) throws IOException {
        writeVec(output, entity.position().subtract(origin));
        writeVec(output, entity.getDeltaMovement());
        AABB bounds = entity.getBoundingBox();
        ZombieTrace.writeDouble(output, bounds.minX - origin.x);
        ZombieTrace.writeDouble(output, bounds.minY - origin.y);
        ZombieTrace.writeDouble(output, bounds.minZ - origin.z);
        ZombieTrace.writeDouble(output, bounds.maxX - origin.x);
        ZombieTrace.writeDouble(output, bounds.maxY - origin.y);
        ZombieTrace.writeDouble(output, bounds.maxZ - origin.z);
    }

    private static void writeVec(DataOutputStream output, Vec3 value) throws IOException {
        ZombieTrace.writeDouble(output, value.x);
        ZombieTrace.writeDouble(output, value.y);
        ZombieTrace.writeDouble(output, value.z);
    }
}

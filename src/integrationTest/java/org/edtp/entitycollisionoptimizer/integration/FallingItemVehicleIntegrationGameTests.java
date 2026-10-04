package org.edtp.entitycollisionoptimizer.integration;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-process parity fixture for the three reported in-game regressions: falling items that keep
 * bouncing after landing, vehicles that create phantom collision boxes, and large collidable mobs.
 *
 * <p>Items land on a stone floor while a boat, a minecart and a happy ghast sit inside a crowd of
 * stationary zombies, so the movement solver, the hard-collision cube path, the native push run and
 * the vanilla vehicle push path are all exercised at once. Every byte of the recorded trace must
 * match the vanilla-only process.
 */
public final class FallingItemVehicleIntegrationGameTests {
    private static final int TRACE_MAGIC = 0x45434F49;
    private static final int TRACE_VERSION = 1;
    private static final int FALLING_ITEM_COUNT = 8;
    private static final int ITEM_COUNT = FALLING_ITEM_COUNT + 4;
    private static final int ZOMBIE_COUNT = 8;
    private static final int TICKS = 140;
    private static final int ROOM_SIZE = 9;
    private static final int ROOM_HEIGHT = 8;
    private static final double ITEM_HEIGHT = 6.0;
    private static final long LEVEL_SEED = 0x5EED_EC02_8BL;
    private static final long ENTITY_SEED_STEP = 0x9E37_79B9_7F4A_7C15L;
    private static final int ENTITY_ID_BASE = 3_000_000;
    private static final long DAY_TIME = 18_000L;
    private static final long GAME_TIME = 1_000L;
    private static final Vec3 SCENE_ORIGIN = new Vec3(-5_909_700.0, -57.0, -9_908_200.0);

    @GameTest(
            maxTicks = 1000,
            environment = "entity_collision_optimizer:integration"
    )
    public void fallingItemsAndVehiclesMatchVanilla(GameTestHelper helper) {
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
        private final List<ItemEntity> items = new ArrayList<>(ITEM_COUNT);
        private final List<Zombie> zombies = new ArrayList<>(ZOMBIE_COUNT);
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 * 1024 * 1024);
        private final DataOutputStream output = new DataOutputStream(bytes);
        private AbstractBoat boat;
        private AbstractMinecart minecart;
        private HappyGhast ghast;
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
                tick++;
                writeFrame(tick);
                if (tick == TICKS) finish();
            } catch (IOException failure) {
                cleanup();
                finished = true;
                throw new IllegalStateException("Cannot encode falling item trace", failure);
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

            // Items fall from a fixed height onto the stone floor; spacing above the 0.5 block
            // merge radius keeps neighbour merging out of the trace.
            for (int index = 0; index < FALLING_ITEM_COUNT; index++) {
                Vec3 position = sceneOrigin.add(
                        1.5 + (index % 4) * 2.0,
                        ITEM_HEIGHT,
                        1.5 + (index / 4) * 2.0
                );
                items.add(spawnItem(position, index, seed += ENTITY_SEED_STEP));
            }

            boat = arena.spawn(EntityType.OAK_BOAT, sceneOrigin.add(2.5, 0.0, 6.5));
            minecart = arena.spawn(EntityType.MINECART, sceneOrigin.add(6.5, 0.0, 6.5));
            ghast = arena.spawn(EntityType.HAPPY_GHAST, sceneOrigin.add(4.5, 1.0, 2.5));
            for (Entity vehicle : new Entity[]{boat, minecart, ghast}) {
                vehicle.setOldPosAndRot();
                vehicle.setDeltaMovement(Vec3.ZERO);
                vehicle.setOnGround(true);
                vehicle.tickCount = 0;
                vehicle.getRandom().setSeed(seed += ENTITY_SEED_STEP);
                if (vehicle instanceof Mob mob) {
                    mob.setNoAi(true);
                }
            }

            // Stationary zombies overlap the vehicles, so every tick runs the push path for them.
            Vec3[] crowd = {
                    new Vec3(2.5, 0.0, 5.6), new Vec3(1.6, 0.0, 6.5), new Vec3(3.4, 0.0, 6.5),
                    new Vec3(6.5, 0.0, 5.6), new Vec3(5.6, 0.0, 6.5), new Vec3(7.4, 0.0, 6.5),
                    new Vec3(4.5, 0.0, 3.6), new Vec3(3.6, 0.0, 2.5)
            };
            for (int index = 0; index < ZOMBIE_COUNT; index++) {
                Zombie zombie = arena.spawn(EntityType.ZOMBIE, sceneOrigin.add(crowd[index]));
                ZombieTrace.normalize(zombie, seed += ENTITY_SEED_STEP);
                zombies.add(zombie);
            }

            // Items resting inside the collidable vehicles. Their per-tick noPhysics test combines
            // the block scan with the entity-collision query, and a positive result kicks the item
            // through moveTowardsClosestSpace, which is the reported "keeps bouncing" behaviour.
            Vec3[] overlaps = {
                    new Vec3(2.5, 0.1, 6.5), new Vec3(1.9, 0.3, 6.5),
                    new Vec3(4.5, 1.1, 2.5), new Vec3(6.5, 0.1, 6.5)
            };
            for (int index = 0; index < overlaps.length; index++) {
                items.add(spawnItem(sceneOrigin.add(overlaps[index]),
                        FALLING_ITEM_COUNT + index, seed += ENTITY_SEED_STEP));
            }
            level.getRandom().setSeed(LEVEL_SEED);

            output.writeInt(TRACE_MAGIC);
            output.writeInt(TRACE_VERSION);
            output.writeInt(ITEM_COUNT);
            output.writeInt(ZOMBIE_COUNT);
            output.writeInt(TICKS);
            writeFrame(0);
        }

        private void writeFrame(int frameTick) throws IOException {
            output.writeInt(frameTick);
            for (ItemEntity item : items) writeItem(output, item, sceneOrigin);
            writeVehicle(output, boat, sceneOrigin);
            writeVehicle(output, minecart, sceneOrigin);
            writeVehicle(output, ghast, sceneOrigin);
            for (int index = 0; index < zombies.size(); index++) {
                ZombieTrace.writeState(output, zombies.get(index), sceneOrigin, index);
            }
        }

        private void finish() throws IOException {
            for (Entity entity : entities()) {
                output.writeLong(entity.getRandom().nextLong());
            }
            output.close();
            byte[] actual = bytes.toByteArray();
            try {
                CrossProcessTrace.verify(helper, "falling-item-vehicles", actual);
            } finally {
                cleanup();
                finished = true;
            }
            helper.succeed();
        }

        private List<Entity> entities() {
            List<Entity> all = new ArrayList<>(items.size() + zombies.size() + 3);
            all.addAll(items);
            all.add(boat);
            all.add(minecart);
            all.add(ghast);
            all.addAll(zombies);
            return all;
        }

        private void cleanup() {
            if (cleanedUp) return;
            cleanedUp = true;
            arena.close();
            arena.setDefaultClockTime(previousDayTime);
            arena.setGameTime(previousGameTime);
        }

        private ItemEntity spawnItem(Vec3 position, int index, long itemSeed) {
            ItemEntity item = arena.spawn(EntityType.ITEM, position);
            item.setItem(new ItemStack(Items.STONE, 1 + index % 3));
            item.setNeverPickUp();
            item.setOldPosAndRot();
            item.setDeltaMovement(Vec3.ZERO);
            item.setOnGround(false);
            item.tickCount = 0;
            item.getRandom().setSeed(itemSeed);
            return item;
        }
    }

    private static void writeItem(DataOutputStream output, ItemEntity item, Vec3 origin) throws IOException {
        output.writeInt(item.getAge());
        output.writeBoolean(item.hasPickUpDelay());
        writeVec(output, item.position().subtract(origin));
        writeVec(output, item.getDeltaMovement());
        writeBounds(output, item.getBoundingBox(), origin);
        output.writeBoolean(item.onGround());
        output.writeBoolean(item.noPhysics);
        output.writeBoolean(item.isRemoved());
        output.writeBoolean(item.getItem().isEmpty());
    }

    private static void writeVehicle(DataOutputStream output, Entity entity, Vec3 origin) throws IOException {
        writeVec(output, entity.position().subtract(origin));
        writeVec(output, entity.getDeltaMovement());
        writeBounds(output, entity.getBoundingBox(), origin);
        output.writeBoolean(entity.onGround());
        output.writeBoolean(entity.isRemoved());
        output.writeInt(entity.getPassengers().size());
        output.writeInt(entity.tickCount);
    }

    private static void writeBounds(DataOutputStream output, AABB bounds, Vec3 origin) throws IOException {
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

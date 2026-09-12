package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Vanilla vs ECO under 26.2 ticket spreading from a radius-2 FORCED ticket:
 * entity ticking (31), block ticking / weak load (32), loaded-without-tick (33).
 */
final class ChunkLoadParity {
    private static final int WAIT = 24;
    private static final int RADIUS = 2;
    private static final Vec3 PORTAL_VELOCITY = new Vec3(0.35, 0.15, -0.22);

    private ChunkLoadParity() {
    }

    static void verify(GameTestHelper helper) {
        Trial trial = new Trial(helper);
        // Its distinct test environment gives this asynchronous oracle a separate
        // batch: no other parity test or benchmark can toggle the global backend.
        var sequence = helper.startSequence();
        for (boolean optimized : new boolean[]{false, true}) {
            sequence.thenExecute(() -> trial.guard(() -> trial.begin(optimized, optimized ? 96 : 80, optimized ? 96 : 80)))
                    .thenWaitUntil(trial::ready).thenExecute(() -> trial.guard(trial::populate));
            for (int step = 0; step < WAIT; step++) {
                int tick = step;
                sequence.thenIdle(1).thenExecute(() -> trial.observe(tick));
            }
            sequence.thenExecute(() -> trial.guard(optimized ? trial::compare : trial::captureExpected));
        }
        sequence.thenExecute(() -> { if (!trial.failed) helper.succeed(); });
        helper.onEachTick(() -> {
            if (!trial.failed && helper.getTick() >= 790) {
                trial.reset();
                CollisionOptimizerConfig.enableEntityCollision = trial.previous;
                helper.assertTrue(false, "chunk fixture readiness timed out");
            }
        });
    }

    private static final class Trial {
        private final GameTestHelper helper;
        private final ServerLevel overworld;
        private final boolean previous;
        private boolean failed;
        private final List<Runnable> cleanup = new ArrayList<>();
        private ChunkLoadObservation expected;
        private ChunkPos weak;
        private ChunkPos strong;
        private ChunkPos loaded;
        private ChunkPos netherDest;
        private ChunkPos portalSource;
        private ItemEntity weakItem;
        private ItemEntity strongItem;
        private ItemEntity boundaryItem;
        private ItemEntity unloadItem;
        private ItemEntity returningItem;
        private ItemEntity loadedItem;
        private ItemEntity wallItem;
        private ItemEntity boatItem;
        private Entity weakBoat;
        private ChunkEntityTrace trace;
        private ChunkEntityTrace portalTrace;
        private Entity portalEntity;
        private ChunkFixtureBlocks blocks;
        private List<DimensionMomentumParity.Observation> transfers;
        private BlockPos lamp;
        private BlockPos idleLamp;
        private Vec3 portalEndVelocity;
        private boolean portalKeptEntity;

        private Trial(GameTestHelper helper) {
            this.helper = helper;
            this.overworld = helper.getLevel();
            this.previous = CollisionOptimizerConfig.enableEntityCollision;
        }

        private void begin(boolean optimized, int chunkX, int chunkZ) {
            reset();
            CollisionOptimizerConfig.enableEntityCollision = optimized;
            CollisionFrame.end(overworld);
            strong = new ChunkPos(chunkX, chunkZ);
            weak = new ChunkPos(chunkX + 1, chunkZ);
            loaded = new ChunkPos(chunkX + 2, chunkZ);
            netherDest = new ChunkPos(chunkX, chunkZ + 4);
            portalSource = new ChunkPos(chunkX, chunkZ + 8);
            force(overworld, strong);
            force(overworld, portalSource);
            force(nether(), netherDest);
        }

        private void guard(Runnable action) {
            if (failed) return;
            try { action.run(); }
            catch (RuntimeException | Error failure) {
                failed = true;
                EntityCollisionOptimizer.LOGGER.error("ECO_CHUNK_STAGE action failed chunk={} tick={}", strong, helper.getTick(), failure);
                reset();
                CollisionOptimizerConfig.enableEntityCollision = previous;
                throw failure;
            }
        }

        private void force(ServerLevel level, ChunkPos chunk) {
            ServerChunkCache chunks = level.getChunkSource();
            CompletableFuture<?> loaded = chunks.addTicketAndLoadWithRadius(TicketType.FORCED, chunk, RADIUS);
            cleanup.add(() -> chunks.removeTicketWithRadius(TicketType.FORCED, chunk, RADIUS));
            level.getServer().managedBlock(loaded::isDone);
            loaded.join();
        }

        private ServerLevel nether() {
            ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "nether");
            return nether;
        }

        private void ready() {
            if (failed) return;
            helper.assertTrue(overworld.getChunkSource().getChunkNow(strong.x(), strong.z()) != null, "strong chunk");
            helper.assertTrue(overworld.getChunkSource().getChunkNow(weak.x(), weak.z()) != null, "weak chunk");
            helper.assertTrue(overworld.getChunkSource().getChunkNow(loaded.x(), loaded.z()) != null, "loaded chunk");
            helper.assertTrue(nether().getChunkSource().getChunkNow(netherDest.x(), netherDest.z()) != null, "nether dest");
            helper.assertTrue(nether().isPositionEntityTicking(netherDest.getWorldPosition()),
                    "nether destination entity ticking ready");
            helper.assertTrue(overworld.isPositionEntityTicking(portalSource.getWorldPosition()), "portal source ready");
            BlockPos strongPos = strong.getWorldPosition().offset(8, 70, 8);
            BlockPos weakPos = weak.getWorldPosition().offset(8, 70, 8);
            BlockPos loadedPos = loaded.getWorldPosition().offset(8, 70, 8);
            helper.assertTrue(overworld.shouldTickBlocksAt(weakPos), "weak chunk must block-tick");
            helper.assertTrue(!overworld.isPositionEntityTicking(weakPos), "weak chunk must not entity-tick");
            helper.assertTrue(overworld.isPositionEntityTicking(strongPos), "strong chunk must entity-tick");
            helper.assertTrue(!overworld.shouldTickBlocksAt(loadedPos), "loaded-without-tick chunk must not block-tick");
        }

        private void populate() {
            blocks = new ChunkFixtureBlocks();
            cleanup.add(blocks::close);
            lamp = platform(overworld, weak, 4, 4);
            idleLamp = platform(overworld, loaded, 4, 4);
            powerRepeater(lamp);
            powerRepeater(idleLamp);
            strongItem = item(strong, 8.5, 8.0, 8.5, Vec3.ZERO);
            weakItem = item(weak, 8.5, 8.0, 8.5, Vec3.ZERO);
            boundaryItem = item(strong, 15.4, 3.0, 8.5, new Vec3(0.45, 0.0, 0.0));
            unloadItem = item(weak, 15.8, 3.0, 8.5, new Vec3(0.45, 0.0, 0.0));
            returningItem = item(weak, 0.1, 5.0, 12.5, new Vec3(-0.3, 0.0, 0.0));
            loadedItem = item(loaded, 8.5, 8.0, 8.5, new Vec3(0.2, 0.1, 0.0));
            blocks.set(overworld, weak.getWorldPosition().offset(0, 73, 3), Blocks.STONE.defaultBlockState(), 3);
            wallItem = item(strong, 15.4, 3.2, 3.5, new Vec3(0.45, 0, 0));
            wallItem.setNoGravity(true);
            weakBoat = EntityTypes.OAK_BOAT.create(overworld, EntitySpawnReason.COMMAND);
            helper.assertTrue(weakBoat != null, "weak boat fixture");
            weakBoat.setPos(block(weak, 0.5, 3, 10.5));
            weakBoat.setNoGravity(true);
            helper.assertTrue(overworld.addFreshEntity(weakBoat), "spawn weak boat");
            cleanup.add(weakBoat::discard);
            boatItem = item(strong, 15.2, 3.2, 10.5, new Vec3(0.45, 0, 0));
            boatItem.setNoGravity(true);
            trace = new ChunkEntityTrace();
            portalTrace = new ChunkEntityTrace();
            portalEndVelocity = portal();
        }

        private void observe(int tick) {
            guard(() -> {
                ready();
                if (tick == 8) {
                    // Piston displacement may move an entity that is not autonomously
                    // ticking. Exercise reactivation and entry into the level-33 ring.
                    returningItem.move(MoverType.PISTON, new Vec3(-0.5, 0, 0));
                    unloadItem.move(MoverType.PISTON, new Vec3(0.5, 0, 0));
                }
                trace.record(block(strong, 0, 0, 0), strongItem, weakItem, boundaryItem,
                        unloadItem, returningItem, loadedItem, wallItem, boatItem, weakBoat);
                portalTrace.record(block(netherDest, 0, 0, 0), portalEntity);
            });
        }

        private Vec3 portal() {
            ServerLevel nether = nether();
            Vec3 destination = block(netherDest, 8.5, 4.0, 8.5);
            blocks.room(nether, netherDest);
            // A return teleport adds destination tickets. Keep those tickets away
            // from the strong/weak/loaded rings whose levels are under test.
            blocks.room(overworld, portalSource);
            transfers = DimensionMomentumParity.capture(helper, overworld, nether,
                    block(portalSource, 8.5, 0, 8.5), block(netherDest, 8.5, 0, 8.5));
            ItemEntity projectile = item(strong, 4.5, 4.0, 4.5, PORTAL_VELOCITY);
            Entity teleported = projectile.teleport(new TeleportTransition(
                    nether, destination, PORTAL_VELOCITY, 0.0F, 0.0F, Set.of(), TeleportTransition.DO_NOTHING));
            helper.assertTrue(teleported != null, "portal teleport");
            portalKeptEntity = teleported == projectile;
            portalEntity = teleported;
            if (teleported != projectile) cleanup.add(teleported::discard);
            return teleported.getDeltaMovement();
        }

        private void captureExpected() {
            expected = snapshot();
            reset();
        }

        private void compare() {
            try {
                snapshot().compare(helper, expected, WAIT, PORTAL_VELOCITY);
            } finally {
                reset();
                CollisionOptimizerConfig.enableEntityCollision = previous;
            }
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_CHUNK_LOAD_PARITY wait={} trace_entities=10 dimension_observations=96 weak_block_collision=true weak_boat_collision=true weak_to_strong=true weak_to_loaded=true weak_lamp=true idle_lamp=false portal_velocity={} result=passed",
                    WAIT, PORTAL_VELOCITY);
        }

        private ChunkLoadObservation snapshot() {
            return new ChunkLoadObservation(trace, portalTrace, transfers,
                    powered(lamp), powered(idleLamp), portalEndVelocity, portalKeptEntity);
        }

        private BlockPos platform(ServerLevel level, ChunkPos chunk, int localX, int localZ) {
            BlockPos origin = chunk.getWorldPosition().offset(localX, 70, localZ);
            for (int dx = -1; dx <= 2; dx++) {
                for (int dz = -1; dz <= 4; dz++) {
                    blocks.set(level, origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                    blocks.set(level, origin.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 3);
                    blocks.set(level, origin.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
            return origin;
        }

        private void powerRepeater(BlockPos origin) {
            blocks.set(overworld, origin, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            blocks.set(overworld, origin.relative(Direction.SOUTH), Blocks.REPEATER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(BlockStateProperties.DELAY, 4), 3);
            blocks.set(overworld, origin.relative(Direction.SOUTH, 2), Blocks.REPEATER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(BlockStateProperties.DELAY, 4), 3);
            BlockPos lampPos = origin.relative(Direction.SOUTH, 3);
            blocks.set(overworld, lampPos, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        }

        private boolean powered(BlockPos origin) {
            BlockState lampState = overworld.getBlockState(origin.relative(Direction.SOUTH, 3));
            return lampState.is(Blocks.REDSTONE_LAMP) && lampState.getValue(BlockStateProperties.LIT);
        }

        private ItemEntity item(ChunkPos chunk, double localX, double localY, double localZ, Vec3 velocity) {
            Vec3 position = block(chunk, localX, localY, localZ);
            ItemEntity entity = new ItemEntity(overworld, position.x, position.y, position.z, new ItemStack(Items.STONE));
            entity.setNeverPickUp();
            entity.setUnlimitedLifetime();
            entity.setNoGravity(false);
            entity.setDeltaMovement(velocity);
            entity.setPos(position);
            entity.setOldPosAndRot();
            helper.assertTrue(overworld.addFreshEntity(entity), "spawn item");
            cleanup.add(entity::discard);
            return entity;
        }

        private static Vec3 block(ChunkPos chunk, double localX, double localY, double localZ) {
            BlockPos origin = chunk.getWorldPosition();
            return new Vec3(origin.getX() + localX, 70 + localY, origin.getZ() + localZ);
        }

        private void reset() {
            for (int index = cleanup.size() - 1; index >= 0; index--) cleanup.get(index).run();
            cleanup.clear();
            weakItem = strongItem = boundaryItem = unloadItem = null;
            lamp = idleLamp = null;
            CollisionFrame.end(overworld);
            CollisionFrame.end(nether());
        }
    }

}

package org.edtp.entitycollisionoptimizer.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owns scenario blocks and entities so every scenario restores its world state. */
final class IntegrationArena implements AutoCloseable {
    private static final long PREPARATION_TIMEOUT_NANOS = 30_000_000_000L;

    private final ServerLevel level;
    private final Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Set<Long> requiredChunks = new LinkedHashSet<>();
    private final Set<Long> forcedChunks = new LinkedHashSet<>();
    private final int entityIdBase;
    private final long entityUuidSeed;
    private int entitySequence;
    private boolean closed;

    IntegrationArena(ServerLevel level, int entityIdBase, long entityUuidSeed) {
        this.level = level;
        this.entityIdBase = entityIdBase;
        this.entityUuidSeed = entityUuidSeed;
    }

    void buildStoneRoom(Vec3 sceneOrigin, int width, int height, int depth) {
        BlockPos origin = BlockPos.containing(sceneOrigin);
        for (int y = -1; y <= height; y++) {
            for (int x = -1; x <= width; x++) {
                for (int z = -1; z <= depth; z++) {
                    BlockPos position = origin.offset(x, y, z);
                    forceChunk(position);
                    originalBlocks.put(position, level.getBlockState(position));
                    boolean boundary = y == -1 || y == height
                            || x == -1 || x == width
                            || z == -1 || z == depth;
                    level.setBlockAndUpdate(position, boundary
                            ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private void forceChunk(BlockPos position) {
        int chunkX = position.getX() >> 4;
        int chunkZ = position.getZ() >> 4;
        long packed = ChunkPos.asLong(chunkX, chunkZ);
        requiredChunks.add(packed);
        if (!level.getForcedChunks().contains(packed)) {
            if (!level.setChunkForced(chunkX, chunkZ, true)) {
                throw new IllegalStateException("Cannot force integration-test chunk " + chunkX + ", " + chunkZ);
            }
            forcedChunks.add(packed);
        }
        level.getChunkAt(position);
    }

    boolean isReadyForEntityTicks() {
        return !requiredChunks.isEmpty() && requiredChunks.stream().allMatch(packed ->
                level.areEntitiesLoaded(packed)
                        && level.isPositionEntityTicking(new ChunkPos(packed).getMiddleBlockPosition(0)));
    }

    void awaitReadyRoom(Vec3 sceneOrigin, int width, int height, int depth) {
        // GameTest ticks are unthrottled, so pump remote chunk work explicitly instead of
        // spending the test's tick budget while generation and entity I/O wait for CPU time.
        long deadline = System.nanoTime() + PREPARATION_TIMEOUT_NANOS;
        level.getServer().managedBlock(() -> {
            // 1.21.1 applies forced-chunk tickets and schedules light batches from
            // ServerChunkCache.tick; managedBlock alone does not advance them.
            level.getChunkSource().tick(() -> true, false);
            level.getChunkSource().getLightEngine().tryScheduleUpdate();
            level.getChunkSource().pollTask();
            return chunkTasksAreReady(sceneOrigin, width, height, depth)
                    || System.nanoTime() >= deadline;
        });
        if (!chunkTasksAreReady(sceneOrigin, width, height, depth)) {
            throw new IllegalStateException("Integration-test room did not become ready within 30 seconds");
        }
        // In 1.21.1 entity I/O is finalized by subsequent world ticks. The scenario
        // waits for isReadyForEntityTicks() before creating any recorded entities.
    }

    private boolean chunkTasksAreReady(Vec3 sceneOrigin, int width, int height, int depth) {
        return !requiredChunks.isEmpty()
                && requiredChunks.stream().allMatch(level.getChunkSource()::isPositionTicking)
                && isDarkRoom(sceneOrigin, width, height, depth);
    }

    private boolean isDarkRoom(Vec3 sceneOrigin, int width, int height, int depth) {
        BlockPos origin = BlockPos.containing(sceneOrigin);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    BlockPos position = origin.offset(x, y, z);
                    if (level.canSeeSky(position) || level.getMaxLocalRawBrightness(position) > 7) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // Minecraft 1.21.1 GameTest deadlines use level gameTime. Normalize only day
    // time; gameTime must remain monotonic for callbacks and timeout tracking.
    long defaultClockTime() {
        return level.getDayTime();
    }

    void setDefaultClockTime(long ticks) {
        level.setDayTime(ticks);
    }

    <T extends Entity> T spawn(EntityType<T> type, Vec3 position) {
        T entity = type.create(level);
        if (entity == null) {
            throw new IllegalStateException("Cannot create integration-test entity " + type);
        }
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        int sequence = entitySequence++;
        entity.setId(entityIdBase + sequence);
        entity.setUUID(new UUID(entityUuidSeed, Integer.toUnsignedLong(sequence)));
        entity.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
        entity.setYBodyRot(0.0F);
        entity.setYHeadRot(0.0F);
        level.addFreshEntityWithPassengers(entity);
        entities.add(entity);
        return entity;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Entity entity : entities) {
            entity.discard();
        }
        for (Map.Entry<BlockPos, BlockState> entry : originalBlocks.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        }
        for (long packed : forcedChunks) {
            level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
        }
    }
}

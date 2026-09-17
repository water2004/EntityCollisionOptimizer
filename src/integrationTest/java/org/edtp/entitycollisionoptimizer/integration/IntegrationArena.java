package org.edtp.entitycollisionoptimizer.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ServerLevelData;
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
        long packed = ChunkPos.pack(chunkX, chunkZ);
        requiredChunks.add(packed);
        if (!level.getForceLoadedChunks().contains(packed)) {
            if (!level.setChunkForced(chunkX, chunkZ, true)) {
                throw new IllegalStateException("Cannot force integration-test chunk " + chunkX + ", " + chunkZ);
            }
            forcedChunks.add(packed);
        }
        level.getChunkAt(position);
    }

    boolean isReadyForEntityTicks() {
        return !requiredChunks.isEmpty() && requiredChunks.stream()
                .map(ChunkPos::unpack)
                .allMatch(level::areEntitiesActuallyLoadedAndTicking);
    }

    boolean isDarkRoom(Vec3 sceneOrigin, int width, int height, int depth) {
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

    long defaultClockTime() {
        return level.getDefaultClockTime();
    }

    void setDefaultClockTime(long ticks) {
        level.clockManager().setTotalTicks(
                level.dimensionType().defaultClock().orElseThrow(
                        () -> new IllegalStateException("Integration-test dimension has no default clock")
                ),
                ticks
        );
    }

    long gameTime() {
        return level.getLevelData().getGameTime();
    }

    void setGameTime(long ticks) {
        ((ServerLevelData) level.getLevelData()).setGameTime(ticks);
    }

    <T extends Entity> T spawn(EntityType<T> type, Vec3 position) {
        T entity = type.create(level, EntitySpawnReason.STRUCTURE);
        if (entity == null) {
            throw new IllegalStateException("Cannot create integration-test entity " + type);
        }
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        int sequence = entitySequence++;
        entity.setId(entityIdBase + sequence);
        entity.setUUID(new UUID(entityUuidSeed, Integer.toUnsignedLong(sequence)));
        entity.snapTo(position.x, position.y, position.z, 0.0F, 0.0F);
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

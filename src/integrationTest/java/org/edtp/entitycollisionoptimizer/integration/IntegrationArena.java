package org.edtp.entitycollisionoptimizer.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns fixed-coordinate blocks and entities so every scenario restores its world state. */
final class IntegrationArena implements AutoCloseable {
    private final ServerLevel level;
    private final Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private boolean closed;

    IntegrationArena(ServerLevel level) {
        this.level = level;
    }

    void buildStoneRoom(Vec3 sceneOrigin, int width, int height, int depth) {
        BlockPos origin = BlockPos.containing(sceneOrigin);
        level.getChunkAt(origin);
        for (int y = -1; y <= height; y++) {
            for (int x = -1; x <= width; x++) {
                for (int z = -1; z <= depth; z++) {
                    BlockPos position = origin.offset(x, y, z);
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

    <T extends Entity> T track(T entity) {
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
    }
}

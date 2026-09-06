package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** Preserves vanilla's z/y/x traversal, halo tests, shape context and collider order. */
public final class OrderedBlockColliders {
    private OrderedBlockColliders() {}

    public static List<VoxelShape> collect(Entity entity, AABB box, List<VoxelShape> entityShapes) {
        return collect(entity.level(), CollisionContext.of(entity), entity, box, entityShapes);
    }

    public static List<VoxelShape> collect(Level level, CollisionContext context, Entity entity,
                                           AABB box, List<VoxelShape> entityShapes) {
        List<VoxelShape> result = new ArrayList<>(entityShapes.size() + 16);
        result.addAll(entityShapes);
        var border = level.getWorldBorder();
        if (entity != null && border.isInsideCloseToBorder(entity, box)) result.add(border.getCollisionShape());
        append(level, context, box, result);
        return result;
    }

    public static void append(Level level, CollisionContext context, AABB box, List<VoxelShape> result) {
        new Scan(level, context, box, result).run();
    }

    private static final class Scan {
        private final Level level;
        private final CollisionContext context;
        private final AABB box;
        private final VoxelShape boxShape;
        private final List<VoxelShape> result;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final int minX, maxX, minY, maxY, minZ, maxZ, minChunkX, minChunkZ, chunkWidth;
        private final ChunkAccess[] chunks;
        private final boolean[] visited;

        private Scan(Level level, CollisionContext context, AABB box, List<VoxelShape> result) {
            this.level = level;
            this.context = context;
            this.box = box;
            this.boxShape = Shapes.create(box);
            this.result = result;
            minX = Mth.floor(box.minX - 1.0E-7) - 1;
            maxX = Mth.floor(box.maxX + 1.0E-7) + 1;
            minY = Mth.floor(box.minY - 1.0E-7) - 1;
            maxY = Mth.floor(box.maxY + 1.0E-7) + 1;
            minZ = Mth.floor(box.minZ - 1.0E-7) - 1;
            maxZ = Mth.floor(box.maxZ + 1.0E-7) + 1;
            minChunkX = minX >> 4;
            minChunkZ = minZ >> 4;
            chunkWidth = (maxX >> 4) - minChunkX + 1;
            int count = Math.multiplyExact(chunkWidth, (maxZ >> 4) - minChunkZ + 1);
            chunks = new ChunkAccess[count];
            visited = new boolean[count];
        }

        private void run() {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    int edgesYZ = edge(y, minY, maxY) + edge(z, minZ, maxZ);
                    for (int chunkX = minChunkX; chunkX <= maxX >> 4; chunkX++) {
                        ChunkAccess chunk = chunk(chunkX, z >> 4);
                        if (chunk == null) continue;
                        int sectionIndex = chunk.getSectionIndex(y);
                        LevelChunkSection[] sections = chunk.getSections();
                        if (sectionIndex < 0 || sectionIndex >= sections.length) continue;
                        LevelChunkSection section = sections[sectionIndex];
                        int start = Math.max(minX, chunkX << 4);
                        int end = Math.min(maxX, (chunkX << 4) + 15);
                        int mask = ((CollisionBlockMask) section.getStates())
                                .entityCollisionOptimizer$nonAirRow(y & 15, z & 15);
                        mask &= (0xffff << (start & 15)) & (0xffff >>> (15 - (end & 15)));
                        while (mask != 0) {
                            int localX = Integer.numberOfTrailingZeros(mask);
                            mask &= mask - 1;
                            int x = (chunkX << 4) + localX;
                            int edges = edgesYZ + edge(x, minX, maxX);
                            if (edges == 3) continue;
                            BlockState state = section.getBlockState(localX, y & 15, z & 15);
                            add(state, x, y, z, edges);
                        }
                    }
                }
            }
        }

        private ChunkAccess chunk(int x, int z) {
            int index = (z - minChunkZ) * chunkWidth + x - minChunkX;
            if (!visited[index]) {
                // The server's collision getter is non-loading; a missing chunk stays absent in this scan.
                chunks[index] = (ChunkAccess) level.getChunkForCollisions(x, z);
                visited[index] = true;
            }
            return chunks[index];
        }

        private void add(BlockState state, int x, int y, int z, int edges) {
            if (edges == 1 && !state.hasLargeCollisionShape()) return;
            if (edges == 2 && !state.is(Blocks.MOVING_PISTON)) return;
            pos.set(x, y, z);
            VoxelShape shape = context.getCollisionShape(state, level, pos);
            if (shape == Shapes.block()) {
                if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                    result.add(shape.move(x, y, z));
                }
            } else {
                VoxelShape moved = shape.move(x, y, z);
                if (!moved.isEmpty() && Shapes.joinIsNotEmpty(moved, boxShape, BooleanOp.AND)) result.add(moved);
            }
        }

        private static int edge(int value, int min, int max) {
            return value == min || value == max ? 1 : 0;
        }
    }
}

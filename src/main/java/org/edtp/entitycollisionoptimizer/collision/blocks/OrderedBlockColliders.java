package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.NativeShapeBatch;

import java.util.List;

/** Preserves vanilla's z/y/x traversal, halo tests, shape context and collider order. */
public final class OrderedBlockColliders {
    private OrderedBlockColliders() {}

    /** A live support shape, not a cached result; missing chunks do not get loaded by this probe. */
    static boolean collectSupport(Entity entity, AABB bounds, AABB scan, double requestedY, NativeShapeBatch result) {
        Level level = entity.level();
        BlockPos pos = entity.mainSupportingBlockPos.orElseGet(() -> BlockPos.containing(
                bounds.minX + (bounds.maxX - bounds.minX) / 2, bounds.minY,
                bounds.minZ + (bounds.maxZ - bounds.minZ) / 2));
        if (level.isOutsideBuildHeight(pos.getY())) return false;
        var chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return false;
        VoxelShape shape = CollisionContext.of(entity).getCollisionShape(chunk.getBlockState(pos), level, pos);
        // In particular, do not snap sub-1e-7 movement against a shape outside the scan.
        if (shape.isEmpty() || (requestedY > -1e-7 && !Shapes.joinIsNotEmpty(
                shape.move(pos.getX(), pos.getY(), pos.getZ()), Shapes.create(scan), BooleanOp.AND))) return false;
        result.addTranslated(shape, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    public static void collectNative(Level level, CollisionContext context, Entity entity,
                                     AABB box, List<VoxelShape> entityShapes, NativeShapeBatch result) {
        for (VoxelShape shape : entityShapes) result.add(shape);
        collectWorld(level, context, entity, box, result);
    }

    public static void collectNative(Level level, CollisionContext context, Entity entity,
                                     AABB box, int[] hardIds, NativeShapeBatch result) {
        CollisionFrame.addHardCubes(entity, hardIds, result);
        collectWorld(level, context, entity, box, result);
    }

    private static void collectWorld(Level level, CollisionContext context, Entity entity,
                                     AABB box, NativeShapeBatch result) {
        var border = level.getWorldBorder();
        if (entity != null && border.isInsideCloseToBorder(entity, box)) result.add(border.getCollisionShape());
        new Scan(level, context, box, result).run();
    }

    private static final class Scan {
        private final Level level;
        private final CollisionContext context;
        private final AABB box;
        private final VoxelShape boxShape;
        private final NativeShapeBatch result;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        private Scan(Level level, CollisionContext context, AABB box, NativeShapeBatch result) {
            this.level = level;
            this.context = context;
            this.box = box;
            this.boxShape = Shapes.create(box);
            this.result = result;
        }

        private void run() {
            try (BlockMaskScan scan = new BlockMaskScan(level, box)) {
                while (scan.next()) {
                    int x = scan.x(), y = scan.y(), z = scan.z();
                    BlockState state = scan.section().getBlockState(x & 15, y & 15, z & 15);
                    add(state, x, y, z);
                }
            }
        }

        private void add(BlockState state, int x, int y, int z) {
            pos.set(x, y, z);
            VoxelShape shape = context.getCollisionShape(state, level, pos);
            if (shape == Shapes.block()) {
                if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                    result.addTranslated(shape, x, y, z);
                }
            } else {
                VoxelShape moved = shape.move(x, y, z);
                if (!moved.isEmpty() && Shapes.joinIsNotEmpty(moved, boxShape, BooleanOp.AND)) result.addTranslated(shape, x, y, z);
            }
        }

    }
}

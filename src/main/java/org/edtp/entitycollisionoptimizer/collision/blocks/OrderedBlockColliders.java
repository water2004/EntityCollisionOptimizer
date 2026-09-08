package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.NativeShapeBatch;
import org.edtp.entitycollisionoptimizer.natives.NativeBlockScan;

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
        new Scan(level, context, box, (shape, x, y, z) -> result.add(shape.move(x, y, z))).run();
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
        new Scan(level, context, box, result::addTranslated).run();
    }

    @FunctionalInterface
    private interface ColliderSink { void add(VoxelShape shape, double x, double y, double z); }

    private static final class Scan {
        private final Level level;
        private final CollisionContext context;
        private final AABB box;
        private final VoxelShape boxShape;
        private final ColliderSink result;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        private Scan(Level level, CollisionContext context, AABB box, ColliderSink result) {
            this.level = level;
            this.context = context;
            this.box = box;
            this.boxShape = Shapes.create(box);
            this.result = result;
        }

        private void run() {
            try (NativeBlockScan scan = new NativeBlockScan(level, box)) {
                for (int count; (count = scan.next()) != 0;) for (int i = 0; i < count; i++) {
                    int x = scan.coordinate(i, 0), y = scan.coordinate(i, 1), z = scan.coordinate(i, 2);
                    BlockState state = scan.section(i).getBlockState(x & 15, y & 15, z & 15);
                    add(state, x, y, z);
                }
            }
        }

        private void add(BlockState state, int x, int y, int z) {
            pos.set(x, y, z);
            VoxelShape shape = context.getCollisionShape(state, level, pos);
            if (shape == Shapes.block()) {
                if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                    result.add(shape, x, y, z);
                }
            } else {
                VoxelShape moved = shape.move(x, y, z);
                if (!moved.isEmpty() && Shapes.joinIsNotEmpty(moved, boxShape, BooleanOp.AND)) result.add(shape, x, y, z);
            }
        }

    }
}

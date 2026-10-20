package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.OrderedBlockColliders;
import org.edtp.entitycollisionoptimizer.collision.blocks.MovementColliders;

import java.util.ArrayList;
import java.util.List;

/** The production scan's output, compared with vanilla's independent block iterator. */
public final class BlockShapeCapture {
    private static final ThreadLocal<List<VoxelShape>> CAPTURE = new ThreadLocal<>();
    private BlockShapeCapture() {}

    public static void record(VoxelShape shape) {
        List<VoxelShape> shapes = CAPTURE.get();
        if (shapes != null) shapes.add(shape);
    }

    public static List<VoxelShape> collect(Level level, CollisionContext context, AABB box) {
        List<VoxelShape> shapes = new ArrayList<>();
        List<VoxelShape> previous = CAPTURE.get();
        CAPTURE.set(shapes);
        try {
            OrderedBlockColliders.collect(level, context, null, box, List.of(), new MovementColliders());
            return shapes;
        } finally {
            if (previous == null) CAPTURE.remove();
            else CAPTURE.set(previous);
        }
    }
}

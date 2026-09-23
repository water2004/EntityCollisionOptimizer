package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.OrderedBlockColliders;
import org.edtp.entitycollisionoptimizer.natives.NativeShapeBatch;

import java.util.ArrayList;
import java.util.List;

/** Test-only observation of the shapes emitted by the production block scan. */
public final class BlockShapeCapture {
    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    private BlockShapeCapture() {}

    public static List<VoxelShape> collect(Level level, CollisionContext context, AABB box) {
        List<VoxelShape> shapes = new ArrayList<>();
        try (NativeShapeBatch batch = new NativeShapeBatch()) {
            if (CURRENT.get() != null) throw new IllegalStateException("Nested block shape capture");
            CURRENT.set(new Capture(batch, shapes));
            try {
                OrderedBlockColliders.collectNative(level, context, null, box, List.of(), batch);
            } finally {
                CURRENT.remove();
            }
        }
        return shapes;
    }

    public static void record(NativeShapeBatch batch, VoxelShape shape, double x, double y, double z) {
        Capture capture = CURRENT.get();
        if (capture != null && capture.batch == batch) capture.shapes.add(shape.move(x, y, z));
    }

    private record Capture(NativeShapeBatch batch, List<VoxelShape> shapes) {}
}

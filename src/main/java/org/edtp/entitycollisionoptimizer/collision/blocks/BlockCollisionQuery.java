package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Block-only existence query: no collider list, ordering buffer or movement body. */
public final class BlockCollisionQuery {
    private BlockCollisionQuery() {}

    public static boolean intersects(Level level, CollisionContext context, AABB box) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        VoxelShape boxShape = null;
        try (BlockMaskScan scan = new BlockMaskScan(level, box)) {
            while (scan.next()) {
                int x = scan.x(), y = scan.y(), z = scan.z();
                var state = scan.section().getBlockState(x & 15, y & 15, z & 15);
                pos.set(x, y, z);
                // Shapes remain live and context-dependent; only state-only candidate masks are shared.
                VoxelShape shape = context.getCollisionShape(state, level, pos);
                if (shape == Shapes.block()) {
                    if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) return true;
                } else if (!shape.isEmpty()) {
                    if (boxShape == null) boxShape = Shapes.create(box);
                    if (Shapes.joinIsNotEmpty(shape.move(x, y, z), boxShape, BooleanOp.AND)) return true;
                }
            }
        }
        return false;
    }
}

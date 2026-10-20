package org.edtp.entitycollisionoptimizer.gametest;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityCollisionInvoker;
import org.edtp.entitycollisionoptimizer.gametest.mixin.CubeVoxelShapeInvoker;

import java.util.List;

/** Custom single-cell shapes and internal grid planes, independent of Lithium's shape classes. */
final class SingleCellParity {
    static void verify(GameTestHelper helper) {
        VoxelShape cell = new SingleCell();
        VoxelShape subdivided = CubeVoxelShapeInvoker.eco$create(BitSetDiscreteVoxelShape.withFilledBounds(4, 4, 4, 0, 0, 0, 4, 4, 4));
        MovementClippingParity.compare(helper, subdivided, "internal voxel planes");
        int cases = 0;
        for (int axis = 0; axis < 3; axis++) for (double epsilon : new double[]{
                -Math.nextUp(1e-7), -1e-7, -Math.nextDown(1e-7), -0.0, 0.0,
                Math.nextDown(1e-7), 1e-7, Math.nextUp(1e-7)}) {
            for (boolean shifted : new boolean[]{false, true}) for (double offset : new double[]{0, -16, 29_999_998}) {
                for (int side : new int[]{-1, 1}) {
                    double[] min = {.1, .1, .1}, max = {.4, .4, .4}, delta = {0, -0.0, 0};
                    min[axis] = side < 0 ? -.75 : .5 + epsilon;
                    max[axis] = side < 0 ? epsilon : 1.25;
                    delta[axis] = -side;
                    AABB box = new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
                    if (shifted) box = box.move(offset, offset, offset);
                    Vec3 requested = new Vec3(delta[0], delta[1], delta[2]);
                    VoxelShape expectedShape = shifted ? cell.move(offset, offset, offset) : cell;
                    List<VoxelShape> shapes = List.of(expectedShape, subdivided.move(100, 100, 100));
                    Vec3 expected = EntityCollisionInvoker.eco$collideWithShapes(requested, box, shapes);
                    NativeImpulseParity.exact(helper, MovementClippingParity.clip(requested, box, shapes), expected,
                            "single cell axis=" + axis + " epsilon=" + epsilon + " shifted=" + shifted + " offset=" + offset + " side=" + side);
                    cases++;
                }
            }
        }
        org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer.LOGGER.info(
                "ECO_SINGLE_CELL_PARITY bitwise_cases={} internal_planes=true translated_shapes=true result=passed", cases);
    }

    private static final class SingleCell extends VoxelShape {
        private final DoubleList coordinates = DoubleArrayList.wrap(new double[]{-0.0, .5});
        SingleCell() { super(BitSetDiscreteVoxelShape.withFilledBounds(1, 1, 1, 0, 0, 0, 1, 1, 1)); }
        @Override public DoubleList getCoords(Direction.Axis axis) { return coordinates; }
    }
}

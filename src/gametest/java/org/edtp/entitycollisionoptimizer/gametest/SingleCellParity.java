package org.edtp.entitycollisionoptimizer.gametest;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.NativeVoxelAccess;
import org.edtp.entitycollisionoptimizer.mixin.EntityCollisionInvoker;
import org.edtp.entitycollisionoptimizer.natives.NativeMovement;
import org.edtp.entitycollisionoptimizer.natives.NativeShapeBatch;

import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Simple-cell inequalities and reference encoding, independent of Lithium's shape classes. */
final class SingleCellParity {
    static void verify(GameTestHelper helper) {
        VoxelShape cell = new SingleCell();
        helper.assertTrue((((NativeVoxelAccess) cell).eco$nativeGeometry().get(JAVA_INT, 12) & 4) != 0,
                "simple cell uses interval solver");
        VoxelShape subdivided = new CubeVoxelShape(BitSetDiscreteVoxelShape.withFilledBounds(4, 4, 4, 0, 0, 0, 4, 4, 4));
        helper.assertTrue((((NativeVoxelAccess) subdivided).eco$nativeGeometry().get(JAVA_INT, 12) & 4) == 0,
                "a solid box with internal grid planes must keep its voxel semantics");
        NativeVoxelParity.compare(helper, subdivided, "internal voxel planes");
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
                    try (NativeShapeBatch batch = new NativeShapeBatch();
                         NativeMovement movement = new NativeMovement(null, requested, box, false)) {
                        // Different flags in one packed array; also reuse a slot previously containing offsets.
                        if (shifted) batch.addTranslated(cell, offset, offset, offset);
                        else batch.add(cell);
                        batch.add(subdivided.move(100, 100, 100));
                        movement.solve(batch, false);
                        Vec3 expected = EntityCollisionInvoker.eco$collideWithShapes(requested, box,
                                List.of(expectedShape, subdivided.move(100, 100, 100)));
                        NativeImpulseParity.exact(helper, movement.displacement(), expected,
                                "single cell axis=" + axis + " epsilon=" + epsilon + " shifted=" + shifted + " offset=" + offset + " side=" + side);
                        cases++;
                    }
                }
            }
        }
        org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer.LOGGER.info(
                "ECO_SINGLE_CELL_PARITY bitwise_cases={} internal_planes=true mixed_descriptors=true result=passed", cases);
    }

    private static final class SingleCell extends VoxelShape {
        private final DoubleList coordinates = DoubleArrayList.wrap(new double[]{-0.0, .5});
        SingleCell() { super(BitSetDiscreteVoxelShape.withFilledBounds(1, 1, 1, 0, 0, 0, 1, 1, 1)); }
        @Override public DoubleList getCoords(Direction.Axis axis) { return coordinates; }
    }
}

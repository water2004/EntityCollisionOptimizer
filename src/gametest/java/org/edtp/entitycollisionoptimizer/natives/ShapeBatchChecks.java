package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityCollisionInvoker;

import java.util.ArrayList;
import java.util.List;

/** Vanilla clipping is the oracle for page growth, mixed references and reentrant batch reuse. */
final class ShapeBatchChecks {
    static void verify(GameTestHelper helper) {
        for (int round = 0; round < 3; round++) {
            try (var outer = new NativeShapeBatch()) {
                List<VoxelShape> expected = fill(outer, 74, round * .125);
                check(helper, outer, expected);
                try (var inner = new NativeShapeBatch()) {
                    check(helper, inner, fill(inner, 53, 100 + round));
                }
                check(helper, outer, expected);
                // Reuse the inner storage with fewer cubes while the outer pointers are still live.
                try (var reused = new NativeShapeBatch()) {
                    check(helper, reused, fill(reused, 2, -100 - round));
                }
                check(helper, outer, expected);
            }
        }
    }

    private static List<VoxelShape> fill(NativeShapeBatch batch, int cubes, double shift) {
        List<VoxelShape> expected = new ArrayList<>();
        batch.addTranslated(Shapes.block(), shift + 30, 0, 0);
        expected.add(Shapes.block().move(shift + 30, 0, 0));
        for (int index = 0; index < cubes; index++) {
            double x = shift + 2 + (index % 14) * 1.25;
            double y = (index / 14) * 2;
            double z = (index % 3) * 1.5 - 1;
            AABB bounds = new AABB(x, y, z, x + .75, y + 1.5, z + .625);
            batch.addCube(bounds);
            expected.add(Shapes.create(bounds));
            if (index % 17 == 0) {
                VoxelShape ordinary = Shapes.create(bounds.move(0, 0, 5));
                batch.add(ordinary);
                expected.add(ordinary);
            }
        }
        return expected;
    }

    private static void check(GameTestHelper helper, NativeShapeBatch batch, List<VoxelShape> expectedShapes) {
        helper.assertValueEqual(batch.size(), expectedShapes.size(), "batch reference count after reuse");
        for (VoxelShape collider : expectedShapes) {
            AABB source = collider.bounds().move(-2, 0, 0);
            for (Vec3 requested : new Vec3[]{new Vec3(3, -.25, 0), new Vec3(-3, 2, 0),
                    new Vec3(-0.0, 0, -0.0), new Vec3(1e-8, -1e-7, 0)}) {
                Vec3 expected = EntityCollisionInvoker.eco$collideWithShapes(requested, source, expectedShapes);
                try (var movement = new NativeMovement(null, requested, source, false)) {
                    movement.solve(batch, false);
                    Vec3 actual = movement.displacement();
                    helper.assertTrue(Double.doubleToRawLongBits(actual.x) == Double.doubleToRawLongBits(expected.x)
                                    && Double.doubleToRawLongBits(actual.y) == Double.doubleToRawLongBits(expected.y)
                                    && Double.doubleToRawLongBits(actual.z) == Double.doubleToRawLongBits(expected.z),
                            "batch clipping expected=" + expected + " actual=" + actual);
                }
            }
        }
    }

    private ShapeBatchChecks() {}
}

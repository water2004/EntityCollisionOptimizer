package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.mixin.EntityCollisionInvoker;
import org.edtp.entitycollisionoptimizer.natives.NativeMovement;
import org.edtp.entitycollisionoptimizer.natives.NativeShapeBatch;

import java.util.List;

/** Pure geometry oracle: the real, unreplaced vanilla collideWithShapes, including signed zero. */
final class NativeVoxelParity {
    static int compare(GameTestHelper helper, VoxelShape shape, String label) {
        int count = 0;
        for (double shift : new double[]{0, -16, 29_999_998.0}) {
            var moved = shape.move(shift, 0, shift);
            for (AABB local : List.of(new AABB(-.6, -.1, -.4, -.01, 1.7, .2),
                    new AABB(.2, 1, .2, .8, 2.8, .8), new AABB(.4, .2, .4, .6, .45, .6))) {
                AABB box = local.move(shift, 0, shift);
                try (NativeShapeBatch batch = new NativeShapeBatch()) {
                    batch.addTranslated(shape, shift, 0, shift);
                    for (Vec3 requested : List.of(new Vec3(1.2, -.3, .8), new Vec3(.8, -.3, 1.2),
                            new Vec3(-1.2, .7, -.8), new Vec3(-.8, .7, -1.2),
                            new Vec3(0, -1, 0), new Vec3(1, 0, 0), new Vec3(0, 0, -1),
                            new Vec3(-0.0, 0, -0.0), new Vec3(1e-8, -1e-7, Math.nextUp(1e-7)))) {
                        try (NativeMovement movement = new NativeMovement(null, requested, box, false)) {
                            Vec3 expected = EntityCollisionInvoker.eco$collideWithShapes(requested, box, List.of(moved));
                            movement.solve(batch, false);
                            NativeImpulseParity.exact(helper, movement.displacement(), expected,
                                    "native voxel " + label + " shift=" + shift + " box=" + local + " delta=" + requested);
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    static void edges(GameTestHelper helper) {
        for (VoxelShape shape : List.of(Shapes.empty(), Shapes.block(), Shapes.box(0, 0, 0, .5, .5, .5),
                Shapes.or(Shapes.box(0, 0, 0, 1, .5, 1), Shapes.box(.5, .5, 0, 1, 1, 1)))) {
            compare(helper, shape, "edge fixture");
        }
        for (double epsilon : new double[]{-1e-7, -Math.nextUp(1e-7), -Math.nextDown(1e-7), 0,
                Math.nextDown(1e-7), 1e-7, Math.nextUp(1e-7)}) {
            for (double side : new double[]{-1, 1}) {
                AABB box = new AABB(side < 0 ? -1 : 1 + epsilon, 0, 0,
                        side < 0 ? epsilon : 2, 1, 1);
                try (NativeShapeBatch batch = new NativeShapeBatch()) {
                    batch.add(Shapes.block());
                    batch.add(Shapes.empty());
                    Vec3 requested = new Vec3(-side, -0.0, 0);
                    try (NativeMovement movement = new NativeMovement(null, requested, box, false)) {
                        movement.solve(batch, false);
                        NativeImpulseParity.exact(helper, movement.displacement(),
                                EntityCollisionInvoker.eco$collideWithShapes(requested, box, List.of(Shapes.block(), Shapes.empty())),
                                "native contact epsilon=" + epsilon + " side=" + side);
                    }
                }
            }
        }
        concurrent(helper);
    }

    private static void concurrent(GameTestHelper helper) {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 3; worker++) {
                final int offset = worker * 32;
                futures.add(executor.submit(() -> {
                    for (int iteration = 0; iteration < 1000; iteration++) {
                        var box = new AABB(offset - .75, 0, 0, offset - .25, 1, 1);
                        Vec3 request = new Vec3(.5, -.1, iteration % 2 == 0 ? .2 : -.2);
                        try (NativeShapeBatch outer = new NativeShapeBatch();
                             NativeMovement movement = new NativeMovement(null, request, box, false)) {
                            outer.addTranslated(Shapes.block(), offset, 0, 0);
                            movement.solve(outer, false);
                            Vec3 expected = EntityCollisionInvoker.eco$collideWithShapes(request, box,
                                    List.of(Shapes.block().move(offset, 0, 0)));
                            try (NativeShapeBatch inner = new NativeShapeBatch();
                                 NativeMovement nested = new NativeMovement(null, request.reverse(), box.move(3, 4, 5), false)) {
                                inner.add(Shapes.empty());
                                nested.solve(inner, false);
                            }
                            Vec3 actual = movement.displacement();
                            if (!actual.equals(expected)) throw new AssertionError("Nested/concurrent movement pool corruption");
                            movement.solve(outer, false);
                            if (!movement.displacement().equals(expected)) throw new AssertionError("Nested shape pool corruption");
                        }
                    }
                }));
            }
            for (var future : futures) future.get();
        } catch (Exception failure) { throw new AssertionError("Concurrent native movement", failure); }
        org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer.LOGGER.info(
                "ECO_NATIVE_MOVEMENT_POOL threads=3 nested_transactions=3000 oracle=vanilla result=passed");
    }
}

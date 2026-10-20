package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.MovementColliders;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityCollisionInvoker;

import java.util.List;

/** The unreplaced vanilla clipping primitive is the oracle, including signed zero. */
final class MovementClippingParity {
    static Vec3 clip(Vec3 requested, AABB box, List<VoxelShape> shapes) {
        var colliders = new MovementColliders();
        for (VoxelShape shape : shapes) colliders.add(shape);
        return colliders.clip(requested, box);
    }
    static int compare(GameTestHelper helper, VoxelShape shape, String label) {
        int count = 0;
        for (double shift : new double[]{0, -16, 29_999_998.0}) {
            List<VoxelShape> shapes = List.of(shape.move(shift, 0, shift));
            for (AABB local : List.of(new AABB(-.6, -.1, -.4, -.01, 1.7, .2),
                    new AABB(.2, 1, .2, .8, 2.8, .8), new AABB(.4, .2, .4, .6, .45, .6))) {
                AABB box = local.move(shift, 0, shift);
                for (Vec3 requested : List.of(new Vec3(1.2, -.3, .8), new Vec3(.8, -.3, 1.2),
                        new Vec3(-1.2, .7, -.8), new Vec3(-.8, .7, -1.2),
                        new Vec3(1, -.3, -1), new Vec3(-1, .7, 1),
                        new Vec3(0, -1, 0), new Vec3(1, 0, 0), new Vec3(0, 0, -1),
                        new Vec3(-0.0, 0, -0.0), new Vec3(1e-8, -1e-7, Math.nextUp(1e-7)))) {
                    NativeImpulseParity.exact(helper, clip(requested, box, shapes),
                            EntityCollisionInvoker.eco$collideWithShapes(requested, box, shapes),
                            "Java clipping " + label + " shift=" + shift + " box=" + local + " delta=" + requested);
                    count++;
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
                AABB box = new AABB(side < 0 ? -1 : 1 + epsilon, 0, 0, side < 0 ? epsilon : 2, 1, 1);
                Vec3 requested = new Vec3(-side, -0.0, 0);
                List<VoxelShape> shapes = List.of(Shapes.block(), Shapes.empty());
                NativeImpulseParity.exact(helper, clip(requested, box, shapes),
                        EntityCollisionInvoker.eco$collideWithShapes(requested, box, shapes),
                        "Java contact epsilon=" + epsilon + " side=" + side);
            }
        }
        concurrent(helper);
        primitiveColliders(helper);
    }

    private static void primitiveColliders(GameTestHelper helper) {
        int comparisons = 0;
        for (int offset : new int[]{-29_999_998, -16, 0, 16, 29_999_998}) {
            var colliders = new MovementColliders();
            var expected = new java.util.ArrayList<VoxelShape>();
            // Grow the arrays and mix block, raw bounds and general-shape entries in order.
            for (int index = 0; index < 20; index++) {
                colliders.addBlock(offset + index, 0, offset);
                expected.add(Shapes.block().move(offset + index, 0, offset));
            }
            for (AABB cube : List.of(new AABB(offset - .5, -.5, offset - .5, offset + .5, .5, offset + .5),
                    new AABB(-0.0, -0.0, -0.0, .5, .5, .5),
                    new AABB(0, 0, 0, 1e-8, .5, .5),
                    new AABB(0, .125, 0, .5, .875, .5))) {
                colliders.addBounds(cube);
                expected.add(Shapes.create(cube));
            }
            VoxelShape planes = org.edtp.entitycollisionoptimizer.gametest.mixin.CubeVoxelShapeInvoker.eco$create(
                    net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape.withFilledBounds(4, 4, 4, 0, 0, 0, 4, 4, 4));
            colliders.add(planes);
            expected.add(planes);
            for (double epsilon : new double[]{-Math.nextUp(1e-7), -1e-7, -Math.nextDown(1e-7),
                    -0.0, 0.0, Math.nextDown(1e-7), 1e-7, Math.nextUp(1e-7)}) {
                AABB box = new AABB(offset - .5, .5 + epsilon, offset - .5, offset + .5, 1.5, offset + .5);
                for (Vec3 request : List.of(new Vec3(1, -.2, .5), new Vec3(-1, .2, -.5),
                        new Vec3(epsilon, epsilon, epsilon), new Vec3(-0.0, 0, -0.0))) {
                    NativeImpulseParity.exact(helper, colliders.clip(request, box),
                            EntityCollisionInvoker.eco$collideWithShapes(request, box, expected),
                            "mixed numeric colliders offset=" + offset + " epsilon=" + epsilon);
                    comparisons++;
                }
                for (float clippedY : new float[]{-0.0F, 0.0F, .25F, -.25F}) {
                    float[] actual = colliders.stepHeights(box, 1.5F, clippedY);
                    float[] reference = EntityCollisionInvoker.eco$stepHeights(box, expected, 1.5F, clippedY);
                    helper.assertTrue(java.util.Arrays.equals(actual, reference), "numeric collider step planes");
                    comparisons++;
                }
            }
            colliders.clear();
            colliders.add(Shapes.block().move(offset + 100, 0, offset));
            colliders.clear();
            colliders.addBlock(offset, 0, offset);
            Vec3 request = new Vec3(.25, -.5, -.25);
            AABB box = new AABB(offset, 1, offset, offset + .5, 2, offset + .5);
            NativeImpulseParity.exact(helper, colliders.clip(request, box),
                    EntityCollisionInvoker.eco$collideWithShapes(request, box,
                            List.of(Shapes.block().move(offset, 0, offset))), "clear does not retain old entries");
        }
        org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer.LOGGER.info(
                "ECO_NUMERIC_COLLIDER_PARITY comparisons={} growth=true reuse=true step_planes=true result=passed", comparisons);
    }

    private static void concurrent(GameTestHelper helper) {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 3; worker++) {
                final int offset = worker * 32;
                futures.add(executor.submit(() -> {
                    List<VoxelShape> shapes = List.of(Shapes.block().move(offset, 0, 0));
                    for (int iteration = 0; iteration < 1000; iteration++) {
                        var box = new AABB(offset - .75, 0, 0, offset - .25, 1, 1);
                        Vec3 request = new Vec3(.5, -.1, iteration % 2 == 0 ? .2 : -.2);
                        Vec3 actual = clip(request, box, shapes);
                        Vec3 expected = EntityCollisionInvoker.eco$collideWithShapes(request, box, shapes);
                        if (!actual.equals(expected)) throw new AssertionError("Concurrent clipping differs from vanilla");
                    }
                }));
            }
            for (var future : futures) future.get();
        } catch (Exception failure) { throw new AssertionError("Concurrent Java movement", failure); }
        org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer.LOGGER.info(
                "ECO_JAVA_MOVEMENT_CLIPPING threads=3 comparisons=3000 oracle=vanilla result=passed");
    }
}

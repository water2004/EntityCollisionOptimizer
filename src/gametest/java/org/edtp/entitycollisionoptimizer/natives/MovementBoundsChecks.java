package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

public final class MovementBoundsChecks {
    public static void verify(GameTestHelper helper) {
        var entity = new Zombie(helper.getLevel());
        var box = new AABB(-.25, -0.0, -.25, .25, 1.8, .25);
        entity.setBoundingBox(box);
        try (var table = new CollisionStateTable(); var next = new CollisionStateTable()) {
            table.bindBody(entity);
            helper.assertTrue(entity.getBoundingBox() == box, "binding preserves bounds reference");
            AABB updated = new AABB(-.5, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            entity.setBoundingBox(updated);
            helper.assertTrue(entity.getBoundingBox() == updated, "Java bounds retain reference identity while bound");
            for (Vec3 request : new Vec3[]{new Vec3(-.5, -.1, .3), new Vec3(.5, .1, -.3),
                    new Vec3(-0.0, 0, -0.0), new Vec3(1e-8, -1e-7, 0)}) {
                AABB captured = entity.getBoundingBox();
                try (var movement = new NativeMovement(entity, request, captured, true)) {
                    AABB scan = movement.stepScan();
                    helper.assertTrue(scan.equals(captured.expandTowards(request)), "movement swept bounds");
                    helper.assertTrue(movement.sourceBounds() == captured && movement.stepScan() == scan,
                            "Java bounds are reused before solving");
                    // Movement keeps its captured geometry despite callbacks changing the entity.
                    entity.setBoundingBox(captured.move(4, 0, 0));
                    for (int i = 0; i < 300; i++) table.bindBody(new Zombie(helper.getLevel()));
                    try (var shapes = new NativeShapeBatch()) {
                        shapes.add(net.minecraft.world.phys.shapes.Shapes.block());
                        movement.solve(shapes, false);
                        Vec3 expected = request.lengthSqr() == 0 ? request : org.edtp.entitycollisionoptimizer.gametest.mixin.EntityCollisionInvoker
                                .eco$collideWithShapes(request, captured, java.util.List.of(net.minecraft.world.phys.shapes.Shapes.block()));
                        helper.assertTrue(movement.displacement().equals(expected), "captured geometry request=" + request
                                + " expected=" + expected + " actual=" + movement.displacement());
                    }
                    entity.setBoundingBox(captured);
                }
            }
            AABB current = entity.getBoundingBox();
            next.bindBody(entity);
            table.clear();
            helper.assertTrue(entity.getBoundingBox() == current, "bounds transfer retains latest value");
            next.clear();
            helper.assertTrue(entity.getBoundingBox() == current, "body detach leaves Java bounds untouched");
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_MOVEMENT_BOUNDS authority=true snapshots=true growth=true transfer=true result=passed");
        ShapeBatchChecks.verify(helper);
    }
}

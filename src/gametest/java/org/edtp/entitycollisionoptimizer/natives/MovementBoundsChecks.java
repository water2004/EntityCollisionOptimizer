package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import static java.lang.foreign.ValueLayout.*;

public final class MovementBoundsChecks {
    public static void verify(GameTestHelper helper) {
        var entity = new Zombie(helper.getLevel());
        var box = new AABB(-.25, -0.0, -.25, .25, 1.8, .25);
        entity.setBoundingBox(box);
        try (var table = new CollisionStateTable(); var next = new CollisionStateTable()) {
            int slot = table.slot(entity);
            helper.assertTrue(entity.getBoundingBox() == box, "binding preserves bounds reference");
            var row = table.movementRow(slot);
            row.set(JAVA_DOUBLE, 0, -.5);
            row.set(JAVA_LONG, 48, row.get(JAVA_LONG, 48) + 1);
            helper.assertTrue(entity.getBoundingBox().minX == -.5, "shared bounds are authoritative");
            for (Vec3 request : new Vec3[]{new Vec3(-.5, -.1, .3), new Vec3(.5, .1, -.3),
                    new Vec3(-0.0, 0, -0.0), new Vec3(1e-8, -1e-7, 0)}) {
                AABB captured = entity.getBoundingBox();
                try (var movement = new NativeMovement(entity, request, captured, true)) {
                    helper.assertTrue(movement.stepScan().equals(captured.expandTowards(request)), "native swept bounds");
                    // Movement keeps its original geometry despite callbacks changing the persistent row.
                    entity.setBoundingBox(captured.move(4, 0, 0));
                    for (int i = 0; i < 300; i++) table.slot(new Zombie(helper.getLevel()));
                    try (var shapes = new NativeShapeBatch()) {
                        shapes.add(net.minecraft.world.phys.shapes.Shapes.block());
                        movement.solve(shapes, false);
                        Vec3 expected = request.lengthSqr() == 0 ? request : org.edtp.entitycollisionoptimizer.mixin.EntityCollisionInvoker
                                .eco$collideWithShapes(request, captured, java.util.List.of(net.minecraft.world.phys.shapes.Shapes.block()));
                        helper.assertTrue(movement.displacement().equals(expected), "captured geometry request=" + request
                                + " expected=" + expected + " actual=" + movement.displacement());
                    }
                    entity.setBoundingBox(captured);
                }
            }
            AABB current = entity.getBoundingBox();
            next.slot(entity);
            table.clear();
            helper.assertTrue(entity.getBoundingBox() == current, "bounds transfer retains latest value");
            next.clear();
            helper.assertTrue(entity.getBoundingBox() == current, "bounds detach materializes latest value");
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_MOVEMENT_BOUNDS authority=true snapshots=true growth=true transfer=true result=passed");
    }
}

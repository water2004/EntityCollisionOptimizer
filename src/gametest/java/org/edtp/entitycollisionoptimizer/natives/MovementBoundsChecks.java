package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityCollisionInvoker;

import java.util.ArrayList;
import java.util.List;

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
                // Explicit-box movement keeps its captured geometry, not the entity's later bounds.
                entity.setBoundingBox(captured.move(4, 0, 0));
                for (int i = 0; i < 300; i++) table.bindBody(new Zombie(helper.getLevel()));
                var shapes = List.of(Shapes.block());
                var expectedShapes = new ArrayList<>(shapes);
                // collideBox also scans the world; the origin need not be empty in a mod stack.
                new BlockCollisions<>(helper.getLevel(), CollisionContext.empty(),
                        captured.expandTowards(request), false, (pos, shape) -> shape)
                        .forEachRemaining(expectedShapes::add);
                Vec3 expected = EntityCollisionInvoker.eco$collideWithShapes(request, captured, expectedShapes);
                Vec3 actual = EntityMovementCollision.collideBox(helper.getLevel(),
                        CollisionContext.empty(), null, request, captured, shapes);
                helper.assertTrue(actual.equals(expected), "captured geometry request=" + request
                        + " expected=" + expected + " actual=" + actual);
                entity.setBoundingBox(captured);
            }
            AABB current = entity.getBoundingBox();
            next.bindBody(entity);
            table.clear();
            helper.assertTrue(entity.getBoundingBox() == current, "bounds transfer retains latest value");
            next.clear();
            helper.assertTrue(entity.getBoundingBox() == current, "body detach leaves Java bounds untouched");
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_MOVEMENT_BOUNDS authority=true snapshots=true growth=true transfer=true result=passed");
    }
}

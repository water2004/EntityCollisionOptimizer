package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;

/** 1.21.11 entity colliders come from the unexpanded movement query, even for the step attempt. */
final class StepEntityCollisionParity {
    static void verify(GameTestHelper helper) {
        try (var scene = new InteractionScene(helper)) {
            var source = scene.spawn(EntityType.ZOMBIE, new Vec3(3.5, 1, 3.5));
            scene.block(4, 1, 3, Blocks.STONE_SLAB);
            source.setOnGround(true);
            Vec3 request = new Vec3(1, 0, 0);
            Vec3 clear = EntityMovementCollision.collide(source, request);
            CollisionTestSupport.assertVectorEqual(helper, clear, new Vec3(1, 0.5, 0), "unobstructed slab step");

            // Entity.collide queries entity colliders with boundingBox.expandTowards(movement) and
            // reuses that list for the step attempt, so a collider entirely above the query box is
            // invisible to the step even though it is within maxUpStep of the entity.
            var ceiling = scene.spawn(EntityType.OAK_BOAT, new Vec3(3.5, 3.1, 3.5));
            helper.assertTrue(ceiling.getBoundingBox().minY > source.getBoundingBox().maxY,
                    "hard collider must be above the movement query");
            Vec3 stepped = EntityMovementCollision.collide(source, request);
            CollisionTestSupport.assertVectorEqual(helper, stepped, clear,
                    "collider above the movement query must not block the step");

            // A collider inside the movement query still participates: a boat beside the slab and
            // inside the query box blocks every candidate step height.
            var blocker = scene.spawn(EntityType.OAK_BOAT, new Vec3(4.5, 2.0, 3.5));
            helper.assertTrue(blocker.getBoundingBox().intersects(source.getBoundingBox().expandTowards(request)),
                    "blocking collider must intersect the movement query");
            Vec3 blocked = EntityMovementCollision.collide(source, request);
            helper.assertTrue(blocked.horizontalDistanceSqr() < clear.horizontalDistanceSqr(),
                    "collider inside the movement query must block the step");
        }
    }
}

package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;

/** An elevated hard collider must participate in the 26.3 step attempt. */
final class StepEntityCollisionParity {
    static void verify(GameTestHelper helper) {
        try (var scene = new InteractionScene(helper)) {
            var source = scene.spawn(EntityTypes.ZOMBIE, new Vec3(3.5, 1, 3.5));
            scene.block(4, 1, 3, Blocks.STONE_SLAB);
            source.setOnGround(true);
            Vec3 request = new Vec3(1, 0, 0);
            Vec3 clear = EntityMovementCollision.collide(source, request);
            CollisionTestSupport.assertVectorEqual(helper, clear, new Vec3(1, 0.5, 0), "unobstructed slab step");

            var ceiling = scene.spawn(EntityTypes.OAK_BOAT, new Vec3(3.5, 3.1, 3.5));
            helper.assertTrue(ceiling.getBoundingBox().minY > source.getBoundingBox().maxY,
                    "hard collider must be above the unexpanded movement query");
            Vec3 blocked = EntityMovementCollision.collide(source, request);
            double gap = helper.absoluteVec(new Vec3(4, 1, 3)).x - source.getBoundingBox().maxX;
            CollisionTestSupport.assertVectorEqual(helper, blocked, new Vec3(gap, 0, 0), "boat prevents slab step");
        }
    }
}

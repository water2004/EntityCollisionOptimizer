package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.mixin.EntityCollisionInvoker;

import java.util.List;

/** Owns the movement entry point; both movement and stepping use the same ordered collector. */
public final class EntityMovementCollision {
    private EntityMovementCollision() {}

    public static Vec3 collide(Entity entity, Vec3 requested) {
        AABB box = entity.getBoundingBox();
        List<VoxelShape> entities = entity.level().getEntityCollisions(entity, box.expandTowards(requested));
        Vec3 clipped = requested.lengthSqr() == 0.0 ? requested : EntityCollisionInvoker.eco$collideWithShapes(
                requested, box, OrderedBlockColliders.collect(entity, box.expandTowards(requested), entities));
        boolean landed = requested.y != clipped.y && requested.y < 0.0;
        if (entity.maxUpStep() <= 0.0F || !(landed || entity.onGround())
                || (requested.x == clipped.x && requested.z == clipped.z)) return clipped;

        AABB stepBase = landed ? box.move(0.0, clipped.y, 0.0) : box;
        AABB stepScan = stepBase.expandTowards(requested.x, entity.maxUpStep(), requested.z);
        if (!landed) stepScan = stepScan.expandTowards(0.0, -9.999999747378752E-6, 0.0);
        List<VoxelShape> stepShapes = OrderedBlockColliders.collect(entity, stepScan, entities);
        float[] heights = EntityCollisionInvoker.eco$stepHeights(
                stepBase, stepShapes, entity.maxUpStep(), (float) clipped.y);
        for (float height : heights) {
            Vec3 stepped = EntityCollisionInvoker.eco$collideWithShapes(
                    new Vec3(requested.x, height, requested.z), stepBase, stepShapes);
            if (stepped.horizontalDistanceSqr() > clipped.horizontalDistanceSqr()) {
                return stepped.subtract(0.0, box.minY - stepBase.minY, 0.0);
            }
        }
        return clipped;
    }
}

package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.List;

/** Live Java shapes and vanilla clipping; only hard-entity lookup uses the native index. */
public final class EntityMovementCollision {
    private EntityMovementCollision() {}

    public static Vec3 collide(Entity entity, Vec3 requested) {
        Level level = entity.level();
        AABB bounds = entity.getBoundingBox();
        AABB scan = bounds.expandTowards(requested);
        // A fully blocked vertical fall needs neither entity candidates nor a step attempt.
        if (requested.y < 0.0 && requested.x == 0.0 && requested.z == 0.0) {
            VoxelShape support = OrderedBlockColliders.support(entity, bounds, scan, requested.y);
            if (support != null && requested.lengthSqr() != 0.0
                    && support.collide(Direction.Axis.Y, bounds.move(Vec3.ZERO), requested.y) == 0.0) {
                return Vec3.ZERO;
            }
        }
        // Since 26.3, the initial entity query also covers the possible upward step.
        int[] hardIds = CollisionFrame.hardCollisionIds(entity, scan.expandTowards(0.0, entity.maxUpStep(), 0.0));
        // Capture eligibility after entity predicates, before block-shape callbacks.
        float maxStep = entity.maxUpStep();
        boolean grounded = entity.onGround();
        if (requested.lengthSqr() == 0.0) return requested;
        MovementColliders shapes = new MovementColliders();
        OrderedBlockColliders.collect(level, CollisionContext.of(entity), entity, scan, hardIds, shapes);
        Vec3 clipped = shapes.clip(requested, bounds);
        boolean landed = requested.y != clipped.y && requested.y < 0.0;
        if (maxStep > 0.0F && (landed || grounded)
                && (requested.x != clipped.x || requested.z != clipped.z)) {
            return step(entity, requested, bounds, clipped, hardIds, maxStep, landed, shapes);
        }
        return clipped;
    }

    private static Vec3 step(Entity entity, Vec3 requested, AABB bounds, Vec3 clipped,
            int[] hardIds, float maxStep, boolean landed, MovementColliders shapes) {
        AABB base = landed ? bounds.move(0.0, clipped.y, 0.0) : bounds;
        AABB scan = base.expandTowards(requested.x, maxStep, requested.z);
        if (!landed) scan = scan.expandTowards(0.0, -1.0E-5F, 0.0);
        shapes.clear();
        OrderedBlockColliders.collect(entity.level(), CollisionContext.of(entity), entity, scan, hardIds, shapes);
        for (float height : shapes.stepHeights(base, maxStep, (float) clipped.y)) {
            Vec3 stepped = shapes.clip(new Vec3(requested.x, height, requested.z), base);
            if (stepped.horizontalDistanceSqr() > clipped.horizontalDistanceSqr()) {
                return stepped.subtract(0.0, bounds.minY - base.minY, 0.0);
            }
        }
        return clipped;
    }

    public static Vec3 collideBox(Level level, CollisionContext context, Entity entity,
                                  Vec3 requested, AABB box, List<VoxelShape> entities) {
        MovementColliders shapes = new MovementColliders();
        OrderedBlockColliders.collect(level, context, entity, box.expandTowards(requested), entities, shapes);
        return shapes.clip(requested, box);
    }
}

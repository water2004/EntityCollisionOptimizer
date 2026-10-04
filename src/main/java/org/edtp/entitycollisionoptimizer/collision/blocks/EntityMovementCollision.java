package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.edtp.entitycollisionoptimizer.OptimizerSwitches;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.NativeMovement;
import org.edtp.entitycollisionoptimizer.natives.NativeShapeBatch;

import java.util.List;

/** Java supplies context-dependent shapes; native owns clipping, stepping and integration. */
public final class EntityMovementCollision {
    private EntityMovementCollision() {}

    public static Vec3 collide(Entity entity, Vec3 requested) {
        try (NativeMovement movement = solve(entity, requested)) { return movement.displacement(); }
    }

    /** Caller owns the transaction through movement publication, including exceptional exits. */
    public static NativeMovement solve(Entity entity, Vec3 requested) {
        var level = entity.level();
        NativeMovement movement = new NativeMovement(entity, requested, null, true);
        try {
            AABB scan = movement.stepScan();
            // 1.21.11's Entity.collide queries entity colliders once with
            // boundingBox.expandTowards(movement) and reuses that list for the step attempt; only
            // block colliders are re-queried with the step box. Widening this query by maxUpStep
            // would hand the step solve colliders vanilla never considers (the 26.3 rule), pinning
            // a mob against a step below any hard collider such as a boat, shulker or ghast.
            EntityColliders colliders = OptimizerSwitches.index()
                    ? EntityColliders.nativeQuery(entity, scan)
                    : EntityColliders.vanillaQuery(level, entity, scan);
            movement.steppingState();
            try (NativeShapeBatch shapes = new NativeShapeBatch()) {
                if (requested.lengthSqr() != 0.0) {
                    colliders.collect(level, entity, scan, shapes);
                }
                movement.solve(shapes, false);
            }
            if (movement.needsStep()) collectStep(level, entity, colliders, movement);
            return movement;
        } catch (RuntimeException failure) {
            movement.close();
            throw failure;
        }
    }

    private static void collectStep(Level level, Entity entity, EntityColliders colliders, NativeMovement movement) {
        try (NativeShapeBatch shapes = new NativeShapeBatch()) {
            colliders.collect(level, entity, movement.stepScan(), shapes);
            movement.solve(shapes, true);
        }
    }

    /** One movement's entity colliders: native index ids or an already-materialized vanilla list. */
    private interface EntityColliders {
        void collect(Level level, Entity entity, AABB scan, NativeShapeBatch shapes);

        static EntityColliders nativeQuery(Entity entity, AABB entityScan) {
            int[] hardIds = CollisionFrame.hardCollisionIds(entity, entityScan);
            return (level, owner, scan, shapes) ->
                    OrderedBlockColliders.collectNative(level, CollisionContext.of(owner), owner, scan, hardIds, shapes);
        }

        static EntityColliders vanillaQuery(Level level, Entity entity, AABB entityScan) {
            List<VoxelShape> shapes = level.getEntityCollisions(entity, entityScan);
            return (ignored, owner, scan, batch) ->
                    OrderedBlockColliders.collectNative(ignored, CollisionContext.of(owner), owner, scan, shapes, batch);
        }
    }

    public static Vec3 collideBox(Level level, CollisionContext context, Entity entity,
                                  Vec3 requested, AABB box, List<VoxelShape> entities) {
        try (NativeMovement movement = new NativeMovement(entity, requested, box, false);
             NativeShapeBatch shapes = new NativeShapeBatch()) {
            OrderedBlockColliders.collectNative(level, context, entity, movement.stepScan(), entities, shapes);
            movement.solve(shapes, false);
            return movement.displacement();
        }
    }
}

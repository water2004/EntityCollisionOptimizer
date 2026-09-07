package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;
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
            List<VoxelShape> entities = level.getEntityCollisions(entity, scan);
            movement.steppingState();
            try (NativeShapeBatch shapes = new NativeShapeBatch()) {
                if (requested.lengthSqr() != 0.0) {
                    OrderedBlockColliders.collectNative(level, CollisionContext.of(entity), entity,
                            scan, entities, shapes);
                }
                movement.solve(shapes, false);
            }
            if (movement.needsStep()) collectStep(entity, entities, movement);
            return movement;
        } catch (RuntimeException | Error failure) {
            movement.close();
            throw failure;
        }
    }

    private static void collectStep(Entity entity, List<VoxelShape> entities, NativeMovement movement) {
        try (NativeShapeBatch shapes = new NativeShapeBatch()) {
            OrderedBlockColliders.collectNative(entity.level(), CollisionContext.of(entity), entity,
                    movement.stepScan(), entities, shapes);
            movement.solve(shapes, true);
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

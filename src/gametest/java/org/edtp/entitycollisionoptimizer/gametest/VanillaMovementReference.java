package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityCollisionInvoker;

import java.util.ArrayList;
import java.util.List;

/** Vanilla Entity.collide assembly, using raw sections/block iteration and unreplaced geometry primitives. */
final class VanillaMovementReference {
    static Vec3 collide(Entity entity, Vec3 requested) {
        AABB bounds = entity.getBoundingBox();
        AABB scan = bounds.expandTowards(requested);
        List<VoxelShape> entities = new ArrayList<>();
        for (Entity target : VanillaEntityQueries.call(() -> entity.level().getEntities(entity, scan.inflate(1.0E-7)))) {
            if (!target.isSpectator() && entity.canCollideWith(target)) {
                entities.add(Shapes.create(target.getBoundingBox()));
            }
        }
        Vec3 clipped = requested.lengthSqr() == 0.0 ? requested
                : EntityCollisionInvoker.eco$collideWithShapes(requested, bounds, collect(entity, scan, entities, true));
        boolean landed = requested.y != clipped.y && requested.y < 0.0;
        if (entity.maxUpStep() > 0.0F && (landed || entity.onGround())
                && (requested.x != clipped.x || requested.z != clipped.z)) {
            AABB base = landed ? bounds.move(0.0, clipped.y, 0.0) : bounds;
            AABB stepScan = base.expandTowards(requested.x, entity.maxUpStep(), requested.z);
            if (!landed) stepScan = stepScan.expandTowards(0.0, -1.0E-5F, 0.0);
            List<VoxelShape> shapes = collect(entity, stepScan, entities, false);
            for (float height : EntityCollisionInvoker.eco$stepHeights(base, shapes, entity.maxUpStep(), (float) clipped.y)) {
                Vec3 stepped = EntityCollisionInvoker.eco$collideWithShapes(
                        new Vec3(requested.x, height, requested.z), base, shapes);
                if (stepped.horizontalDistanceSqr() > clipped.horizontalDistanceSqr()) {
                    return stepped.subtract(0.0, bounds.minY - base.minY, 0.0);
                }
            }
        }
        return clipped;
    }

    private static List<VoxelShape> collect(Entity entity, AABB scan, List<VoxelShape> entities, boolean includeBorder) {
        List<VoxelShape> shapes = new ArrayList<>(entities);
        var border = entity.level().getWorldBorder();
        if (includeBorder && border.isInsideCloseToBorder(entity, scan)) shapes.add(border.getCollisionShape());
        new BlockCollisions<>(entity.level(), CollisionContext.of(entity), scan, false,
                (pos, shape) -> shape).forEachRemaining(shapes::add);
        return shapes;
    }

    private VanillaMovementReference() {}
}

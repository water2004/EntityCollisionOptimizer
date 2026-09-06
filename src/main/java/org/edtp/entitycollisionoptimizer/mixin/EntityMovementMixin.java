package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;
import org.edtp.entitycollisionoptimizer.collision.blocks.OrderedBlockColliders;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = Entity.class, priority = 1100)
public abstract class EntityMovementMixin {
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void eco$ownMovement(Vec3 requested, CallbackInfoReturnable<Vec3> cir) {
        Entity entity = (Entity) (Object) this;
        if (CollisionOptimizerConfig.enableEntityCollision && entity.level() instanceof ServerLevel) {
            cir.setReturnValue(EntityMovementCollision.collide(entity, requested));
        }
    }

    @Inject(method = "collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private static void eco$ownEntityBox(Entity entity, Vec3 requested, AABB box, Level level,
                                         List<VoxelShape> entities, CallbackInfoReturnable<Vec3> cir) {
        if (CollisionOptimizerConfig.enableEntityCollision && level instanceof ServerLevel) {
            CollisionContext context = entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
            cir.setReturnValue(EntityCollisionInvoker.eco$collideWithShapes(requested, box,
                    OrderedBlockColliders.collect(level, context, entity, box.expandTowards(requested), entities)));
        }
    }

    @Inject(method = "collideBoundingBox(Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private static void eco$ownContextBox(CollisionContext context, Vec3 requested, AABB box, Level level,
                                          List<VoxelShape> entities, CallbackInfoReturnable<Vec3> cir) {
        if (CollisionOptimizerConfig.enableEntityCollision && level instanceof ServerLevel) {
            cir.setReturnValue(EntityCollisionInvoker.eco$collideWithShapes(requested, box,
                    OrderedBlockColliders.collect(level, context, null, box.expandTowards(requested), entities)));
        }
    }
}

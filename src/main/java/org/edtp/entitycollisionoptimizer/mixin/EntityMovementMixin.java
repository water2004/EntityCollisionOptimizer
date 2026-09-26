package org.edtp.entitycollisionoptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;
import org.edtp.entitycollisionoptimizer.natives.NativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = Entity.class, priority = 1100)
public abstract class EntityMovementMixin {
    @WrapMethod(method = "move")
    private void eco$movementLifetime(MoverType type, Vec3 requested, Operation<Void> original,
                                       @Share("eco$movement") LocalRef<NativeMovement> transaction) {
        try {
            original.call(type, requested);
        } finally {
            NativeMovement movement = transaction.get();
            if (movement != null) movement.close();
        }
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 eco$solveMovement(Entity entity, Vec3 requested, Operation<Vec3> original,
                                   @Share("eco$movement") LocalRef<NativeMovement> transaction) {
        if (!(entity.level() instanceof ServerLevel)) {
            return original.call(entity, requested);
        }
        var result = EntityMovementCollision.solve(entity, requested);
        transaction.set(result);
        return result.displacement();
    }

    // 1.21.1 publishes the collided destination with scalar setPos, after the no-physics branch.
    @WrapOperation(method = "move", at = @At(value = "INVOKE", ordinal = 1, target =
            "Lnet/minecraft/world/entity/Entity;setPos(DDD)V"))
    private void eco$movementDestination(Entity entity, double x, double y, double z, Operation<Void> original,
                                         @Share("eco$movement") LocalRef<NativeMovement> transaction) {
        var result = transaction.get();
        if (result == null) {
            original.call(entity, x, y, z);
        } else {
            Vec3 destination = result.destination(entity.position());
            original.call(entity, destination.x, destination.y, destination.z);
        }
    }

    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void eco$ownMovement(Vec3 requested, CallbackInfoReturnable<Vec3> cir) {
        Entity entity = (Entity) (Object) this;
        if (entity.level() instanceof ServerLevel) {
            cir.setReturnValue(EntityMovementCollision.collide(entity, requested));
        }
    }

    @Inject(method = "collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private static void eco$ownEntityBox(Entity entity, Vec3 requested, AABB box, Level level,
                                         List<VoxelShape> entities, CallbackInfoReturnable<Vec3> cir) {
        if (level instanceof ServerLevel) {
            CollisionContext context = entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
            cir.setReturnValue(EntityMovementCollision.collideBox(level, context, entity, requested, box, entities));
        }
    }

}

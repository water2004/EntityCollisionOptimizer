package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = CommonLevelAccessor.class, priority = 1100)
public interface CommonLevelAccessorMixin {
    @Inject(
            method = "getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void entityCollisionOptimizer$getEntityCollisions(
            Entity entity,
            AABB box,
            CallbackInfoReturnable<List<VoxelShape>> cir
    ) {
        if ((Object) this instanceof ServerLevel level) {
            cir.setReturnValue(CollisionFrame.getEntityCollisions(level, entity, box));
        }
    }
}

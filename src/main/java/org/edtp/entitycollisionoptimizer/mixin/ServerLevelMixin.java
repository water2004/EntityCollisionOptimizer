package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"
            )
    )
    private void entityCollisionOptimizer$beginCollisionFrame(
            BooleanSupplier shouldKeepTicking,
            CallbackInfo ci
    ) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CollisionOptimizerConfig.enableEntityCollision) {
            CollisionFrame.begin(self);
        } else {
            CollisionFrame.suspend(self);
        }
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void entityCollisionOptimizer$endCollisionFrame(
            BooleanSupplier shouldKeepTicking,
            CallbackInfo ci
    ) {
        CollisionFrame.end((ServerLevel) (Object) this);
    }

    @Inject(method = "addEntity", at = @At("RETURN"))
    private void entityCollisionOptimizer$trackAddedEntity(
            Entity entity,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            CollisionFrame.addEntity(entity);
        }
    }
}

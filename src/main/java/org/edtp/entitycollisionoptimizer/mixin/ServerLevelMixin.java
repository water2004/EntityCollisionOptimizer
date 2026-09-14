package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    /* Attach the entity storage to the server level after initialization */
    private void entityCollisionOptimizer$attachEntityStorage(CallbackInfo ci) {
        CollisionFrame.attach((ServerLevel) (Object) this);
    }

    /** Begin a collision frame before entity ticking of each tick */
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
        CollisionFrame.begin(self);
    }

    /** End the collision frame after the entire level tick */
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void entityCollisionOptimizer$endCollisionFrame(
            BooleanSupplier shouldKeepTicking,
            CallbackInfo ci
    ) {
        CollisionFrame.end((ServerLevel) (Object) this);
    }

}

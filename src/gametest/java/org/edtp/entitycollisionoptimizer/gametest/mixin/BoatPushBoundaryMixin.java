package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import org.edtp.entitycollisionoptimizer.gametest.PushRunBoundaryParity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Boat.class)
public abstract class BoatPushBoundaryMixin {
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void eco$observeSpecialBoundary(Entity entity, CallbackInfo ci) {
        PushRunBoundaryParity.observe();
    }
}

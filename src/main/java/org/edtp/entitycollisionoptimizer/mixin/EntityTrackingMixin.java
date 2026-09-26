package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Track query visibility, including accessible non-ticking chunks; not just entity ticking. */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class EntityTrackingMixin {

    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void eco$start(Entity entity, CallbackInfo ci) {
        CollisionFrame.trackingStarted((ServerLevel) entity.level(), entity);
    }

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
    private void eco$end(Entity entity, CallbackInfo ci) {
        CollisionFrame.trackingEnded((ServerLevel) entity.level(), entity);
    }

    @Inject(method = "onSectionChange(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void eco$section(Entity entity, CallbackInfo ci) {
        CollisionFrame.sectionChanged((ServerLevel) entity.level(), entity);
    }
}

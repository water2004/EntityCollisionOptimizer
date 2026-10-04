package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Track query visibility, including accessible non-ticking chunks; not just entity ticking. */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class EntityTrackingMixin {
    // 1.21.11's official mappings name this synthetic outer-instance field
    // field_26936 instead of the this$0 used by later versions.
    @Shadow @Final private ServerLevel field_26936;

    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void eco$start(Entity entity, CallbackInfo ci) {
        CollisionFrame.trackingStarted(field_26936, entity);
    }

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
    private void eco$end(Entity entity, CallbackInfo ci) {
        CollisionFrame.trackingEnded(field_26936, entity);
    }

    @Inject(method = "onSectionChange(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void eco$section(Entity entity, CallbackInfo ci) {
        CollisionFrame.sectionChanged(field_26936, entity);
    }

    @Inject(method = {"onTickingStart(Lnet/minecraft/world/entity/Entity;)V",
            "onTickingEnd(Lnet/minecraft/world/entity/Entity;)V"}, at = @At("RETURN"))
    private void eco$tickingChanged(Entity entity, CallbackInfo ci) {
        // 26.3 living-entity pushability depends on entity ticking, even without movement.
        ((CollisionCacheState) entity).entityCollisionOptimizer$invalidateCollisionCache();
    }
}

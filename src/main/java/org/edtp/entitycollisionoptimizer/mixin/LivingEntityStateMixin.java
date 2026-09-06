package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.CollisionSleepingState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(LivingEntity.class)
public abstract class LivingEntityStateMixin implements CollisionSleepingState {
    @Shadow @Final private static EntityDataAccessor<Optional<BlockPos>> SLEEPING_POS_ID;
    @Unique private boolean eco$sleepingValid;
    @Unique private boolean eco$sleeping;

    @Override
    public boolean entityCollisionOptimizer$isSleepingCached() {
        if (!eco$sleepingValid) {
            eco$sleeping = ((LivingEntity) (Object) this).isSleeping();
            eco$sleepingValid = true;
        }
        return eco$sleeping;
    }

    @Inject(method = "onSyncedDataUpdated", at = @At("HEAD"))
    private void eco$onDataUpdated(EntityDataAccessor<?> accessor, CallbackInfo ci) {
        // Sleeping position, not pose, is the authority used by LivingEntity.push.
        if (accessor.equals(SLEEPING_POS_ID)) eco$sleepingValid = false;
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void eco$onSetHealth(float health, CallbackInfo ci) {
        ((CollisionCacheState) this).entityCollisionOptimizer$invalidateCollisionCache();
    }
}

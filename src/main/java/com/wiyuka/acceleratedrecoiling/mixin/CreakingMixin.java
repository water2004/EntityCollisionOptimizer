package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.collision.CollisionCacheState;
import net.minecraft.world.entity.monster.creaking.Creaking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Creaking.class)
public abstract class CreakingMixin {
    @Inject(method = "aiStep", at = @At("RETURN"))
    private void acceleratedRecoiling$afterCanMoveUpdate(CallbackInfo ci) {
        ((CollisionCacheState) this).acceleratedRecoiling$invalidateCollisionCache();
    }
}

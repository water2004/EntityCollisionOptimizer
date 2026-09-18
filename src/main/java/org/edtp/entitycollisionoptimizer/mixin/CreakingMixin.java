package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import net.minecraft.world.entity.monster.creaking.Creaking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pushability here follows canMove(), which the creaking's own AI toggles without any setter we hook, so the cached state expires each tick. */
@Mixin(Creaking.class)
public abstract class CreakingMixin {
    @Inject(method = "aiStep", at = @At("RETURN"))
    private void entityCollisionOptimizer$afterCanMoveUpdate(CallbackInfo ci) {
        ((CollisionCacheState) this).entityCollisionOptimizer$invalidateCollisionCache();
    }
}

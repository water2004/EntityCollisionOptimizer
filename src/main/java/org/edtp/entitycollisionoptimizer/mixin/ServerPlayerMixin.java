package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "setGameMode", at = @At("RETURN"))
    private void entityCollisionOptimizer$onSetGameMode(
            GameType gameType,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            ((CollisionCacheState) this).entityCollisionOptimizer$invalidateCollisionCache();
        }
    }
}

package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.collision.CollisionCacheState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "setGameMode", at = @At("RETURN"))
    private void acceleratedRecoiling$onSetGameMode(
            GameType gameType,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            ((CollisionCacheState) this).acceleratedRecoiling$invalidateCollisionCache();
        }
    }
}

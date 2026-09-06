package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.collision.CollisionCacheEpochs;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Scoreboard.class)
public abstract class ScoreboardMixin {
    @Inject(method = "addPlayerToTeam", at = @At("RETURN"))
    private void acceleratedRecoiling$onAddPlayerToTeam(
            String playerName,
            PlayerTeam team,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if ((Object) this instanceof ServerScoreboard && cir.getReturnValueZ()) {
            CollisionCacheEpochs.invalidateTeams();
        }
    }

    @Inject(
            method = "removePlayerFromTeam(Ljava/lang/String;)Z",
            at = @At("RETURN")
    )
    private void acceleratedRecoiling$onRemovePlayerFromAnyTeam(
            String playerName,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if ((Object) this instanceof ServerScoreboard && cir.getReturnValueZ()) {
            CollisionCacheEpochs.invalidateTeams();
        }
    }

    @Inject(
            method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN")
    )
    private void acceleratedRecoiling$onRemovePlayerFromTeam(
            String playerName,
            PlayerTeam team,
            CallbackInfo ci
    ) {
        if ((Object) this instanceof ServerScoreboard) {
            CollisionCacheEpochs.invalidateTeams();
        }
    }

    @Inject(method = "removePlayerTeam", at = @At("RETURN"))
    private void acceleratedRecoiling$onRemoveTeam(PlayerTeam team, CallbackInfo ci) {
        if ((Object) this instanceof ServerScoreboard) {
            CollisionCacheEpochs.invalidateTeams();
        }
    }
}

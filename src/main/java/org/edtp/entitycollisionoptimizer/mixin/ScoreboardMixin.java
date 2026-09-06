package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs;
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
    private void entityCollisionOptimizer$onAddPlayerToTeam(
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
    private void entityCollisionOptimizer$onRemovePlayerFromAnyTeam(
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
    private void entityCollisionOptimizer$onRemovePlayerFromTeam(
            String playerName,
            PlayerTeam team,
            CallbackInfo ci
    ) {
        if ((Object) this instanceof ServerScoreboard) {
            CollisionCacheEpochs.invalidateTeams();
        }
    }

    @Inject(method = "removePlayerTeam", at = @At("RETURN"))
    private void entityCollisionOptimizer$onRemoveTeam(PlayerTeam team, CallbackInfo ci) {
        if ((Object) this instanceof ServerScoreboard) {
            CollisionCacheEpochs.invalidateTeams();
        }
    }
}

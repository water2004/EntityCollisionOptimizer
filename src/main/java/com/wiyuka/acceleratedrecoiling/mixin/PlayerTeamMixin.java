package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.collision.CollisionCacheEpochs;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerTeam.class)
public abstract class PlayerTeamMixin {
    @Inject(method = "setCollisionRule", at = @At("RETURN"))
    private void acceleratedRecoiling$onSetCollisionRule(
            Team.CollisionRule collisionRule,
            CallbackInfo ci
    ) {
        PlayerTeam self = (PlayerTeam) (Object) this;
        if (self.getScoreboard() instanceof ServerScoreboard) {
            CollisionCacheEpochs.invalidateTeams();
        }
    }
}

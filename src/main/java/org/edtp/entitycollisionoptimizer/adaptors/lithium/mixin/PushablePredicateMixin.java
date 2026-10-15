package org.edtp.entitycollisionoptimizer.adaptors.lithium.mixin;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.adaptors.lithium.PushableQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.entity.pushable.EntityPushablePredicate", remap = false)
public abstract class PushablePredicateMixin implements PushableQuery {
    @Unique private PlayerTeam eco$sourceTeam;
    @Unique private Team.CollisionRule eco$sourceRule;

    @Override
    public void eco$capturePushableQuery(PlayerTeam team, Team.CollisionRule rule) {
        eco$sourceTeam = team;
        eco$sourceRule = rule;
    }

    @Override public PlayerTeam eco$sourceTeam() { return eco$sourceTeam; }
    @Override public Team.CollisionRule eco$sourceRule() { return eco$sourceRule; }
}

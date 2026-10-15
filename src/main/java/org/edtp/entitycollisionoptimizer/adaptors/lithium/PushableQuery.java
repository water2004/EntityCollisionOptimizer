package org.edtp.entitycollisionoptimizer.adaptors.lithium;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/** Captured only at EntitySelector.pushableBy's factory; a Lithium marker alone is not sufficient. */
public interface PushableQuery {
    void eco$capturePushableQuery(PlayerTeam team, Team.CollisionRule rule);
    PlayerTeam eco$sourceTeam();
    Team.CollisionRule eco$sourceRule();
}

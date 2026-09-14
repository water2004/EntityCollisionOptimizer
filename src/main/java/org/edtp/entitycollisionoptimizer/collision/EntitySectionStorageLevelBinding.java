package org.edtp.entitycollisionoptimizer.collision;

import net.minecraft.server.level.ServerLevel;

/** Binds a vanilla entity section storage to the level whose queries it serves. */
public interface EntitySectionStorageLevelBinding {
    void eco$setQueryLevel(ServerLevel level);
}

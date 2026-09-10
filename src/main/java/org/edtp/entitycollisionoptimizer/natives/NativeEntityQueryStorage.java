package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.server.level.ServerLevel;

/** Level association carried by the section storage itself. */
public interface NativeEntityQueryStorage {
    void eco$queryLevel(ServerLevel level);
}

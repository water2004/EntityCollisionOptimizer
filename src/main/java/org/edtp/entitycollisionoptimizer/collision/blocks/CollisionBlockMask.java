package org.edtp.entitycollisionoptimizer.collision.blocks;

/** A conservative index: every non-air block remains a collision candidate. */
public interface CollisionBlockMask {
    int entityCollisionOptimizer$nonAirRow(int y, int z);
}

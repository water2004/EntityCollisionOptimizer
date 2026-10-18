package org.edtp.entitycollisionoptimizer.collision.blocks;

/** Palette-owned candidate planes for vanilla's interior/face/edge/corner rules. No shapes are cached. */
public interface CollisionBlockMask {
    CollisionRows entityCollisionOptimizer$collisionRows(int minY, int maxY, int minZ, int maxZ);
}

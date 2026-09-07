package org.edtp.entitycollisionoptimizer.collision.blocks;

/** A row filtered by vanilla's interior/face/edge/corner rules. No shapes are cached. */
public interface CollisionBlockMask {
    int entityCollisionOptimizer$collisionRow(int y, int z, int edgesYZ, int edgesX);
    java.lang.foreign.MemorySegment entityCollisionOptimizer$collisionRows(int minY, int maxY, int minZ, int maxZ);
}

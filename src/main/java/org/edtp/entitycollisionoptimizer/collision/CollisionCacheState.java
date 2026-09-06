package org.edtp.entitycollisionoptimizer.collision;

/**
 * Per-entity revision used to invalidate collision predicate snapshots while a
 * server collision frame is active.
 */
public interface CollisionCacheState {
    long entityCollisionOptimizer$collisionRevision();

    void entityCollisionOptimizer$invalidateCollisionCache();
}

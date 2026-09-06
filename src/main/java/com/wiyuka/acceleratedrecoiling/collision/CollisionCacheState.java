package com.wiyuka.acceleratedrecoiling.collision;

/**
 * Per-entity revision used to invalidate collision predicate snapshots while a
 * server collision frame is active.
 */
public interface CollisionCacheState {
    long acceleratedRecoiling$collisionRevision();

    void acceleratedRecoiling$invalidateCollisionCache();
}

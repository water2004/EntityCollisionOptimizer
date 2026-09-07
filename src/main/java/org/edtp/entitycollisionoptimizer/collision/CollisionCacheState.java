package org.edtp.entitycollisionoptimizer.collision;

/**
 * Per-entity revision used to invalidate collision predicate snapshots while a
 * server collision frame is active.
 */
public interface CollisionCacheState {
    int PUSHABLE = 1;
    int VEHICLE = 2;
    int PASSENGER = 4;
    int SLEEPING = 8;

    long entityCollisionOptimizer$collisionRevision();

    void entityCollisionOptimizer$invalidateCollisionCache();

    boolean entityCollisionOptimizer$isPushableCached();

    /** Live revision-checked vanilla flags, shared by selection and ordinary push execution. */
    int entityCollisionOptimizer$pushState();

    void entityCollisionOptimizer$resetPushabilityCache();
}

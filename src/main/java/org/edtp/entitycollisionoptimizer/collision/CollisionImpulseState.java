package org.edtp.entitycollisionoptimizer.collision;

/**
 * Per-entity impulse accumulator used by the FFM collision frame. Native
 * queries preserve push ordering while coalescing the resulting Vec3 update.
 */
public interface CollisionImpulseState {
    void entityCollisionOptimizer$queueCollisionImpulse(double x, double z);

    void entityCollisionOptimizer$flushCollisionImpulse();
}

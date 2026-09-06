package org.edtp.entitycollisionoptimizer.collision;

/**
 * Lazy velocity storage used by native pushing. Each addition rounds immediately;
 * only Vec3 allocation is deferred until observation or the end of the frame.
 */
public interface CollisionImpulseState {
    void entityCollisionOptimizer$queueCollisionImpulse(double x, double z);

    void entityCollisionOptimizer$flushCollisionImpulse();
}

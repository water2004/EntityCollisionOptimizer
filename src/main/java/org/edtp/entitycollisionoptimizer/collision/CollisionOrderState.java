package org.edtp.entitycollisionoptimizer.collision;

/** Insertion order in the entity's current vanilla section, independent of native cell layout. */
public interface CollisionOrderState {
    long eco$sectionOrder();
    void eco$sectionOrder(long order);
}

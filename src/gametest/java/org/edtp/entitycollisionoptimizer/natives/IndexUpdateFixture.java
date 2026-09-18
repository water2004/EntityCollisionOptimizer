package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.phys.AABB;

/** Test-only geometry source for query fixtures without live Minecraft entities. */
final class IndexUpdateFixture {
    static void update(FFMBackend.Context context, int id, AABB box,
                       int sectionX, int sectionY, int sectionZ,
                       boolean selectable, boolean passenger,
                       boolean vanillaEntityPush, boolean allowsDeferredVelocityWrites,
                       int team, int rule, int bodySlot,
                       boolean hardCollidable, long order) {
        var bounds = new CollisionBounds();
        bounds.capacity(1);
        bounds.set(0, box);
        FFMBackend.updateEntity(context, id, bounds.row(0), selectable, passenger, false, false,
                vanillaEntityPush, allowsDeferredVelocityWrites, team, rule, bodySlot, hardCollidable, order);
        FFMBackend.updateLocation(context, id, sectionX, sectionY, sectionZ, order);
    }
}

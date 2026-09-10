package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.phys.AABB;

/** Test-only geometry source for query fixtures without live Minecraft entities. */
final class IndexUpdateFixture {
    static void update(FFMBackend.Context context, int id, AABB box,
                       int sectionX, int sectionY, int sectionZ) {
        var bounds = new CollisionBounds();
        bounds.capacity(1);
        bounds.set(0, box);
        FFMBackend.updateEntity(context, id, bounds.row(0));
        if (org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig.STARTUP_VANILLA_ORDER) {
            FFMBackend.updateOrderedLocation(context, id, sectionX, sectionY, sectionZ, 0L);
        } else {
            FFMBackend.updateLocation(context, id, sectionX, sectionY, sectionZ);
        }
    }
}

package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.phys.AABB;

import java.lang.foreign.MemorySegment;

/** Test-only geometry source for query fixtures without live Minecraft entities. */
final class IndexUpdateFixture {
    private static final AABB EMPTY = new AABB(0, 0, 0, 0, 0, 0);

    static void initialize(FFMBackend.Context context, int count) {
        for (int id = 0; id < count; id++) {
            insert(context, id, EMPTY, 0, 0, 0);
        }
    }

    static void insert(FFMBackend.Context context, int id, AABB box,
                       int sectionX, int sectionY, int sectionZ) {
        FFMBackend.insertEntity(context, id, box, sectionX, sectionY, sectionZ);
    }

    static void update(FFMBackend.Context context, int id, AABB box,
                       int sectionX, int sectionY, int sectionZ,
                       boolean selectable, boolean passenger,
                       boolean vanillaEntityPush, boolean allowsDeferredVelocityWrites,
                       int team, int rule, int bodySlot,
                       boolean hardCollidable) {
        var bounds = new CollisionBounds();
        bounds.capacity(1);
        bounds.set(0, box);
        FFMBackend.updateEntityState(context, id, bounds.row(0), selectable, passenger,
                vanillaEntityPush, allowsDeferredVelocityWrites, team, rule, bodySlot, hardCollidable);
        FFMBackend.updateEntitySection(context, id, sectionX, sectionY, sectionZ);
    }

    static void metadata(FFMBackend.Context context, int id,
                         boolean selectable, boolean passenger,
                         boolean vanillaEntityPush, boolean allowsDeferredVelocityWrites,
                         int team, int rule, int bodySlot,
                         boolean hardCollidable) {
        FFMBackend.updateEntityState(context, id, MemorySegment.NULL, selectable, passenger,
                vanillaEntityPush, allowsDeferredVelocityWrites, team, rule, bodySlot, hardCollidable);
    }
}

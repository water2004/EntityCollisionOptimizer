package org.edtp.entitycollisionoptimizer.collision;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global revisions for collision inputs which are not owned by one entity.
 * Worldthreader may update different levels concurrently.
 */
public final class CollisionCacheEpochs {
    private static final AtomicLong BLOCK_REVISION = new AtomicLong();
    private static final AtomicLong TEAM_REVISION = new AtomicLong();
    private static final AtomicLong VEHICLE_REVISION = new AtomicLong();

    public static long vehicleRevision() { return VEHICLE_REVISION.get(); }
    public static void invalidateVehicles() { VEHICLE_REVISION.incrementAndGet(); }

    private CollisionCacheEpochs() {
    }

    public static long blockRevision() {
        return BLOCK_REVISION.get();
    }

    public static long teamRevision() {
        return TEAM_REVISION.get();
    }

    public static void invalidateBlocks() {
        BLOCK_REVISION.incrementAndGet();
    }

    public static void invalidateTeams() {
        TEAM_REVISION.incrementAndGet();
    }
}

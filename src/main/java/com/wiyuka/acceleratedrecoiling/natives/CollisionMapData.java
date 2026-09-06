package com.wiyuka.acceleratedrecoiling.natives;

import net.minecraft.world.entity.Entity;

import java.util.Arrays;

/**
 * Reusable compressed sparse row storage for the directed collision graph.
 */
public final class CollisionMapData {
    private static int[] collisionCounts = new int[1024];
    private static int[] collisionStarts = new int[1025];
    private static int[] writeCursors = new int[1024];
    private static int[] collisionTargets = new int[1024];
    private static int entityCount;
    private static int directedEdgeCount;
    private static int lastNonEmptyDirectedEdgeCount;

    private CollisionMapData() {
    }

    public static void beginFrame(int newEntityCount) {
        ensureEntityCapacity(newEntityCount);
        Arrays.fill(collisionCounts, 0, newEntityCount, 0);
        entityCount = newEntityCount;
        directedEdgeCount = 0;
    }

    public static void countCollision(int source) {
        collisionCounts[source]++;
    }

    public static void sealCounts() {
        collisionStarts[0] = 0;
        for (int source = 0; source < entityCount; source++) {
            collisionStarts[source + 1] = collisionStarts[source] + collisionCounts[source];
        }
        directedEdgeCount = collisionStarts[entityCount];
        ensureTargetCapacity(directedEdgeCount);
        System.arraycopy(collisionStarts, 0, writeCursors, 0, entityCount);
    }

    public static void addCollision(int source, int target) {
        collisionTargets[writeCursors[source]++] = target;
    }

    public static int directedEdgeCount() {
        return directedEdgeCount;
    }

    public static void finishFrame() {
        if (directedEdgeCount != 0) {
            lastNonEmptyDirectedEdgeCount = directedEdgeCount;
        }
    }

    public static void resetLastNonEmptyDirectedEdgeCount() {
        lastNonEmptyDirectedEdgeCount = 0;
    }

    public static int lastNonEmptyDirectedEdgeCount() {
        return lastNonEmptyDirectedEdgeCount;
    }

    public static int getCollisionStart(Entity source) {
        int sourceId = TempID.getId(source);
        return sourceId < 0 || sourceId >= entityCount ? 0 : collisionStarts[sourceId];
    }

    public static int getCollisionEnd(Entity source) {
        int sourceId = TempID.getId(source);
        return sourceId < 0 || sourceId >= entityCount ? 0 : collisionStarts[sourceId + 1];
    }

    public static int[] collisionTargets() {
        return collisionTargets;
    }

    private static void ensureEntityCapacity(int required) {
        if (required <= collisionCounts.length) {
            return;
        }
        int newSize = Math.max(required, collisionCounts.length + (collisionCounts.length >> 1));
        collisionCounts = Arrays.copyOf(collisionCounts, newSize);
        collisionStarts = Arrays.copyOf(collisionStarts, newSize + 1);
        writeCursors = Arrays.copyOf(writeCursors, newSize);
    }

    private static void ensureTargetCapacity(int required) {
        if (required <= collisionTargets.length) {
            return;
        }
        int grown = collisionTargets.length + (collisionTargets.length >> 1);
        collisionTargets = Arrays.copyOf(collisionTargets, Math.max(required, grown));
    }
}

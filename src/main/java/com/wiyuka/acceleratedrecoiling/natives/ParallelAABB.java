package com.wiyuka.acceleratedrecoiling.natives;

import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.Team;

import java.util.Arrays;
import java.util.List;

public final class ParallelAABB {
    private static Team[] collisionTeams = new Team[1024];
    private static Team.CollisionRule[] collisionRules = new Team.CollisionRule[1024];
    private static boolean[] pushableTargets = new boolean[1024];
    private static boolean[] denseSources = new boolean[1024];
    private static int[] entityTempIds = new int[1024];
    private static byte[] pairDirections = new byte[1024];

    private ParallelAABB() {
    }

    public static void handleEntityPush(List<Entity> entities, double inflate) {
        CollisionMapData.beginFrame(TempID.currentIndex);
        ensureEntityCapacity(entities.size());

        double[] aabbs = new double[entities.size() * 6];
        for (int index = 0; index < entities.size(); index++) {
            ICustomData data = (ICustomData) entities.get(index);
            data.extractionBoundingBox(aabbs, index * 6, inflate);
            data.setDensity(0);
        }

        FFMBackend.PushResult result = FFMBackend.push(aabbs, entities.size());
        cacheEntityCollisionState(entities, result);

        int pairCount = result.size();
        ensurePairCapacity(pairCount);
        countDirectedCollisions(entities, result, pairCount);
        writeDirectedCollisions(result, pairCount);
        CollisionMapData.finishFrame();
    }

    private static void cacheEntityCollisionState(
            List<Entity> entities,
            FFMBackend.PushResult result
    ) {
        for (int index = 0; index < entities.size(); index++) {
            Entity entity = entities.get(index);
            ICustomData data = (ICustomData) entity;
            float density = result.getDensity(index);
            data.setDensity(density);

            Team team = entity.getTeam();
            collisionTeams[index] = team;
            collisionRules[index] = team == null
                    ? Team.CollisionRule.ALWAYS
                    : team.getCollisionRule();
            pushableTargets[index] = !entity.isSpectator() && entity.isPushable();
            denseSources[index] = entity instanceof LivingEntity
                    && density >= FoldConfig.densityThreshold;
            entityTempIds[index] = TempID.getId(entity);
        }
    }

    private static void countDirectedCollisions(
            List<Entity> entities,
            FFMBackend.PushResult result,
            int pairCount
    ) {
        for (int pairIndex = 0; pairIndex < pairCount; pairIndex++) {
            int firstIndex = result.getA(pairIndex);
            int secondIndex = result.getB(pairIndex);
            if (!validPair(firstIndex, secondIndex, entities.size())) {
                pairDirections[pairIndex] = 0;
                continue;
            }

            Entity first = entities.get(firstIndex);
            Entity second = entities.get(secondIndex);
            if (!first.getBoundingBox().intersects(second.getBoundingBox())) {
                pairDirections[pairIndex] = 0;
                continue;
            }

            byte directions = 0;
            if (canPush(firstIndex, secondIndex)) {
                directions |= 1;
                CollisionMapData.countCollision(entityTempIds[firstIndex]);
            }
            if (canPush(secondIndex, firstIndex)) {
                directions |= 2;
                CollisionMapData.countCollision(entityTempIds[secondIndex]);
            }
            pairDirections[pairIndex] = directions;
        }
        CollisionMapData.sealCounts();
    }

    private static void writeDirectedCollisions(FFMBackend.PushResult result, int pairCount) {
        for (int pairIndex = 0; pairIndex < pairCount; pairIndex++) {
            byte directions = pairDirections[pairIndex];
            if (directions == 0) {
                continue;
            }

            int firstIndex = result.getA(pairIndex);
            int secondIndex = result.getB(pairIndex);
            if ((directions & 1) != 0) {
                CollisionMapData.addCollision(entityTempIds[firstIndex], entityTempIds[secondIndex]);
            }
            if ((directions & 2) != 0) {
                CollisionMapData.addCollision(entityTempIds[secondIndex], entityTempIds[firstIndex]);
            }
        }
    }

    private static boolean validPair(int firstIndex, int secondIndex, int entityCount) {
        return firstIndex >= 0
                && secondIndex >= 0
                && firstIndex < entityCount
                && secondIndex < entityCount;
    }

    private static boolean canPush(int sourceIndex, int targetIndex) {
        if (!denseSources[sourceIndex]
                || collisionRules[sourceIndex] == Team.CollisionRule.NEVER
                || !pushableTargets[targetIndex]
                || collisionRules[targetIndex] == Team.CollisionRule.NEVER) {
            return false;
        }

        Team sourceTeam = collisionTeams[sourceIndex];
        boolean allied = sourceTeam != null && sourceTeam.isAlliedTo(collisionTeams[targetIndex]);
        Team.CollisionRule sourceRule = collisionRules[sourceIndex];
        Team.CollisionRule targetRule = collisionRules[targetIndex];

        if ((sourceRule == Team.CollisionRule.PUSH_OWN_TEAM
                || targetRule == Team.CollisionRule.PUSH_OWN_TEAM) && allied) {
            return false;
        }
        return (sourceRule != Team.CollisionRule.PUSH_OTHER_TEAMS
                && targetRule != Team.CollisionRule.PUSH_OTHER_TEAMS) || allied;
    }

    private static void ensureEntityCapacity(int entityCount) {
        if (entityCount <= collisionTeams.length) {
            return;
        }
        int newSize = Math.max(entityCount, collisionTeams.length + (collisionTeams.length >> 1));
        collisionTeams = Arrays.copyOf(collisionTeams, newSize);
        collisionRules = Arrays.copyOf(collisionRules, newSize);
        pushableTargets = Arrays.copyOf(pushableTargets, newSize);
        denseSources = Arrays.copyOf(denseSources, newSize);
        entityTempIds = Arrays.copyOf(entityTempIds, newSize);
    }

    private static void ensurePairCapacity(int pairCount) {
        if (pairCount <= pairDirections.length) {
            return;
        }
        int newSize = Math.max(pairCount, pairDirections.length + (pairDirections.length >> 1));
        pairDirections = Arrays.copyOf(pairDirections, newSize);
    }
}

#pragma once

#include "export.h"

extern "C" {

ECO_EXPORT void* createCollisionContext();
ECO_EXPORT void destroyCollisionContext(void* context);
ECO_EXPORT int setCollisionGridSize(void* context, int gridSize);
ECO_EXPORT int beginCollisionFrame(
        void* context,
        const double* aabbs,
        const double* positions,
        const int* sections,
        int entityCount,
        int gridSize
);
ECO_EXPORT int addCollisionEntity(
        void* context,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        double positionX,
        double positionZ,
        int sectionX,
        int sectionY,
        int sectionZ
);
ECO_EXPORT int updateCollisionEntity(
        void* context,
        int entityId,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        double positionX,
        double positionZ,
        int sectionX,
        int sectionY,
        int sectionZ
);
ECO_EXPORT int updateCollisionEntityMetadata(
        void* context,
        int entityId,
        int selectable,
        int passenger,
        int vehicle,
        int noPhysics,
        int vanillaEntityPush,
        int vanillaVectorPush,
        int teamId,
        int collisionRule
);
ECO_EXPORT int invalidateCollisionEntityMetadata(void* context, int entityId);
ECO_EXPORT int invalidateCollisionMetadata(void* context, int mask);
ECO_EXPORT int queryCollisionEntities(void* context, int sourceId, int* output, int outputCapacity);
ECO_EXPORT int queryPushableEntities(
        void* context,
        int sourceId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceUsesVanillaPush,
        int* output,
        int* nativePushOutput,
        int outputCapacity
);

ECO_EXPORT int calculatePushImpulses(
        double sourceX, double sourceZ, const double* targetPositions, int count, double* impulses
);

}

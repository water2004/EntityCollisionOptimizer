#pragma once

#include "export.h"

extern "C" {

AR_EXPORT void* createCollisionContext();
AR_EXPORT void destroyCollisionContext(void* context);
AR_EXPORT int setCollisionGridSize(void* context, int gridSize);
AR_EXPORT int beginCollisionFrame(
        void* context,
        const double* aabbs,
        const double* positions,
        const int* sections,
        int entityCount,
        int gridSize
);
AR_EXPORT int addCollisionEntity(
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
AR_EXPORT int updateCollisionEntity(
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
AR_EXPORT int updateCollisionEntityMetadata(
        void* context,
        int entityId,
        int selectable,
        int passenger,
        int ordinaryLivingTarget,
        int teamId,
        int collisionRule
);
AR_EXPORT int invalidateCollisionEntityMetadata(void* context, int entityId);
AR_EXPORT int invalidateCollisionMetadata(void* context, int mask);
AR_EXPORT int queryCollisionEntities(void* context, int sourceId, int* output, int outputCapacity);
AR_EXPORT int queryPushableEntities(
        void* context,
        int sourceId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceUsesVanillaPush,
        int* output,
        int outputCapacity
);

}

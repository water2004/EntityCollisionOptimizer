#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

#include "eco/export.h"
#include <cstdint>

extern "C" {
ECO_EXPORT int putCollisionEntity(
        void* context, int id, const double* bounds, int x, int y, int z
#if ECO_VANILLA_ORDER
        ,
        std::int64_t sectionOrder
#endif
);
ECO_EXPORT int removeCollisionEntity(void* context, int id);
ECO_EXPORT int updateCollisionLocation(
        void* context, int id, int x, int y, int z
#if ECO_VANILLA_ORDER
        , std::int64_t sectionOrder
#endif
);
ECO_EXPORT int scanCollisionBlocks(const std::uint16_t* const* rows, int* query, int* output, int capacity);

ECO_EXPORT void* createCollisionContext();
ECO_EXPORT void destroyCollisionContext(void* context);
ECO_EXPORT int setCollisionGridSize(void* context, int gridSize);
ECO_EXPORT int beginCollisionFrame(
        void* context,
        const double* aabbs,
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
        int sectionX,
        int sectionY,
        int sectionZ
);
ECO_EXPORT int updateCollisionEntity(
        void* context,
        int entityId,
        const double* bounds,
        int selectable,
        int passenger,
        int vehicle,
        int noPhysics,
        int vanillaEntityPush,
        int vanillaVectorPush,
        int teamId,
        int collisionRule,
        int bodySlot,
        int hardCollidable
#if ECO_VANILLA_ORDER
        , std::int64_t sectionOrder
#endif
);
ECO_EXPORT int invalidateCollisionEntityMetadata(void* context, int entityId);
ECO_EXPORT int invalidateCollisionMetadata(void* context, int mask);
ECO_EXPORT int queryCollisionEntities(void* context, int sourceId, int* output, int outputCapacity);
ECO_EXPORT int queryHardCollisionEntities(
        void* context,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int excludeId,
        int hardOnly,
        int* output,
        int outputCapacity
);
// Whole-level box scan for EntitySectionStorage.getEntities. The ordered build
// preserves section and insertion order; the unordered build omits that cost.
ECO_EXPORT int queryEntitiesInBox(
        void* context,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int* output,
        int outputCapacity
);
// output: [metadataRequired, pushableCount, nonPassengerCount], IDs[capacity], bodySlots[capacity].
// On a metadata miss only the header and returned IDs are valid. nativePushOutput has capacity ints.
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

ECO_EXPORT int executePushRun(void* bodies, int capacity, int sourceSlot,
                             const int* targetSlots, int count);

ECO_EXPORT int solveMovement(const void* body, double* data, const void* shapes, int count, int phase);
ECO_EXPORT int prepareMovement(const double* bounds, double* data);

}

#pragma once

#include "eco/export.h"
#include <cstdint>

extern "C" {
ECO_EXPORT int putCollisionEntity(
        void* context, int id, const double* bounds, int x, int y, int z,
        std::int64_t sectionOrder
);
ECO_EXPORT int removeCollisionEntity(void* context, int id);
ECO_EXPORT int updateCollisionLocation(
        void* context, int id, int x, int y, int z, std::int64_t sectionOrder
);
ECO_EXPORT int scanCollisionBlocks(const std::uint16_t* const* rows, int* query, int* output, int capacity);

ECO_EXPORT void* createCollisionContext();
ECO_EXPORT void destroyCollisionContext(void* context);
ECO_EXPORT int updateCollisionEntity(
        void* context,
        int entityId,
        const double* bounds,
        int selectable,
        int passenger,
        int vanillaEntityPush,
        int allowsDeferredVelocityWrites,
        int teamId,
        int collisionRule,
        int bodySlot,
        int hardCollidable,
        std::int64_t sectionOrder
);
ECO_EXPORT int invalidateEntityPushabilityCache(void* context, int entityId);
ECO_EXPORT int invalidatePushEligibilityFields(void* context, int fieldsToInvalidate);
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
// Whole-level box scan for EntitySectionStorage.getEntities in vanilla order.
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
        const double* sourceBounds,
        int excludedEntityId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceNativePushEligible,
        int* output,
        int* nativePushOutput,
        int outputCapacity
);

ECO_EXPORT int executePushRun(void* sourceBody, void* targetBodies, int targetCapacity,
                             const int* targetSlots, int count);

ECO_EXPORT int solveMovement(const void* body, double* data, const void* shapes, int count, int phase);
ECO_EXPORT int prepareMovement(const double* bounds, double* data);

}

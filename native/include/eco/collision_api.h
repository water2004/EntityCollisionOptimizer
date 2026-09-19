#pragma once

#include <cstdint>

#ifdef AR_WINDOWS
#define ECO_EXPORT __declspec(dllexport)
#else
#define ECO_EXPORT __attribute__((visibility("default")))
#endif

// ABI declarations used by the Java FFM backend. Keep names and signatures in
// sync with FFMBackend and native/version-script.
extern "C" {

// Collision context lifecycle.
ECO_EXPORT void* createCollisionContext();
ECO_EXPORT void destroyCollisionContext(void* context);

// Persistent entity index updates.
ECO_EXPORT int putCollisionEntity(
        void* context, int id, const double* bounds, int x, int y, int z,
        std::int64_t sectionOrder
);
ECO_EXPORT int removeCollisionEntity(void* context, int id);
ECO_EXPORT int updateCollisionLocation(
        void* context, int id, int x, int y, int z, std::int64_t sectionOrder
);

// Entity metadata and push-eligibility cache updates.
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

// Spatial entity queries.
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
// Whole-level box scan for EntitySectionStorage.getEntities, in vanilla order.
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
// output: [metadataRequired, pushableCount, nonPassengerCount], IDs[capacity],
// bodySlots[capacity]. nativePushOutput receives one native-push flag per ID.
// On a metadata miss, only the header and returned IDs are valid.
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

// Execute one batched entity-push run.
ECO_EXPORT int executePushRun(void* sourceBody, void* targetBodies, int targetCapacity,
                             const int* targetSlots, int count);

// Movement preparation and collision solving.
ECO_EXPORT int prepareMovement(const double* bounds, double* data);
ECO_EXPORT int solveMovement(const void* body, double* data, const void* shapes, int count, int phase);

// Scan block collision masks; this API does not use a collision context.
ECO_EXPORT int scanCollisionBlocks(const std::uint16_t* const* rows, int* query, int* output, int capacity);
}

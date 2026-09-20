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

// Persistent entity state and index updates.
ECO_EXPORT int insertCollisionEntity(
        void* contextPointer,
        int nativeId,
        const double* entityBounds,
        int sectionX,
        int sectionY,
        int sectionZ,
        std::int64_t sectionOrder
);
ECO_EXPORT int updateCollisionEntityState(
        void* contextPointer,
        int nativeId,
        const double* entityBounds,
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
ECO_EXPORT int updateCollisionEntitySection(
        void* contextPointer,
        int nativeId,
        int sectionX,
        int sectionY,
        int sectionZ,
        std::int64_t sectionOrder
);
ECO_EXPORT int removeCollisionEntity(void* contextPointer, int nativeId);

// Push-eligibility cache invalidation.
ECO_EXPORT int invalidateEntityPushEligibilityCache(void* contextPointer, int nativeId);
ECO_EXPORT int invalidatePushEligibilityCacheFields(void* contextPointer, int fieldsToInvalidate);

// Spatial entity queries.
ECO_EXPORT int queryHardCollisionEntities(
        void* contextPointer,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int excludedNativeId,
        int hardOnly,
        int* outputNativeIds,
        int outputCapacity
);
// Whole-level box scan for EntitySectionStorage.getEntities, in vanilla order.
ECO_EXPORT int queryEntitiesInBox(
        void* contextPointer,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int* outputNativeIds,
        int outputCapacity
);
// outputBuffer: [metadataRequired, pushableCount, nonPassengerCount],
// native IDs[capacity], bodySlots[capacity]. nativePushFlags receives one
// native-push flag per returned native ID.
// On a metadata miss, only the header and returned IDs are valid.
ECO_EXPORT int queryPushableEntities(
        void* contextPointer,
        const double* sourceBounds,
        int excludedNativeId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceNativePushEligible,
        int* outputBuffer,
        int* nativePushFlags,
        int outputCapacity
);

// Execute one batched entity-push run.
ECO_EXPORT int executePushRun(void* sourceBodyPointer, void* targetBodiesPointer,
                             int targetCapacity, const int* targetSlots, int targetCount);

// Movement preparation and collision solving.
ECO_EXPORT int prepareMovement(const double* entityBounds, double* movementData);
ECO_EXPORT int solveMovement(const void* bodyPointer, double* movementData,
                             const void* shapeReferencesPointer, int shapeCount,
                             int movementPhase);

// Scan block collision masks; this API does not use a collision context.
ECO_EXPORT int scanCollisionBlocks(const std::uint16_t* const* collisionRows,
                                   int* queryState, int* outputRecords,
                                   int outputCapacity);
}

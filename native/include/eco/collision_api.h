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

// Valid on the calling thread after an exported function reports a C++ exception.
ECO_EXPORT const char* lastNativeException() noexcept;

// Collision context lifecycle.
// indexUpdatesPointer is the stable shared header described in state/index_updates.h.
ECO_EXPORT void* createCollisionContext(void* indexUpdatesPointer);
ECO_EXPORT void destroyCollisionContext(void* context);

// Persistent entity state and index updates.
ECO_EXPORT int insertCollisionEntity(
        void* contextPointer,
        int nativeId,
        const double* entityBounds,
        int sectionX,
        int sectionY,
        int sectionZ,
        int hardCollidable,
        int derivedTeam
);
ECO_EXPORT int removeCollisionEntity(void* contextPointer, int nativeId);

// Queries and membership changes consume pending shared index updates first.
// Explicit flushing is needed only when Java fills the bounded update buffer.
ECO_EXPORT int flushCollisionUpdates(void* contextPointer);

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
// native IDs[capacity], requiredFields[capacity]. nativePushFlags receives one
// native-push flag per returned native ID.
// requiredFields is populated only on a metadata miss (state=1, team=2).
ECO_EXPORT int queryPushableEntities(
        void* contextPointer,
        const double* sourceBounds,
        int excludedNativeId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceNativePushEligible,
        std::uint64_t queryEpoch,
        int* outputBuffer,
        int* nativePushFlags,
        int outputCapacity
);

// Execute one batched entity-push run.
ECO_EXPORT int executePushRun(void* sourceBodyPointer, void* targetBodiesPointer,
                             int targetCapacity, const int* targetSlots, int targetCount);

// Movement preparation and collision solving.
ECO_EXPORT int prepareMovement(double* movementData);
ECO_EXPORT int solveMovement(double* movementData,
                             const void* shapeReferencesPointer, int shapeCount,
                             int movementPhase);

}

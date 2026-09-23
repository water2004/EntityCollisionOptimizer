#include "eco/collision_api.h"
#include "state/collision_context.h"
#include "spatial/spatial_index.h"
#include "spatial/section_index.h"

int insertCollisionEntity(
        void* contextPointer,
        int nativeId,
        const double* entityBounds,
        int sectionX,
        int sectionY,
        int sectionZ
) {
    if (!contextPointer || !entityBounds || nativeId < 0) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        // nativeId indexes metadata, so new IDs must append and retired IDs may be reused.
        if (static_cast<std::size_t>(nativeId) > context.metadata.size()) return -1;
        if (static_cast<std::size_t>(nativeId) == context.metadata.size()) {
            context.metadata.emplace_back();
            context.sectionSlots.push_back({nullptr, 0});
        } else if (context.sectionSlots[nativeId].members != nullptr) return -1;
        context.metadata[nativeId] = {};
        context.metadata[nativeId].sectionX = sectionX;
        context.metadata[nativeId].sectionY = sectionY;
        context.metadata[nativeId].sectionZ = sectionZ;
        eco::insertSectionEntity(
                context,
                nativeId,
                eco::makeAabb(
                    entityBounds[0], entityBounds[1], entityBounds[2],
                    entityBounds[3], entityBounds[4], entityBounds[5]
                )
        );
        return 0;
    } catch (...) { return -2; }
}

int removeCollisionEntity(void* contextPointer, int nativeId) {
    if (!contextPointer || nativeId < 0) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(nativeId) >= context.metadata.size()) return -1;
        eco::removeSectionEntity(context, nativeId);
        if (context.metadata[nativeId].hardCollidable) --context.hardEntityCount;
        context.metadata[nativeId] = {};
        return 0;
    } catch (...) { return -2; }
}

int updateCollisionEntitySection(
        void* contextPointer,
        int nativeId,
        int sectionX,
        int sectionY,
        int sectionZ
) {
    if (!contextPointer || nativeId < 0) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(nativeId) >= context.metadata.size()) return -1;
        auto& metadata = context.metadata[nativeId];
        eco::updateSectionEntity(context, nativeId, sectionX, sectionY, sectionZ);
        if (metadata.selectableValid && !metadata.selectable) {
            eco::updateEntityQueryability(context, nativeId, true);
        }
        metadata.selectableValid = false;
        return 0;
    } catch (...) { return -2; }
}

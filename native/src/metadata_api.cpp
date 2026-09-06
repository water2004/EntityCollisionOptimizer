#include "collision_api.h"

#include "collision_types.h"

#include <cstddef>

int updateCollisionEntityMetadata(
        void* contextPointer,
        int entityId,
        int selectable,
        int passenger,
        int ordinaryLivingTarget,
        int teamId,
        int collisionRule
) {
    if (contextPointer == nullptr || entityId < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(entityId) >= context.metadata.size()) {
            return -1;
        }
        eco::EntityMetadata& metadata = context.metadata[entityId];
        metadata.selectable = selectable != 0;
        metadata.passenger = passenger != 0;
        metadata.ordinaryLivingTarget = ordinaryLivingTarget != 0;
        metadata.teamId = teamId;
        metadata.collisionRule = collisionRule;
        metadata.selectableValid = true;
        metadata.teamValid = true;
        return 0;
    } catch (...) {
        return -2;
    }
}

int invalidateCollisionEntityMetadata(void* contextPointer, int entityId) {
    if (contextPointer == nullptr || entityId < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(entityId) >= context.metadata.size()) {
            return -1;
        }
        context.metadata[entityId].selectableValid = false;
        return 0;
    } catch (...) {
        return -2;
    }
}

int invalidateCollisionMetadata(void* contextPointer, int mask) {
    if (contextPointer == nullptr
            || (mask & ~(eco::METADATA_SELECTABLE | eco::METADATA_TEAM)) != 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        for (eco::EntityMetadata& metadata : context.metadata) {
            if ((mask & eco::METADATA_SELECTABLE) != 0) {
                metadata.selectableValid = false;
            }
            if ((mask & eco::METADATA_TEAM) != 0) {
                metadata.teamValid = false;
            }
        }
        return 0;
    } catch (...) {
        return -2;
    }
}

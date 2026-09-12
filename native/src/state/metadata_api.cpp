#include "eco/collision_api.h"

#include "state/collision_context.h"
#include "spatial/section_index.h"
#include "spatial/spatial_index.h"

#include <cstddef>

int updateCollisionEntity(
        void* contextPointer,
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
) {
    if (contextPointer == nullptr || entityId < 0 || bodySlot < 0
            || collisionRule < eco::COLLISION_ALWAYS || collisionRule > eco::COLLISION_PUSH_OTHER_TEAMS) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(entityId) >= context.metadata.size()
                || static_cast<std::size_t>(entityId) >= context.boxes.size()) {
            return -1;
        }
        if (bounds != nullptr) {
            eco::updateEntityBounds(
                    context,
                    entityId,
                    eco::makeAabb(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5])
            );
        }
        eco::EntityMetadata& metadata = context.metadata[entityId];
        metadata.selectable = selectable != 0;
        metadata.passenger = passenger != 0;
        metadata.vehicle = vehicle != 0;
        metadata.noPhysics = noPhysics != 0;
        metadata.vanillaEntityPush = vanillaEntityPush != 0;
        metadata.vanillaVectorPush = vanillaVectorPush != 0;
        const bool hard = hardCollidable != 0;
        if (metadata.hardCollidable != hard) {
            if (hard) ++context.hardEntityCount; else --context.hardEntityCount;
            if (static_cast<std::size_t>(entityId) < context.memberSlots.size()) {
                for (const auto& slot : context.memberSlots[entityId]) {
                    if (hard) ++slot.members->hardCount; else --slot.members->hardCount;
                }
            }
        }
        metadata.hardCollidable = hard;
        metadata.teamId = teamId;
        metadata.collisionRule = collisionRule;
        metadata.bodySlot = bodySlot;
#if ECO_VANILLA_ORDER
        if (metadata.sectionOrder != sectionOrder) {
            metadata.sectionOrder = sectionOrder;
            eco::invalidateSectionOrder(context, entityId);
        }
#endif
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

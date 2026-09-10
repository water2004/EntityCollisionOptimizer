#include "spatial/section_index.h"

#if ECO_VANILLA_ORDER
#include "spatial/ordered_candidates.h"
#endif

#include <algorithm>

namespace eco {
namespace {

Cell sectionOf(const EntityMetadata& metadata) noexcept {
    return {metadata.sectionX, metadata.sectionY, metadata.sectionZ};
}

void markSectionOrderDirty(CollisionContext& context, int entityId) noexcept {
#if ECO_VANILLA_ORDER
    if (static_cast<std::size_t>(entityId) < context.sectionSlots.size()) {
        CellMembers* members = context.sectionSlots[entityId].members;
        if (members != nullptr) members->orderDirty = true;
    }
#else
    (void) context;
    (void) entityId;
#endif
}

} // namespace

void insertSectionEntity(CollisionContext& context, int entityId) {
    context.sectionSlots.resize(context.boxes.size(), {nullptr, 0});
    const Cell section = sectionOf(context.metadata[entityId]);
    CellMembers*& entry = context.sections.entry(section);
    if (entry == nullptr) entry = &context.acquireSectionMembers();
    entry->ids.push_back(entityId);
#if ECO_VANILLA_ORDER
    entry->orderDirty = true;
#endif
    context.sectionSlots[entityId] = {entry, entry->ids.size() - 1};
}

void removeSectionEntity(CollisionContext& context, int entityId) {
    if (static_cast<std::size_t>(entityId) >= context.sectionSlots.size()) return;
    CellSlot slot = context.sectionSlots[entityId];
    if (slot.members == nullptr || slot.index >= slot.members->ids.size()) return;

    CellMembers& members = *slot.members;
    const int movedId = members.ids.back();
    if (slot.index != members.ids.size() - 1) {
        members.ids[slot.index] = movedId;
        context.sectionSlots[movedId].index = slot.index;
    }
    members.ids.pop_back();
#if ECO_VANILLA_ORDER
    members.orderDirty = true;
#endif
    context.sectionSlots[entityId] = {nullptr, 0};
    if (members.ids.empty()) {
        context.sections.erase(sectionOf(context.metadata[entityId]));
        context.retireSectionMembers(&members);
    }
}

void updateSectionEntity(
        CollisionContext& context,
        int entityId,
        std::int32_t sectionX,
        std::int32_t sectionY,
        std::int32_t sectionZ
#if ECO_VANILLA_ORDER
        , std::int64_t sectionOrder
#endif
) {
    EntityMetadata& metadata = context.metadata[entityId];
    const bool moved = metadata.sectionX != sectionX
            || metadata.sectionY != sectionY
            || metadata.sectionZ != sectionZ;
#if ECO_VANILLA_ORDER
    const bool reordered = metadata.sectionOrder != sectionOrder;
    if (moved || reordered) invalidateCandidateOrder(context, entityId);
#endif
    if (moved) removeSectionEntity(context, entityId);
    metadata.sectionX = sectionX;
    metadata.sectionY = sectionY;
    metadata.sectionZ = sectionZ;
#if ECO_VANILLA_ORDER
    metadata.sectionOrder = sectionOrder;
    if (!moved && reordered) markSectionOrderDirty(context, entityId);
#endif
    if (moved) insertSectionEntity(context, entityId);
}

void rebuildSectionIndex(CollisionContext& context) {
    context.clearSectionsAndPool();
    context.sectionSlots.resize(context.boxes.size(), {nullptr, 0});
    for (std::size_t entityId = 0; entityId < context.boxes.size(); ++entityId) {
        insertSectionEntity(context, static_cast<int>(entityId));
    }
}

const std::vector<int>* sectionEntities(CollisionContext& context, const Cell& section) {
    CellMembers* members = context.sections.find(section);
    if (members == nullptr) return nullptr;
#if ECO_VANILLA_ORDER
    if (members->orderDirty) {
        std::sort(members->ids.begin(), members->ids.end(), [&context](int left, int right) {
            return candidateBefore(context, left, right);
        });
        for (std::size_t index = 0; index < members->ids.size(); ++index) {
            context.sectionSlots[members->ids[index]].index = index;
        }
        members->orderDirty = false;
    }
#endif
    return &members->ids;
}

} // namespace eco

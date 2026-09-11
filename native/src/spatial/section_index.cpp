#include "spatial/section_index.h"

#include <algorithm>

namespace eco {
namespace {

Cell sectionOf(const EntityMetadata& metadata) noexcept {
    return {metadata.sectionX, metadata.sectionY, metadata.sectionZ};
}

bool candidateBefore(const CollisionContext& context, int leftId, int rightId) noexcept {
#if ECO_VANILLA_ORDER
    const auto& left = context.metadata[leftId];
    const auto& right = context.metadata[rightId];
    if (left.sectionX != right.sectionX) return left.sectionX < right.sectionX;
    const auto leftZ = left.sectionZ & 0x3fffff, rightZ = right.sectionZ & 0x3fffff;
    if (leftZ != rightZ) return leftZ < rightZ;
    const auto leftY = left.sectionY & 0xfffff, rightY = right.sectionY & 0xfffff;
    if (leftY != rightY) return leftY < rightY;
    if (left.sectionOrder != right.sectionOrder) return left.sectionOrder < right.sectionOrder;
    return leftId < rightId;
#else
    (void) context;
    (void) leftId;
    (void) rightId;
    return false;
#endif
}

} // namespace

void insertSectionEntity(CollisionContext& context, int entityId) {
    context.sectionSlots.resize(context.boxes.size(), {nullptr, 0});
    const Cell section = sectionOf(context.metadata[entityId]);
    CellMembers*& entry = context.sections.entry(section);
    if (entry == nullptr) entry = &context.acquireSectionMembers();
#if ECO_VANILLA_ORDER
    const bool remainsOrdered = !entry->orderDirty
            && (entry->ids.empty() || !candidateBefore(context, entityId, entry->ids.back()));
#endif
    entry->ids.push_back(entityId);
#if ECO_VANILLA_ORDER
    entry->bounds.push(context.boxes[entityId]);
    entry->orderDirty = !remainsOrdered;
#endif
    context.sectionSlots[entityId] = {entry, entry->ids.size() - 1};
}

void removeSectionEntity(CollisionContext& context, int entityId) {
    if (static_cast<std::size_t>(entityId) >= context.sectionSlots.size()) return;
    CellSlot slot = context.sectionSlots[entityId];
    if (slot.members == nullptr || slot.index >= slot.members->ids.size()) return;

    CellMembers& members = *slot.members;
    const int movedId = members.ids.back();
    const bool movedMember = slot.index != members.ids.size() - 1;
    if (movedMember) {
        members.ids[slot.index] = movedId;
        context.sectionSlots[movedId].index = slot.index;
    }
#if ECO_VANILLA_ORDER
    members.bounds.swapErase(slot.index);
#endif
    members.ids.pop_back();
#if ECO_VANILLA_ORDER
    if (movedMember) members.orderDirty = true;
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
#endif
    if (moved) removeSectionEntity(context, entityId);
    metadata.sectionX = sectionX;
    metadata.sectionY = sectionY;
    metadata.sectionZ = sectionZ;
#if ECO_VANILLA_ORDER
    metadata.sectionOrder = sectionOrder;
    if (!moved && reordered) invalidateSectionOrder(context, entityId);
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

const CellMembers* sectionEntities(CollisionContext& context, const Cell& section) {
    CellMembers* members = context.sections.find(section);
    if (members == nullptr) return nullptr;
#if ECO_VANILLA_ORDER
    if (members->orderDirty) {
        std::sort(members->ids.begin(), members->ids.end(), [&context](int left, int right) {
            return candidateBefore(context, left, right);
        });
        for (std::size_t index = 0; index < members->ids.size(); ++index) {
            context.sectionSlots[members->ids[index]].index = index;
            members->bounds.set(index, context.boxes[members->ids[index]]);
        }
        members->orderDirty = false;
    }
#endif
    return members;
}

#if ECO_VANILLA_ORDER
void invalidateSectionOrder(CollisionContext& context, int entityId) noexcept {
    if (static_cast<std::size_t>(entityId) >= context.sectionSlots.size()) return;
    CellMembers* members = context.sectionSlots[entityId].members;
    if (members != nullptr) members->orderDirty = true;
}
#endif

} // namespace eco

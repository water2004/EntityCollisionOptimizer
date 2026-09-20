#include "spatial/section_index.h"

#include <algorithm>

namespace eco {
namespace {

bool metadataIsQueryable(const EntityMetadata& metadata) noexcept {
    return !metadata.selectableValid || metadata.selectable;
}

Cell sectionOf(const EntityMetadata& metadata) noexcept {
    return {metadata.sectionX, metadata.sectionY, metadata.sectionZ};
}

bool candidateBefore(const CollisionContext& context, int leftNativeId, int rightNativeId) noexcept {
    const auto& left = context.metadata[leftNativeId];
    const auto& right = context.metadata[rightNativeId];
    if (left.sectionX != right.sectionX) return left.sectionX < right.sectionX;
    const auto leftZ = left.sectionZ & 0x3fffff, rightZ = right.sectionZ & 0x3fffff;
    if (leftZ != rightZ) return leftZ < rightZ;
    const auto leftY = left.sectionY & 0xfffff, rightY = right.sectionY & 0xfffff;
    if (leftY != rightY) return leftY < rightY;
    if (left.sectionOrder != right.sectionOrder) return left.sectionOrder < right.sectionOrder;
    return leftNativeId < rightNativeId;
}

} // namespace

void insertSectionEntity(CollisionContext& context, int nativeId) {
    context.sectionSlots.resize(context.boxes.size(), {nullptr, 0});
    const Cell section = sectionOf(context.metadata[nativeId]);
    CellMembers*& entry = context.sections.entry(section);
    if (entry == nullptr) entry = &context.acquireSectionMembers();
    const bool remainsOrdered = !entry->orderDirty
            && (entry->ids.empty() || !candidateBefore(context, nativeId, entry->ids.back()));
    entry->ids.push_back(nativeId);
    const bool queryable = metadataIsQueryable(context.metadata[nativeId]);
    entry->queryable.push_back(static_cast<std::uint8_t>(queryable));
    if (queryable) ++entry->queryableCount;
    entry->bounds.push(context.boxes[nativeId]);
    if (context.metadata[nativeId].hardCollidable) ++entry->hardCount;
    entry->orderDirty = !remainsOrdered;
    context.sectionSlots[nativeId] = {entry, entry->ids.size() - 1};
}

void removeSectionEntity(CollisionContext& context, int nativeId) {
    if (static_cast<std::size_t>(nativeId) >= context.sectionSlots.size()) return;
    CellSlot slot = context.sectionSlots[nativeId];
    if (slot.members == nullptr || slot.index >= slot.members->ids.size()) return;

    CellMembers& members = *slot.members;
    if (members.queryable[slot.index] != 0) --members.queryableCount;
    if (context.metadata[nativeId].hardCollidable) --members.hardCount;
    const auto offset = static_cast<std::ptrdiff_t>(slot.index);
    members.ids.erase(members.ids.begin() + offset);
    members.queryable.erase(members.queryable.begin() + offset);
    members.bounds.erase(slot.index);
    for (std::size_t index = slot.index; index < members.ids.size(); ++index) {
        context.sectionSlots[members.ids[index]].index = index;
    }
    context.sectionSlots[nativeId] = {nullptr, 0};
    if (members.ids.empty()) {
        context.sections.erase(sectionOf(context.metadata[nativeId]));
        context.retireSectionMembers(&members);
    }
}

void updateSectionEntity(
        CollisionContext& context,
        int nativeId,
        std::int32_t sectionX,
        std::int32_t sectionY,
        std::int32_t sectionZ,
        std::int64_t sectionOrder
) {
    EntityMetadata& metadata = context.metadata[nativeId];
    const bool moved = metadata.sectionX != sectionX
            || metadata.sectionY != sectionY
            || metadata.sectionZ != sectionZ;
    const bool reordered = metadata.sectionOrder != sectionOrder;
    if (moved) removeSectionEntity(context, nativeId);
    metadata.sectionX = sectionX;
    metadata.sectionY = sectionY;
    metadata.sectionZ = sectionZ;
    metadata.sectionOrder = sectionOrder;
    if (!moved && reordered) invalidateSectionOrder(context, nativeId);
    if (moved) insertSectionEntity(context, nativeId);
}

const CellMembers* sectionEntities(CollisionContext& context, const Cell& section) {
    CellMembers* members = context.sections.find(section);
    if (members == nullptr) return nullptr;
    if (members->orderDirty) {
        std::sort(members->ids.begin(), members->ids.end(), [&context](int left, int right) {
            return candidateBefore(context, left, right);
        });
        for (std::size_t index = 0; index < members->ids.size(); ++index) {
            context.sectionSlots[members->ids[index]].index = index;
            members->bounds.set(index, context.boxes[members->ids[index]]);
            members->queryable[index] = static_cast<std::uint8_t>(
                    metadataIsQueryable(context.metadata[members->ids[index]])
            );
        }
        members->orderDirty = false;
    }
    return members;
}

void invalidateSectionOrder(CollisionContext& context, int nativeId) noexcept {
    if (static_cast<std::size_t>(nativeId) >= context.sectionSlots.size()) return;
    CellMembers* members = context.sectionSlots[nativeId].members;
    if (members != nullptr) members->orderDirty = true;
}

} // namespace eco

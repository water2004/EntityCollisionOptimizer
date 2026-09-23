#include "spatial/section_index.h"

namespace eco {
namespace {

bool metadataIsQueryable(const EntityMetadata& metadata) noexcept {
    return !metadata.selectableValid || metadata.selectable;
}

Cell sectionOf(const EntityMetadata& metadata) noexcept {
    return {metadata.sectionX, metadata.sectionY, metadata.sectionZ};
}

} // namespace

void insertSectionEntity(CollisionContext& context, int nativeId, const Aabb& bounds) {
    context.sectionSlots.resize(context.metadata.size(), {nullptr, 0});
    const Cell section = sectionOf(context.metadata[nativeId]);
    CellMembers*& membersSlot = context.sections.findOrInsertValueSlot(section);
    // A null slot is a newly recorded section key awaiting its members object.
    if (membersSlot == nullptr) membersSlot = &context.acquireSectionMembers();
    membersSlot->ids.push_back(nativeId);
    const bool queryable = metadataIsQueryable(context.metadata[nativeId]);
    membersSlot->queryable.push_back(static_cast<std::uint8_t>(queryable));
    if (queryable) ++membersSlot->queryableCount;
    membersSlot->bounds.push(bounds);
    if (context.metadata[nativeId].hardCollidable) ++membersSlot->hardCount;
    context.sectionSlots[nativeId] = {membersSlot, membersSlot->ids.size() - 1};
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
        std::int32_t sectionZ
) {
    EntityMetadata& metadata = context.metadata[nativeId];
    const bool moved = metadata.sectionX != sectionX
            || metadata.sectionY != sectionY
            || metadata.sectionZ != sectionZ;
    Aabb bounds{};
    if (moved) {
        const CellSlot slot = context.sectionSlots[nativeId];
        bounds = slot.members->bounds.get(slot.index);
        removeSectionEntity(context, nativeId);
    }
    metadata.sectionX = sectionX;
    metadata.sectionY = sectionY;
    metadata.sectionZ = sectionZ;
    if (moved) insertSectionEntity(context, nativeId, bounds);
}

const CellMembers* sectionEntities(const CollisionContext& context, const Cell& section) noexcept {
    return context.sections.find(section);
}

} // namespace eco

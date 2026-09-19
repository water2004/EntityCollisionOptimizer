#include "spatial/spatial_index.h"

#include <cmath>
#include <cstddef>
#include <cstdint>

namespace eco {

std::size_t CellHash::operator()(const Cell& cell) const noexcept {
    std::uint64_t x = static_cast<std::uint64_t>(cell.x);
    std::uint64_t y = static_cast<std::uint64_t>(cell.y);
    std::uint64_t z = static_cast<std::uint64_t>(cell.z);
    x ^= x >> 30;
    x *= 0xbf58476d1ce4e5b9ULL;
    x ^= x >> 27;
    x *= 0x94d049bb133111ebULL;
    x ^= x >> 31;
    z ^= z >> 30;
    z *= 0xbf58476d1ce4e5b9ULL;
    z ^= z >> 27;
    z *= 0x94d049bb133111ebULL;
    z ^= z >> 31;
    y ^= y >> 30;
    y *= 0xbf58476d1ce4e5b9ULL;
    y ^= y >> 27;
    y *= 0x94d049bb133111ebULL;
    y ^= y >> 31;
    x ^= y + 0x9e3779b97f4a7c15ULL + (x << 6) + (x >> 2);
    return static_cast<std::size_t>(x ^ (z + 0x9e3779b97f4a7c15ULL + (x << 6) + (x >> 2)));
}

Aabb makeAabb(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
) noexcept {
    return {minX, minY, minZ, maxX, maxY, maxZ};
}

bool isIndexable(const Aabb& box) noexcept {
    return std::isfinite(box.minX)
            && std::isfinite(box.minY)
            && std::isfinite(box.minZ)
            && std::isfinite(box.maxX)
            && std::isfinite(box.maxY)
            && std::isfinite(box.maxZ)
            && box.minX < box.maxX
            && box.minY < box.maxY
            && box.minZ < box.maxZ;
}

void updateEntityBounds(CollisionContext& context, int entityId, const Aabb& box) noexcept {
    context.boxes[entityId] = box;
    if (static_cast<std::size_t>(entityId) >= context.sectionSlots.size()) return;
    const CellSlot slot = context.sectionSlots[entityId];
    if (slot.members != nullptr) slot.members->bounds.set(slot.index, box);
}

void updateEntityQueryability(CollisionContext& context, int entityId, bool queryable) noexcept {
    if (static_cast<std::size_t>(entityId) >= context.sectionSlots.size()) return;
    const CellSlot slot = context.sectionSlots[entityId];
    if (slot.members != nullptr && slot.index < slot.members->queryable.size()) {
        const bool previous = slot.members->queryable[slot.index] != 0;
        if (previous != queryable) {
            if (queryable) ++slot.members->queryableCount; else --slot.members->queryableCount;
            slot.members->queryable[slot.index] = static_cast<std::uint8_t>(queryable);
        }
    }
}

} // namespace eco

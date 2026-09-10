#include "spatial/spatial_index.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <utility>

namespace eco {

namespace {

struct CellRange {
    bool valid = false;
    std::int64_t minX = 0, minY = 0, minZ = 0;
    std::int64_t maxX = -1, maxY = -1, maxZ = -1;
    std::size_t count = 0;
};

CellRange coveredCellRange(const Aabb& box, int gridSize) {
    CellRange result;
    if (!isIndexable(box)) return result;

    const double negativeInfinity = -std::numeric_limits<double>::infinity();
    result.valid = true;
    result.minX = cellCoordinate(box.minX, gridSize);
    result.maxX = cellCoordinate(std::nextafter(box.maxX, negativeInfinity), gridSize);
    result.minY = cellCoordinate(box.minY, gridSize);
    result.maxY = cellCoordinate(std::nextafter(box.maxY, negativeInfinity), gridSize);
    result.minZ = cellCoordinate(box.minZ, gridSize);
    result.maxZ = cellCoordinate(std::nextafter(box.maxZ, negativeInfinity), gridSize);

    const std::size_t width = static_cast<std::size_t>(result.maxX - result.minX + 1);
    const std::size_t height = static_cast<std::size_t>(result.maxY - result.minY + 1);
    const std::size_t depth = static_cast<std::size_t>(result.maxZ - result.minZ + 1);
    result.count = width * height * depth;
    return result;
}

bool contains(const CellRange& range, const Cell& cell) noexcept {
    return range.valid
            && cell.x >= range.minX && cell.x <= range.maxX
            && cell.y >= range.minY && cell.y <= range.maxY
            && cell.z >= range.minZ && cell.z <= range.maxZ;
}

CellRange membershipRange(const std::vector<Cell>& memberships) noexcept {
    if (memberships.empty()) return {};

    CellRange result;
    result.valid = true;
    result.minX = memberships.front().x;
    result.minY = memberships.front().y;
    result.minZ = memberships.front().z;
    result.maxX = memberships.back().x;
    result.maxY = memberships.back().y;
    result.maxZ = memberships.back().z;
    result.count = memberships.size();
    return result;
}

bool sameRange(const std::vector<Cell>& memberships, const CellRange& range) noexcept {
    const CellRange current = membershipRange(memberships);
    return current.valid == range.valid
            && (!range.valid
                    || (current.count == range.count
                            && current.minX == range.minX && current.minY == range.minY
                            && current.minZ == range.minZ
                            && current.maxX == range.maxX && current.maxY == range.maxY
                            && current.maxZ == range.maxZ));
}

bool cellBefore(const Cell& left, const Cell& right) noexcept {
    if (left.x != right.x) return left.x < right.x;
    if (left.z != right.z) return left.z < right.z;
    return left.y < right.y;
}

void sortMemberships(
        std::vector<Cell>& memberships,
        std::vector<CellSlot>& slots
) {
    for (std::size_t index = 1; index < memberships.size(); ++index) {
        std::size_t cursor = index;
        while (cursor > 0 && cellBefore(memberships[cursor], memberships[cursor - 1])) {
            std::swap(memberships[cursor], memberships[cursor - 1]);
            std::swap(slots[cursor], slots[cursor - 1]);
            --cursor;
        }
    }
}

void appendMembership(CollisionContext& context, int entityId, const Cell& cell) {
    CellMembers*& slot = context.cells.entry(cell);
    if (slot == nullptr) slot = &context.acquireMembers();
    auto& members = *slot;
    members.ids.push_back(entityId);
#if ECO_VANILLA_ORDER
    members.bounds.push(context.boxes[entityId]);
#endif
    const std::size_t index = members.ids.size() - 1;
    if (context.metadata[entityId].hardCollidable) ++members.hardCount;
    context.memberSlots[entityId].push_back({&members, index});
}

void removeMembershipSlot(
        CollisionContext& context,
        int entityId,
        const Cell& cell,
        const CellSlot slot
) {
    // CellSlot is the backreference created by appendMembership.  Reusing it
    // avoids a hash lookup on every removal; the map is only touched when the
    // cell becomes empty and must actually be retired.
    if (slot.members == nullptr || slot.index >= slot.members->ids.size()) return;
    auto& members = *slot.members;
    if (context.metadata[entityId].hardCollidable) --members.hardCount;
    const int movedId = members.ids.back();
#if ECO_VANILLA_ORDER
    members.bounds.swapErase(slot.index);
#endif
    if (slot.index != members.ids.size() - 1) {
        members.ids[slot.index] = movedId;
        for (auto& movedSlot : context.memberSlots[movedId]) {
            if (movedSlot.members == slot.members) {
                movedSlot.index = slot.index;
                break;
            }
        }
    }
    members.ids.pop_back();
    if (members.ids.empty()) {
        context.cells.erase(cell);
        context.retireMembers(&members);
    }
}

} // namespace

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

std::int64_t cellCoordinate(double value, int gridSize) noexcept {
    return static_cast<std::int64_t>(std::floor(value / static_cast<double>(gridSize)));
}

std::vector<Cell> coveredCells(const Aabb& box, int gridSize) {
    const CellRange range = coveredCellRange(box, gridSize);
    std::vector<Cell> result;
    if (!range.valid) return result;

    result.reserve(range.count);
    // The first membership is the component-wise minimum cell; ordered queries
    // use it to assign each candidate to its unique lowest common cell.
    for (std::int64_t x = range.minX; x <= range.maxX; ++x) {
        for (std::int64_t z = range.minZ; z <= range.maxZ; ++z) {
            for (std::int64_t y = range.minY; y <= range.maxY; ++y) {
                result.push_back({x, y, z});
            }
        }
    }
    return result;
}

void insertMemberships(
        CollisionContext& context,
        int entityId,
        const std::vector<Cell>& memberships
) {
    context.memberSlots.resize(context.boxes.size());
    auto& slots = context.memberSlots[entityId];
    slots.clear();
    slots.reserve(memberships.size());
    for (const Cell& cell : memberships) {
        appendMembership(context, entityId, cell);
    }
}

void removeMemberships(
        CollisionContext& context,
        int entityId,
        const std::vector<Cell>& memberships
) {
    if (memberships.empty()) return;
    auto& slots = context.memberSlots[entityId];
    for (std::size_t cellIndex = 0; cellIndex < slots.size(); ++cellIndex) {
        const CellSlot slot = slots[cellIndex];
        removeMembershipSlot(context, entityId, memberships[cellIndex], slot);
    }
    slots.clear();
}

void rebuildSpatialIndex(CollisionContext& context) {
    context.clearCellsAndPool();
    context.memberSlots.clear();
    context.memberSlots.resize(context.boxes.size());
    context.memberships.clear();
    context.memberships.resize(context.boxes.size());
    context.queryMarks.assign(context.boxes.size(), 0);
    context.queryGeneration = 0;
    for (std::size_t index = 0; index < context.boxes.size(); ++index) {
        std::vector<Cell> memberships = coveredCells(context.boxes[index], context.gridSize);
        insertMemberships(context, static_cast<int>(index), memberships);
        context.memberships[index] = std::move(memberships);
    }
}

void updateEntityBounds(CollisionContext& context, int entityId, const Aabb& box) {
    const CellRange newRange = coveredCellRange(box, context.gridSize);
    std::vector<Cell>& oldMemberships = context.memberships[entityId];
    context.boxes[entityId] = box;
    context.memberSlots.resize(context.boxes.size());
    auto& slots = context.memberSlots[entityId];

    if (sameRange(oldMemberships, newRange)) {
#if ECO_VANILLA_ORDER
        for (const CellSlot slot : slots) {
            slot.members->bounds.set(slot.index, box);
        }
#endif
        return;
    }
    const CellRange oldRange = membershipRange(oldMemberships);
    oldMemberships.reserve(newRange.count);
    slots.reserve(newRange.count);
    std::size_t writeIndex = 0;
    for (std::size_t readIndex = 0; readIndex < oldMemberships.size(); ++readIndex) {
        const Cell cell = oldMemberships[readIndex];
        const CellSlot slot = slots[readIndex];
        if (contains(newRange, cell)) {
#if ECO_VANILLA_ORDER
            slot.members->bounds.set(slot.index, box);
#endif
            if (writeIndex != readIndex) {
                oldMemberships[writeIndex] = cell;
                slots[writeIndex] = slot;
            }
            ++writeIndex;
            continue;
        }

        removeMembershipSlot(context, entityId, cell, slot);
    }
    oldMemberships.resize(writeIndex);
    slots.resize(writeIndex);

    if (newRange.valid) {
        for (std::int64_t x = newRange.minX; x <= newRange.maxX; ++x) {
            for (std::int64_t z = newRange.minZ; z <= newRange.maxZ; ++z) {
                for (std::int64_t y = newRange.minY; y <= newRange.maxY; ++y) {
                    const Cell cell{x, y, z};
                    if (!contains(oldRange, cell)) {
                        appendMembership(context, entityId, cell);
                        oldMemberships.push_back(cell);
                    }
                }
            }
        }
    }

    sortMemberships(oldMemberships, slots);
}

bool intersects(const Aabb& first, const Aabb& second) noexcept {
    return first.minX < second.maxX
            && first.maxX > second.minX
            && first.minY < second.maxY
            && first.maxY > second.minY
            && first.minZ < second.maxZ
            && first.maxZ > second.minZ;
}

void beginQuery(CollisionContext& context) {
    ++context.queryGeneration;
    if (context.queryGeneration == 0) {
        std::fill(context.queryMarks.begin(), context.queryMarks.end(), 0);
        context.queryGeneration = 1;
    }
}

} // namespace eco

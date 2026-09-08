#include "spatial/spatial_index.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <utility>

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

std::int64_t cellCoordinate(double value, int gridSize) noexcept {
    return static_cast<std::int64_t>(std::floor(value / static_cast<double>(gridSize)));
}

std::vector<Cell> coveredCells(const Aabb& box, int gridSize) {
    std::vector<Cell> result;
    if (!isIndexable(box)) {
        return result;
    }

    const double negativeInfinity = -std::numeric_limits<double>::infinity();
    const std::int64_t minCellX = cellCoordinate(box.minX, gridSize);
    const std::int64_t maxCellX = cellCoordinate(std::nextafter(box.maxX, negativeInfinity), gridSize);
    const std::int64_t minCellY = cellCoordinate(box.minY, gridSize);
    const std::int64_t maxCellY = cellCoordinate(std::nextafter(box.maxY, negativeInfinity), gridSize);
    const std::int64_t minCellZ = cellCoordinate(box.minZ, gridSize);
    const std::int64_t maxCellZ = cellCoordinate(std::nextafter(box.maxZ, negativeInfinity), gridSize);

    const std::size_t width = static_cast<std::size_t>(maxCellX - minCellX + 1);
    const std::size_t height = static_cast<std::size_t>(maxCellY - minCellY + 1);
    const std::size_t depth = static_cast<std::size_t>(maxCellZ - minCellZ + 1);
    result.reserve(width * height * depth);
    // The first membership is the component-wise minimum cell; ordered queries
    // use it to assign each candidate to its unique lowest common cell.
    for (std::int64_t x = minCellX; x <= maxCellX; ++x) {
        for (std::int64_t z = minCellZ; z <= maxCellZ; ++z) {
            for (std::int64_t y = minCellY; y <= maxCellY; ++y) {
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
    for (const Cell& cell : memberships) {
        auto& members = context.cells[cell];
        members.ids.push_back(entityId);
#if ECO_VANILLA_ORDER
        members.orderDirty = true;
#endif
    }
}

void removeMemberships(
        CollisionContext& context,
        int entityId,
        const std::vector<Cell>& memberships
) {
    for (const Cell& cell : memberships) {
        auto cellIterator = context.cells.find(cell);
        if (cellIterator == context.cells.end()) {
            continue;
        }
        std::vector<int>& entities = cellIterator->second.ids;
        const auto entityIterator = std::find(entities.begin(), entities.end(), entityId);
        if (entityIterator != entities.end()) {
            *entityIterator = entities.back();
            entities.pop_back();
#if ECO_VANILLA_ORDER
            cellIterator->second.orderDirty = true;
#endif
        }
        if (entities.empty()) {
            context.cells.erase(cellIterator);
        }
    }
}

void rebuildSpatialIndex(CollisionContext& context) {
#if ECO_VANILLA_ORDER
    context.candidateHeap.clear();
#endif
    context.cells.clear();
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
    std::vector<Cell> newMemberships = coveredCells(box, context.gridSize);
    std::vector<Cell>& oldMemberships = context.memberships[entityId];
    if (oldMemberships != newMemberships) {
        removeMemberships(context, entityId, oldMemberships);
        insertMemberships(context, entityId, newMemberships);
        oldMemberships = std::move(newMemberships);
    }
    context.boxes[entityId] = box;
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

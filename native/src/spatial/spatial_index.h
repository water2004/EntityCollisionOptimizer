#pragma once

#include "state/collision_context.h"

#include <cstdint>
#include <vector>

namespace eco {

Aabb makeAabb(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
) noexcept;
bool isIndexable(const Aabb& box) noexcept;
std::int64_t cellCoordinate(double value, int gridSize) noexcept;
std::vector<Cell> coveredCells(const Aabb& box, int gridSize);
void insertMemberships(
        CollisionContext& context,
        int entityId,
        const std::vector<Cell>& memberships
);
void removeMemberships(
        CollisionContext& context,
        int entityId,
        const std::vector<Cell>& memberships
);
void rebuildSpatialIndex(CollisionContext& context);
void updateEntityBounds(CollisionContext& context, int entityId, const Aabb& box);
bool intersects(const Aabb& first, const Aabb& second) noexcept;
void beginQuery(CollisionContext& context);

} // namespace eco

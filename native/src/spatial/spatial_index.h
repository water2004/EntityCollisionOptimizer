#pragma once

#include "state/collision_context.h"

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
void updateEntityBounds(CollisionContext& context, int entityId, const Aabb& box) noexcept;
void updateEntityQueryability(CollisionContext& context, int entityId, bool queryable) noexcept;

} // namespace eco

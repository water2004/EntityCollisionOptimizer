#pragma once

#include "collision_types.h"

namespace eco {

// Immutable source-only bounds, computed once before visiting any candidates.
struct LookupSections {
    std::int64_t minX, minY, minZ, maxX, maxY, maxZ;
    explicit LookupSections(const Aabb& source) noexcept;
    bool contains(const EntityMetadata& target) const noexcept;
};
bool passesTeamRules(
        int sourceTeamId,
        int sourceRule,
        int targetTeamId,
        int targetRule
) noexcept;
} // namespace eco

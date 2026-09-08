#pragma once

#include "geometry/aabb.h"
#include "state/entity_metadata.h"

namespace eco {

// Immutable source-only bounds, computed once before visiting any candidates.
struct LookupSections {
    std::int64_t minX, minY, minZ, maxX, maxY, maxZ;
    explicit LookupSections(const Aabb& source) noexcept;
    bool contains(const EntityMetadata& target) const noexcept;
};
/** Source-dependent rule decisions are fixed for the entire query. */
struct TeamFilter {
    int sourceTeam;
    unsigned alliedRules, otherRules;
    TeamFilter(int sourceTeamId, int sourceRule) noexcept;
    bool accepts(int targetTeamId, int targetRule) const noexcept {
        unsigned allowed = targetTeamId == sourceTeam ? alliedRules : otherRules;
        return (allowed & (1u << targetRule)) != 0;
    }
};
} // namespace eco

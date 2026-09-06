#include "collision_rules.h"

#include "spatial_index.h"

namespace eco {

bool isInLookupSections(const Aabb& source, const EntityMetadata& target) noexcept {
    const auto minSectionX = cellCoordinate(source.minX - 2.0, 16);
    const auto minSectionY = cellCoordinate(source.minY - 4.0, 16);
    const auto minSectionZ = cellCoordinate(source.minZ - 2.0, 16);
    const auto maxSectionX = cellCoordinate(source.maxX + 2.0, 16);
    const auto maxSectionY = cellCoordinate(source.maxY, 16);
    const auto maxSectionZ = cellCoordinate(source.maxZ + 2.0, 16);
    return target.sectionX >= minSectionX && target.sectionX <= maxSectionX
            && target.sectionY >= minSectionY && target.sectionY <= maxSectionY
            && target.sectionZ >= minSectionZ && target.sectionZ <= maxSectionZ;
}

bool passesTeamRules(
        int sourceTeamId,
        int sourceRule,
        int targetTeamId,
        int targetRule
) noexcept {
    if (sourceRule == COLLISION_NEVER || targetRule == COLLISION_NEVER) {
        return false;
    }
    const bool allied = sourceTeamId >= 0 && sourceTeamId == targetTeamId;
    if ((sourceRule == COLLISION_PUSH_OWN_TEAM || targetRule == COLLISION_PUSH_OWN_TEAM)
            && allied) {
        return false;
    }
    return (sourceRule != COLLISION_PUSH_OTHER_TEAMS
            && targetRule != COLLISION_PUSH_OTHER_TEAMS) || allied;
}

} // namespace eco

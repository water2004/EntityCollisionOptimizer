#include "collision_rules.h"

#include <cmath>

namespace eco {
namespace {
std::int64_t sectionCoordinate(double value) noexcept {
    // SectionPos.posToSectionCoord: floor to a block BEFORE shifting to a section.
    // Dividing first can underflow tiny negative coordinates to -0.0 and select section 0.
    return static_cast<std::int64_t>(std::floor(value)) >> 4;
}
} // namespace

LookupSections::LookupSections(const Aabb& source) noexcept
    : minX(sectionCoordinate(source.minX - 2.0)),
      minY(sectionCoordinate(source.minY - 4.0)),
      minZ(sectionCoordinate(source.minZ - 2.0)),
      maxX(sectionCoordinate(source.maxX + 2.0)),
      maxY(sectionCoordinate(source.maxY)),
      maxZ(sectionCoordinate(source.maxZ + 2.0)) {}

bool LookupSections::contains(const EntityMetadata& target) const noexcept {
    return target.sectionX >= minX && target.sectionX <= maxX
            && target.sectionY >= minY && target.sectionY <= maxY
            && target.sectionZ >= minZ && target.sectionZ <= maxZ;
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

#include "query/collision_rules.h"

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

TeamFilter::TeamFilter(int sourceTeamId, int sourceRule) noexcept : sourceTeam(sourceTeamId) {
    otherRules = sourceRule == COLLISION_NEVER || sourceRule == COLLISION_PUSH_OTHER_TEAMS
            ? 0u : (1u << COLLISION_ALWAYS) | (1u << COLLISION_PUSH_OWN_TEAM);
    alliedRules = sourceRule == COLLISION_NEVER || sourceRule == COLLISION_PUSH_OWN_TEAM
            ? 0u : (1u << COLLISION_ALWAYS) | (1u << COLLISION_PUSH_OTHER_TEAMS);
    // Two absent teams are not allied. Equal sentinel IDs must select the non-allied rules too.
    if (sourceTeamId < 0) alliedRules = otherRules;
}

} // namespace eco

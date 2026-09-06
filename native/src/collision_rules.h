#pragma once

#include "collision_types.h"

namespace eco {

bool isInLookupSections(const Aabb& source, const EntityMetadata& target) noexcept;
bool passesTeamRules(
        int sourceTeamId,
        int sourceRule,
        int targetTeamId,
        int targetRule
) noexcept;
bool pushHasNoEffect(
        const EntityMetadata& source,
        const EntityMetadata& target,
        bool sourceUsesVanillaPush
) noexcept;

} // namespace eco

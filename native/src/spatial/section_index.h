#pragma once

#include "geometry/aabb.h"
#include "state/collision_context.h"

#include <cstdint>

namespace eco {

void insertSectionEntity(CollisionContext& context, int nativeId, const Aabb& bounds);
void removeSectionEntity(CollisionContext& context, int nativeId);
void updateSectionEntity(
        CollisionContext& context,
        int nativeId,
        std::int32_t sectionX,
        std::int32_t sectionY,
        std::int32_t sectionZ
);
const CellMembers* sectionEntities(const CollisionContext& context, const Cell& section) noexcept;

} // namespace eco

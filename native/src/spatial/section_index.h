#pragma once

#include "state/collision_context.h"

#include <cstdint>

namespace eco {

void insertSectionEntity(CollisionContext& context, int nativeId);
void removeSectionEntity(CollisionContext& context, int nativeId);
void updateSectionEntity(
        CollisionContext& context,
        int nativeId,
        std::int32_t sectionX,
        std::int32_t sectionY,
        std::int32_t sectionZ,
        std::int64_t sectionOrder
);
const CellMembers* sectionEntities(CollisionContext& context, const Cell& section);
void invalidateSectionOrder(CollisionContext& context, int nativeId) noexcept;

} // namespace eco

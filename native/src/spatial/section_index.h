#pragma once

#include "state/collision_context.h"

#include <cstdint>

namespace eco {

void rebuildSectionIndex(CollisionContext& context);
void insertSectionEntity(CollisionContext& context, int entityId);
void removeSectionEntity(CollisionContext& context, int entityId);
void updateSectionEntity(
        CollisionContext& context,
        int entityId,
        std::int32_t sectionX,
        std::int32_t sectionY,
        std::int32_t sectionZ
#if ECO_VANILLA_ORDER
        , std::int64_t sectionOrder
#endif
);
const std::vector<int>* sectionEntities(CollisionContext& context, const Cell& section);
#if ECO_VANILLA_ORDER
void invalidateSectionOrder(CollisionContext& context, int entityId) noexcept;
#endif

} // namespace eco

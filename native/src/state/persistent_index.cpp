#include "eco/collision_api.h"
#include "state/collision_context.h"
#include "spatial/spatial_index.h"
#include "spatial/section_index.h"

int insertCollisionEntity(
        void* pointer, int id, const double* bounds, int x, int y, int z,
        std::int64_t sectionOrder
) {
    if (!pointer || !bounds || id < 0) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) > context.boxes.size()) return -1;
        if (static_cast<std::size_t>(id) == context.boxes.size()) {
            context.boxes.emplace_back(); context.metadata.emplace_back();
            context.sectionSlots.push_back({nullptr, 0});
        } else if (context.sectionSlots[id].members != nullptr) return -1;
        if (context.metadata[id].hardCollidable) --context.hardEntityCount;
        context.metadata[id] = {};
        context.metadata[id].sectionX = x; context.metadata[id].sectionY = y; context.metadata[id].sectionZ = z;
        context.metadata[id].sectionOrder = sectionOrder;
        eco::updateEntityBounds(context, id, eco::makeAabb(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
        eco::insertSectionEntity(context, id);
        return 0;
    } catch (...) { return -2; }
}

int removeCollisionEntity(void* pointer, int id) {
    if (!pointer || id < 0) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) >= context.boxes.size()) return -1;
        eco::removeSectionEntity(context, id);
        if (context.metadata[id].hardCollidable) --context.hardEntityCount;
        context.boxes[id] = {}; context.metadata[id] = {};
        return 0;
    } catch (...) { return -2; }
}

int updateCollisionEntitySection(
        void* pointer, int id, int x, int y, int z, std::int64_t sectionOrder
) {
    if (!pointer || id < 0) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) >= context.boxes.size()) return -1;
        auto& metadata = context.metadata[id];
        eco::updateSectionEntity(context, id, x, y, z, sectionOrder);
        if (metadata.selectableValid && !metadata.selectable) {
            eco::updateEntityQueryability(context, id, true);
        }
        metadata.selectableValid = false;
        return 0;
    } catch (...) { return -2; }
}

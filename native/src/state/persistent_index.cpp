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
        auto& c = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) > c.boxes.size()) return -1;
        if (static_cast<std::size_t>(id) == c.boxes.size()) {
            c.boxes.emplace_back(); c.metadata.emplace_back();
            c.sectionSlots.push_back({nullptr, 0});
        } else if (c.sectionSlots[id].members != nullptr) return -1;
        if (c.metadata[id].hardCollidable) --c.hardEntityCount;
        c.metadata[id] = {};
        c.metadata[id].sectionX = x; c.metadata[id].sectionY = y; c.metadata[id].sectionZ = z;
        c.metadata[id].sectionOrder = sectionOrder;
        eco::updateEntityBounds(c, id, eco::makeAabb(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
        eco::insertSectionEntity(c, id);
        return 0;
    } catch (...) { return -2; }
}

int removeCollisionEntity(void* pointer, int id) {
    if (!pointer || id < 0) return -1;
    try {
        auto& c = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) >= c.boxes.size()) return -1;
        eco::removeSectionEntity(c, id);
        if (c.metadata[id].hardCollidable) --c.hardEntityCount;
        c.boxes[id] = {}; c.metadata[id] = {};
        return 0;
    } catch (...) { return -2; }
}

int updateCollisionEntitySection(
        void* pointer, int id, int x, int y, int z, std::int64_t sectionOrder
) {
    if (!pointer || id < 0) return -1;
    try {
        auto& c = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) >= c.boxes.size()) return -1;
        auto& m = c.metadata[id];
        eco::updateSectionEntity(c, id, x, y, z, sectionOrder);
        if (m.selectableValid && !m.selectable) {
            eco::updateEntityQueryability(c, id, true);
        }
        m.selectableValid = false;
        return 0;
    } catch (...) { return -2; }
}

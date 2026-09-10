#include "eco/collision_api.h"
#include "state/collision_context.h"
#include "spatial/spatial_index.h"
#include "spatial/section_index.h"

int putCollisionEntity(
        void* pointer, int id, const double* bounds, int x, int y, int z
#if ECO_VANILLA_ORDER
        ,
        std::int64_t sectionOrder
#endif
) {
    if (!pointer || !bounds || id < 0) return -1;
    try {
        auto& c = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) > c.boxes.size()) return -1;
        if (static_cast<std::size_t>(id) == c.boxes.size()) {
            c.boxes.emplace_back(); c.metadata.emplace_back();
            c.memberships.emplace_back(); c.queryMarks.push_back(0);
        } else if (!c.memberships[id].empty()) return -1;
        if (c.metadata[id].hardCollidable) --c.hardEntityCount;
        c.metadata[id] = {};
        c.queryMarks[id] = 0;
        c.metadata[id].sectionX = x; c.metadata[id].sectionY = y; c.metadata[id].sectionZ = z;
#if ECO_VANILLA_ORDER
        c.metadata[id].sectionOrder = sectionOrder;
#endif
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
        eco::removeMemberships(c, id, c.memberships[id]);
        c.memberships[id].clear();
        if (c.metadata[id].hardCollidable) --c.hardEntityCount;
        c.boxes[id] = {}; c.metadata[id] = {};
        return 0;
    } catch (...) { return -2; }
}

int updateCollisionLocation(
        void* pointer, int id, int x, int y, int z
#if ECO_VANILLA_ORDER
        , std::int64_t sectionOrder
#endif
) {
    if (!pointer || id < 0) return -1;
    try {
        auto& c = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) >= c.boxes.size()) return -1;
        auto& m = c.metadata[id];
        eco::updateSectionEntity(c, id, x, y, z
#if ECO_VANILLA_ORDER
                , sectionOrder
#endif
        );
        m.selectableValid = false;
        return 0;
    } catch (...) { return -2; }
}

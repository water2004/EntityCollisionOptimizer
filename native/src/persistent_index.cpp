#include "collision_api.h"
#include "collision_types.h"
#include "spatial_index.h"
#include "ordered_candidates.h"

int putCollisionEntity(void* pointer, int id, const double* bounds, int x, int y, int z) {
    if (!pointer || !bounds || id < 0) return -1;
    try {
        auto& c = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) > c.boxes.size()) return -1;
        if (static_cast<std::size_t>(id) == c.boxes.size()) {
            c.boxes.emplace_back(); c.metadata.emplace_back();
            c.memberships.emplace_back(); c.queryMarks.push_back(0);
        } else if (!c.memberships[id].empty()) return -1;
        c.metadata[id] = {};
        c.queryMarks[id] = 0;
        c.metadata[id].sectionX = x; c.metadata[id].sectionY = y; c.metadata[id].sectionZ = z;
        eco::updateEntityBounds(c, id, eco::makeAabb(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
        return 0;
    } catch (...) { return -2; }
}

int removeCollisionEntity(void* pointer, int id) {
    if (!pointer || id < 0) return -1;
    try {
        auto& c = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) >= c.boxes.size()) return -1;
        eco::removeMemberships(c, id, c.memberships[id]);
        c.memberships[id].clear();
        c.boxes[id] = {}; c.metadata[id] = {};
        return 0;
    } catch (...) { return -2; }
}

int updateCollisionLocation(void* pointer, int id, int x, int y, int z) {
    if (!pointer || id < 0) return -1;
    try {
        auto& c = *static_cast<eco::CollisionContext*>(pointer);
        if (static_cast<std::size_t>(id) >= c.boxes.size()) return -1;
        auto& m = c.metadata[id];
#if ECO_VANILLA_ORDER
        if (m.sectionX != x || m.sectionY != y || m.sectionZ != z) eco::invalidateCandidateOrder(c, id);
#endif
        m.sectionX = x; m.sectionY = y; m.sectionZ = z;
        m.selectableValid = false;
        return 0;
    } catch (...) { return -2; }
}

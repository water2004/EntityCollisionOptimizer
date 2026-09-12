#pragma once

#include "spatial/spatial_index.h"
#include <algorithm>

namespace eco {
#if !ECO_VANILLA_ORDER
/** Consume cell storage directly; callbacks see intersecting IDs in the original traversal order. */
template<class Consumer>
int visitIntersectingCandidates(CollisionContext& context, int sourceId, Consumer&& consume) {
    beginQuery(context);
    const Aabb source = context.boxes[sourceId];
    for (const CellSlot& slot : context.memberSlots[sourceId]) {
        const CellMembers& members = *slot.members;
        for (std::size_t index = 0; index < members.queryableCount; ++index) {
            const int id = members.ids[index];
            if (context.queryMarks[id] == context.queryGeneration) continue;
            context.queryMarks[id] = context.queryGeneration;
            if (!eco::intersects(source, context.boxes[id])) continue;
            const int status = consume(id);
            if (status != 0) return status;
        }
    }
    return 0;
}
#endif
} // namespace eco

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
        for (const int id : slot.members->ids) {
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

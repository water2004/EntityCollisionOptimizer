#pragma once

#include "candidate_mask.h"
#include <algorithm>
#include <bit>

namespace eco {
/** Consume cell storage directly; callbacks see intersecting IDs in the original traversal order. */
template<class Consumer>
int visitIntersectingCandidates(CollisionContext& context, int sourceId, Consumer&& consume) {
    beginQuery(context);
    const Aabb source = context.boxes[sourceId];
    for (const Cell& cell : context.memberships[sourceId]) {
        auto found = context.cells.find(cell);
        if (found == context.cells.end()) continue;
        const auto& ids = found->second.ids;
        for (std::size_t offset = 0; offset < ids.size(); offset += 4) {
            unsigned count = static_cast<unsigned>(std::min<std::size_t>(4, ids.size() - offset));
            const int* batch = ids.data() + offset;
            unsigned unseen = 0;
            for (unsigned lane = 0; lane < count; ++lane) {
                int id = batch[lane];
                if (context.queryMarks[id] == context.queryGeneration) continue;
                context.queryMarks[id] = context.queryGeneration;
                unseen |= 1u << lane;
            }
            if (unseen == 0) continue;
            unsigned hits = intersectionMask(source, context.boxes.data(), batch, count) & unseen;
            while (hits != 0) {
                unsigned lane = std::countr_zero(hits);
                hits &= hits - 1;
                int id = batch[lane];
                int status = consume(id);
                if (status != 0) return status;
            }
        }
    }
    return 0;
}
} // namespace eco

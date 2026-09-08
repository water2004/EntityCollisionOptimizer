#pragma once

#include "spatial_index.h"

namespace eco {
/** Read cell IDs in place. Independent comparisons expose the small batch to the vectorizer. */
inline unsigned intersectionMask(const Aabb& source, const Aabb* boxes, const int* ids, unsigned count) {
    unsigned mask = 0;
    for (unsigned lane = 0; lane < count; ++lane) {
        const Aabb& target = boxes[ids[lane]];
        // Non-short-circuit comparisons retain strict boundary/NaN semantics without control flow.
        const bool hit = (source.minX < target.maxX) & (source.maxX > target.minX)
                & (source.minY < target.maxY) & (source.maxY > target.minY)
                & (source.minZ < target.maxZ) & (source.maxZ > target.minZ);
        mask |= static_cast<unsigned>(hit) << lane;
    }
    return mask;
}
} // namespace eco

#pragma once

#include "collision_types.h"

namespace eco {

// Only changes to membership or the ordering key invalidate a cell's reusable order.
void invalidateCandidateOrder(CollisionContext& context, int entityId);

/** Merge ordered cell streams. Filtering their union preserves vanilla traversal order. */
class OrderedCandidates {
public:
    OrderedCandidates(CollisionContext& context, int sourceId);
    int next(); // -1 when exhausted; a repeated ID from overlapping cells is returned only once.

private:
    CollisionContext& context;
};

} // namespace eco

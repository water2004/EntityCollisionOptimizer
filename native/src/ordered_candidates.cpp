#include "ordered_candidates.h"

#include <algorithm>

namespace eco {
namespace {

bool before(const CollisionContext& context, int a, int b) noexcept {
    const auto& left = context.metadata[a];
    const auto& right = context.metadata[b];
    // Vanilla's signed packed section keys: signed X, unsigned Z/Y, then insertion order.
    if (left.sectionX != right.sectionX) return left.sectionX < right.sectionX;
    const auto lz = left.sectionZ & 0x3fffff, rz = right.sectionZ & 0x3fffff;
    if (lz != rz) return lz < rz;
    const auto ly = left.sectionY & 0xfffff, ry = right.sectionY & 0xfffff;
    if (ly != ry) return ly < ry;
    if (left.sectionOrder != right.sectionOrder) return left.sectionOrder < right.sectionOrder;
    // Unresolved metadata can temporarily share an order key. A total order keeps duplicates adjacent.
    return a < b;
}

struct Later {
    const CollisionContext& context;
    bool operator()(const CandidateCursor& a, const CandidateCursor& b) const noexcept {
        return before(context, b.entity(), a.entity());
    }
};

// Two axis-aligned cell ranges have one component-wise minimum common cell.
// Emit the candidate only there, before heap merging rather than after it.
// Filtering each sorted stream preserves its order, including section/insertion order.
bool seekOwned(CollisionContext& context, CandidateCursor& cursor) {
    if (cursor.ownershipAxes == 0) return cursor.index < cursor.ids->size();
    while (cursor.index < cursor.ids->size()) {
        const Cell& minimum = context.memberships[cursor.entity()].front();
        if (((cursor.ownershipAxes & 1) == 0 || minimum.x == cursor.cell.x)
                && ((cursor.ownershipAxes & 2) == 0 || minimum.y == cursor.cell.y)
                && ((cursor.ownershipAxes & 4) == 0 || minimum.z == cursor.cell.z)) return true;
        ++cursor.index;
    }
    return false;
}

} // namespace

void invalidateCandidateOrder(CollisionContext& context, int entityId) {
    for (const auto& cell : context.memberships[entityId]) {
        auto found = context.cells.find(cell);
        if (found != context.cells.end()) found->second.orderDirty = true;
    }
}

OrderedCandidates::OrderedCandidates(CollisionContext& context, int sourceId) : context(context) {
    auto& heap = context.candidateHeap;
    heap.clear(); // Previous query's cursors must never survive mutation of the spatial index.
    if (context.memberships[sourceId].empty()) return;
    const Cell& minimum = context.memberships[sourceId].front();
    for (const auto& cell : context.memberships[sourceId]) {
        auto found = context.cells.find(cell);
        if (found == context.cells.end()) continue;
        auto& members = found->second;
        if (members.orderDirty) {
            std::sort(members.ids.begin(), members.ids.end(), [&context](int a, int b) {
                return before(context, a, b);
            });
            members.orderDirty = false;
        }
        unsigned ownershipAxes = (cell.x > minimum.x ? 1u : 0u)
                | (cell.y > minimum.y ? 2u : 0u) | (cell.z > minimum.z ? 4u : 0u);
        CandidateCursor cursor{&members.ids, 0, cell, ownershipAxes};
        if (seekOwned(context, cursor)) heap.push_back(cursor);
    }
    std::make_heap(heap.begin(), heap.end(), Later{context});
}

int OrderedCandidates::next() {
    auto& heap = context.candidateHeap;
    if (heap.empty()) return -1;
    const int id = heap.front().entity();
    ++heap.front().index;
    if (!seekOwned(context, heap.front())) {
        heap.front() = heap.back();
        heap.pop_back();
    }
    if (heap.empty()) return id;

    // A stream only advances forward. Repair the root once instead of doing
    // separate pop/push heap operations (and moving the cursor twice).
    const CandidateCursor cursor = heap.front();
    std::size_t parent = 0;
    for (std::size_t child = 1; child < heap.size(); child = parent * 2 + 1) {
        if (child + 1 < heap.size()
                && before(context, heap[child + 1].entity(), heap[child].entity())) ++child;
        if (!before(context, heap[child].entity(), cursor.entity())) break;
        heap[parent] = heap[child];
        parent = child;
    }
    heap[parent] = cursor;
    return id;
}

} // namespace eco

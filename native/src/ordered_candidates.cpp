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
        if (!members.ids.empty()) heap.push_back({&members.ids, 0});
    }
    std::make_heap(heap.begin(), heap.end(), Later{context});
}

int OrderedCandidates::next() {
    auto& heap = context.candidateHeap;
    while (!heap.empty()) {
        std::pop_heap(heap.begin(), heap.end(), Later{context});
        auto cursor = heap.back();
        heap.pop_back();
        int id = cursor.entity();
        if (++cursor.index < cursor.ids->size()) {
            heap.push_back(cursor);
            std::push_heap(heap.begin(), heap.end(), Later{context});
        }
        if (id == previous) continue;
        previous = id;
        return id;
    }
    return -1;
}

} // namespace eco

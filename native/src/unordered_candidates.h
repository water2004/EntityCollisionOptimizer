#pragma once

#include "spatial_index.h"

namespace eco {
/** Direct grid traversal. Only query deduplication state, no ordering streams or keys. */
class UnorderedCandidates {
    CollisionContext& context;
    const std::vector<Cell>& cells;
    std::size_t cellIndex = 0, memberIndex = 0;
    const std::vector<int>* members = nullptr;
public:
    UnorderedCandidates(CollisionContext& context, int sourceId)
            : context(context), cells(context.memberships[sourceId]) {
        beginQuery(context);
    }
    int next() {
        for (;;) {
            if (members && memberIndex < members->size()) {
                int id = (*members)[memberIndex++];
                if (context.queryMarks[id] == context.queryGeneration) continue;
                context.queryMarks[id] = context.queryGeneration;
                return id;
            }
            if (cellIndex == cells.size()) return -1;
            auto found = context.cells.find(cells[cellIndex++]);
            members = found == context.cells.end() ? nullptr : &found->second.ids;
            memberIndex = 0;
        }
    }
};
} // namespace eco

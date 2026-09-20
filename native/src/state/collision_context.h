#pragma once

#include "geometry/aabb.h"
#include "state/entity_metadata.h"
#include "spatial/cell_map.h"

#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

namespace eco {

// CellMembers live in a stable deque pool, so backreferences stay valid while
// the flat map rehashes its entries.  Erasure retires the corresponding slots.
struct CellSlot {
    CellMembers* members;
    std::size_t index;
};

struct CollisionContext {
    std::vector<Aabb> boxes;
    std::vector<EntityMetadata> metadata;
    CellMap sections;
    std::deque<CellMembers> sectionMembersPool;
    CellMembers* freeSectionMembers = nullptr;
    std::vector<CellSlot> sectionSlots;
    std::vector<int> metadataMisses;
    // Exact live hard-collidable population; hard-only queries exit early at zero.
    std::size_t hardEntityCount = 0;

    CellMembers& acquireSectionMembers() {
        if (freeSectionMembers != nullptr) {
            CellMembers* members = freeSectionMembers;
            freeSectionMembers = members->poolNext;
            members->poolNext = nullptr;
            return *members;
        }
        sectionMembersPool.emplace_back();
        return sectionMembersPool.back();
    }

    void retireSectionMembers(CellMembers* members) {
        members->ids.clear();
        members->queryable.clear();
        members->bounds.clear();
        members->queryableCount = 0;
        members->hardCount = 0;
        members->poolNext = freeSectionMembers;
        freeSectionMembers = members;
    }
};

} // namespace eco

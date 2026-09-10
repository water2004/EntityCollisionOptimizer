#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

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

struct CandidateCursor {
    const std::vector<int>* ids;
    std::size_t index;
    Cell cell;
    unsigned ownershipAxes;
    int entity() const noexcept { return (*ids)[index]; }
};

struct CollisionContext {
    int gridSize = 1;
    std::vector<Aabb> boxes;
    std::vector<EntityMetadata> metadata;
    std::vector<std::vector<Cell>> memberships;
    std::vector<std::vector<CellSlot>> memberSlots;
    CellMap cells;
    std::deque<CellMembers> membersPool;
    CellMembers* freeMembers = nullptr;
#if ECO_VANILLA_ORDER
    std::vector<CandidateCursor> candidateHeap;
#endif
    std::vector<std::uint32_t> queryMarks;
    std::vector<int> metadataMisses;
    std::uint32_t queryGeneration = 0;
    // Exact live hard-collidable population; hard-only queries exit early at zero.
    std::size_t hardEntityCount = 0;

    CellMembers& acquireMembers() {
        if (freeMembers != nullptr) {
            CellMembers* members = freeMembers;
            freeMembers = members->poolNext;
            members->poolNext = nullptr;
            return *members;
        }
        membersPool.emplace_back();
        return membersPool.back();
    }

    void retireMembers(CellMembers* members) {
        members->ids.clear();
        members->hardCount = 0;
#if ECO_VANILLA_ORDER
        members->orderDirty = true;
#endif
        members->poolNext = freeMembers;
        freeMembers = members;
    }

    void clearCellsAndPool() {
        cells.clear();
        membersPool.clear();
        freeMembers = nullptr;
    }
};

} // namespace eco
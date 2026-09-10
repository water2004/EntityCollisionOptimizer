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

#if ECO_VANILLA_ORDER
struct OrderedSectionCandidates {
    const std::vector<int>* ids = nullptr;
    std::vector<std::uint64_t> bits;
};
#endif

struct CollisionContext {
    int gridSize = 1;
    std::vector<Aabb> boxes;
    std::vector<EntityMetadata> metadata;
    std::vector<std::vector<Cell>> memberships;
    std::vector<std::vector<CellSlot>> memberSlots;
    CellMap cells;
    std::deque<CellMembers> membersPool;
    CellMembers* freeMembers = nullptr;
    CellMap sections;
    std::deque<CellMembers> sectionMembersPool;
    CellMembers* freeSectionMembers = nullptr;
    std::vector<CellSlot> sectionSlots;
#if ECO_VANILLA_ORDER
    // Reused query scratch.  Bits select fine-grid hits while ids retain the
    // persistent section/insertion order.
    std::vector<OrderedSectionCandidates> orderedSections;
    std::size_t orderedSectionCount = 0;
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
#if ECO_VANILLA_ORDER
        members->bounds.clear();
#endif
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
#if ECO_VANILLA_ORDER
        members->orderDirty = true;
#endif
        members->poolNext = freeSectionMembers;
        freeSectionMembers = members;
    }

    void clearSectionsAndPool() {
        sections.clear();
        sectionMembersPool.clear();
        freeSectionMembers = nullptr;
        sectionSlots.clear();
    }
};

} // namespace eco

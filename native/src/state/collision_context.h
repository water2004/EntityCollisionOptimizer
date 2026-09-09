#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

#include "geometry/aabb.h"
#include "state/entity_metadata.h"
#if !ECO_VANILLA_ORDER
#include "spatial/cell_geometry.h"
#endif

#include <cstddef>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace eco {

struct Cell {
    std::int64_t x;
    std::int64_t y;
    std::int64_t z;

    bool operator==(const Cell&) const = default;
};

struct CellHash {
    std::size_t operator()(const Cell& cell) const noexcept;
};

struct CellMembers {
    std::vector<int> ids;
#if ECO_VANILLA_ORDER
    bool orderDirty = true;
#else
    CellGeometry geometry;
#endif
};

#if !ECO_VANILLA_ORDER
// unordered_map rehash preserves element addresses. Erasure retires the corresponding slots.
struct CellSlot {
    CellMembers* members;
    std::size_t index;
};
#endif

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
#if !ECO_VANILLA_ORDER
    std::vector<std::vector<CellSlot>> memberSlots;
#endif
    std::unordered_map<Cell, CellMembers, CellHash> cells;
#if ECO_VANILLA_ORDER
    std::vector<CandidateCursor> candidateHeap;
#endif
    std::vector<std::uint32_t> queryMarks;
    std::vector<int> metadataMisses;
    std::uint32_t queryGeneration = 0;
};

} // namespace eco

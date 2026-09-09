#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

#include "geometry/aabb.h"
#include "state/entity_metadata.h"
#include "spatial/cell_geometry.h"

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
#endif
    CellGeometry geometry;
};

// unordered_map rehash preserves element addresses. Erasure retires the corresponding slots.
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
    std::unordered_map<Cell, CellMembers, CellHash> cells;
#if ECO_VANILLA_ORDER
    std::vector<CandidateCursor> candidateHeap;
#endif
    std::vector<std::uint32_t> queryMarks;
    std::vector<int> metadataMisses;
    std::uint32_t queryGeneration = 0;
};

} // namespace eco

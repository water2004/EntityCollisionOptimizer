#pragma once

#include "spatial/cell_geometry.h"
#include <cstdint>

namespace eco {
/** Contiguous field columns permit automatic vectorization across candidates. */
inline unsigned intersectionMask(const Aabb& source, const CellGeometryBlock& targets) {
    std::uint64_t lanes[CellGeometryBlock::WIDTH];
    for (unsigned lane = 0; lane < CellGeometryBlock::WIDTH; ++lane) {
        // Non-short-circuit comparisons retain strict boundary/NaN semantics without control flow.
        const bool hit = (source.minX < targets.maxX[lane]) & (source.maxX > targets.minX[lane])
                & (source.minY < targets.maxY[lane]) & (source.maxY > targets.minY[lane])
                & (source.minZ < targets.maxZ[lane]) & (source.maxZ > targets.minZ[lane]);
        lanes[lane] = static_cast<std::uint64_t>(hit) << lane;
    }
    std::uint64_t mask = 0;
    for (auto lane : lanes) mask |= lane;
    return static_cast<unsigned>(mask);
}
} // namespace eco

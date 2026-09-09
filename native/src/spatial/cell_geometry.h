#pragma once

#include "geometry/aabb.h"
#include <cstddef>
#include <vector>

namespace eco {
/** Resident candidate columns. Geometry is updated on writes, never packed by queries. */
struct alignas(32) CellGeometryBlock {
    static constexpr unsigned WIDTH = 8;
    double minX[WIDTH]{}, minY[WIDTH]{}, minZ[WIDTH]{};
    double maxX[WIDTH]{}, maxY[WIDTH]{}, maxZ[WIDTH]{};
    unsigned hardMask = 0;
};

class CellGeometry {
    std::vector<CellGeometryBlock> blocks;
public:
    const CellGeometryBlock& block(std::size_t index) const { return blocks[index]; }
    void resize(std::size_t count) { blocks.resize((count + CellGeometryBlock::WIDTH - 1) / CellGeometryBlock::WIDTH); }
    void writeHard(std::size_t index, bool hard) {
        auto& mask = blocks[index / CellGeometryBlock::WIDTH].hardMask;
        const unsigned bit = 1u << (index % CellGeometryBlock::WIDTH);
        mask = hard ? mask | bit : mask & ~bit;
    }
    void write(std::size_t index, const Aabb& box) {
        auto& b = blocks[index / CellGeometryBlock::WIDTH];
        const auto lane = index % CellGeometryBlock::WIDTH;
        b.minX[lane] = box.minX; b.minY[lane] = box.minY; b.minZ[lane] = box.minZ;
        b.maxX[lane] = box.maxX; b.maxY[lane] = box.maxY; b.maxZ[lane] = box.maxZ;
    }
};
} // namespace eco

#pragma once

#include "geometry/aabb.h"

#include <cstddef>
#include <vector>

namespace eco {

// Bounds are duplicated beside spatial membership slots so a candidate scan
// reads contiguous columns instead of gathering six fields through entity IDs.
struct CellBoundsSoa {
    std::vector<double> minX, minY, minZ;
    std::vector<double> maxX, maxY, maxZ;

    void push(const Aabb& box) {
        minX.push_back(box.minX);
        minY.push_back(box.minY);
        minZ.push_back(box.minZ);
        maxX.push_back(box.maxX);
        maxY.push_back(box.maxY);
        maxZ.push_back(box.maxZ);
    }

    void set(std::size_t index, const Aabb& box) noexcept {
        minX[index] = box.minX;
        minY[index] = box.minY;
        minZ[index] = box.minZ;
        maxX[index] = box.maxX;
        maxY[index] = box.maxY;
        maxZ[index] = box.maxZ;
    }

    void erase(std::size_t index) {
        const auto offset = static_cast<std::ptrdiff_t>(index);
        minX.erase(minX.begin() + offset);
        minY.erase(minY.begin() + offset);
        minZ.erase(minZ.begin() + offset);
        maxX.erase(maxX.begin() + offset);
        maxY.erase(maxY.begin() + offset);
        maxZ.erase(maxZ.begin() + offset);
    }

    void clear() noexcept {
        minX.clear();
        minY.clear();
        minZ.clear();
        maxX.clear();
        maxY.clear();
        maxZ.clear();
    }
};

} // namespace eco

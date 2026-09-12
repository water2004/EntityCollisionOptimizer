#pragma once

#include "geometry/aabb.h"

#include <cstddef>
#include <utility>
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

    void swap(std::size_t first, std::size_t second) noexcept {
        using std::swap;
        swap(minX[first], minX[second]);
        swap(minY[first], minY[second]);
        swap(minZ[first], minZ[second]);
        swap(maxX[first], maxX[second]);
        swap(maxY[first], maxY[second]);
        swap(maxZ[first], maxZ[second]);
    }

    void popBack() {
        minX.pop_back(); minY.pop_back(); minZ.pop_back();
        maxX.pop_back(); maxY.pop_back(); maxZ.pop_back();
    }

    void swapErase(std::size_t index) {
        const std::size_t last = minX.size() - 1;
        if (index != last) {
            minX[index] = minX[last];
            minY[index] = minY[last];
            minZ[index] = minZ[last];
            maxX[index] = maxX[last];
            maxY[index] = maxY[last];
            maxZ[index] = maxZ[last];
        }
        minX.pop_back();
        minY.pop_back();
        minZ.pop_back();
        maxX.pop_back();
        maxY.pop_back();
        maxZ.pop_back();
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

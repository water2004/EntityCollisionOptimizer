#include "geometry/voxel_geometry.h"

#include <algorithm>
#include <cmath>

namespace eco {
namespace {

// Exact specialization of the voxel index inequalities for a single occupied cell.
// Do not use an arbitrary shape's outer bounds: internal grid planes affect penetration.
inline double clipSingleCell(const VoxelRef& shape, int axis, const double* box, double distance) {
    const auto* coordinates = reinterpret_cast<const double*>(shape.geometry() + 1);
    const auto coordinate = [&](int a, int end) {
        double value = coordinates[2 * a + end];
        return shape.translated() ? value + shape.offset[a] : value;
    };

    double gap;
    if (distance > 0.0) {
        double face = coordinate(axis, 0);
        if (!(box[axis + 3] - 1.0e-7 < face)) return distance;
        gap = face - box[axis + 3];
        if (gap < -1.0e-7 || distance < gap) return distance;
    } else if (distance < 0.0) {
        double face = coordinate(axis, 1);
        if (box[axis] + 1.0e-7 < face) return distance;
        gap = face - box[axis];
        if (gap > 1.0e-7 || distance > gap) return distance;
    } else return distance;

    int b = (axis + 1) % 3, c = (axis + 2) % 3;
    if (box[b + 3] - 1.0e-7 < coordinate(b, 0) || !(box[b] + 1.0e-7 < coordinate(b, 1))) return distance;
    if (box[c + 3] - 1.0e-7 < coordinate(c, 0) || !(box[c] + 1.0e-7 < coordinate(c, 1))) return distance;
    return gap;
}

} // namespace

const double* VoxelGeometry::coordinates(int axis) const {
    auto* data = reinterpret_cast<const double*>(this + 1);
    for (int i = 0; i < axis; ++i) data += size[i] + 1;
    return data;
}

bool VoxelGeometry::full(const int cell[3]) const {
    for (int axis = 0; axis < 3; ++axis) {
        if (cell[axis] < 0 || cell[axis] >= size[axis]) return false;
    }
    const auto* bits = reinterpret_cast<const std::uint64_t*>(coordinates(2) + size[2] + 1);
    const auto index = (static_cast<std::uint64_t>(cell[0]) * size[1] + cell[1]) * size[2] + cell[2];
    return (bits[index >> 6] & (std::uint64_t{1} << (index & 63))) != 0;
}

double VoxelRef::coordinate(int axis, int index) const {
    double value = geometry()->coordinates(axis)[index];
    // Match OffsetDoubleList: add to the coordinate, do not subtract from the query (rounding differs).
    return translated() ? value + offset[axis] : value;
}

int VoxelRef::index(int axis, double value) const {
    const int size = geometry()->size[axis];
    if (!translated() && (geometry()->flags & 2)) {
        // CubeVoxelShape's specialized, clamped floor. Avoid out-of-range C++ integer conversions.
        double scaled = std::floor(value * size);
        if (scaled < -1) return -1;
        if (scaled >= size) return size;
        return std::isnan(scaled) ? 0 : static_cast<int>(scaled);
    }
    int first = 0, length = size + 1;
    while (length > 0) {
        int half = length / 2, middle = first + half;
        if (value < coordinate(axis, middle)) length = half;
        else { first = middle + 1; length -= half + 1; }
    }
    return first - 1;
}

double clipVoxel(const VoxelRef& shape, int axis, const double* box, double distance) {
    const auto* geometry = shape.geometry();
    if (geometry->flags & 1) return distance;
    if (std::abs(distance) < 1.0e-7) return 0.0;
    if (geometry->flags & 4) return clipSingleCell(shape, axis, box, distance);
    int b = (axis + 1) % 3, c = (axis + 2) % 3;
    int lowB = std::max(0, shape.index(b, box[b] + 1.0e-7));
    int highB = std::min(geometry->size[b], shape.index(b, box[b + 3] - 1.0e-7) + 1);
    int lowC = std::max(0, shape.index(c, box[c] + 1.0e-7));
    int highC = std::min(geometry->size[c], shape.index(c, box[c + 3] - 1.0e-7) + 1);
    const bool positive = distance > 0.0;
    int start = positive ? shape.index(axis, box[axis + 3] - 1.0e-7) + 1
                         : shape.index(axis, box[axis] + 1.0e-7) - 1;
    int cell[3]{};
    for (int a = start; positive ? a < geometry->size[axis] : a >= 0; a += positive ? 1 : -1) {
        cell[axis] = a;
        for (cell[b] = lowB; cell[b] < highB; ++cell[b]) {
            for (cell[c] = lowC; cell[c] < highC; ++cell[c]) {
                if (!geometry->full(cell)) continue;
                double gap = shape.coordinate(axis, positive ? a : a + 1) - box[axis + (positive ? 3 : 0)];
                if (positive && gap >= -1.0e-7) return std::fmin(distance, gap);
                if (!positive && gap <= 1.0e-7) return std::fmax(distance, gap);
                return distance;
            }
        }
    }
    return distance;
}

void clipMovement(const double* requested, const double* box, const VoxelRef* shapes, int count, double* result) {
    if (count == 0) { std::copy_n(requested, 3, result); return; }
    std::fill_n(result, 3, 0.0);
    int order[3]{1, 0, 2};
    if (std::abs(requested[0]) < std::abs(requested[2])) std::swap(order[1], order[2]);
    for (int axis : order) {
        double distance = requested[axis];
        if (distance == 0.0) continue;
        double moved[6];
        for (int i = 0; i < 6; ++i) moved[i] = box[i] + result[i % 3];
        for (int i = 0; i < count; ++i) {
            if (std::abs(distance) < 1.0e-7) { distance = 0.0; break; }
            distance = clipVoxel(shapes[i], axis, moved, distance);
        }
        result[axis] = distance;
    }
}
}

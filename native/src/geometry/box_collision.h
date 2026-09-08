#pragma once

#include "geometry/voxel_geometry.h"

namespace eco {
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
}

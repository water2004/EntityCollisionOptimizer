#pragma once

#include <cstdint>

namespace eco {
// Immutable voxel grid: header, three coordinate arrays, then X/Y/Z ordered occupancy bits.
// Java retains its owning MemorySegment for the entire synchronous call.
struct VoxelGeometry {
    std::int32_t size[3], flags; // empty=1, unshifted CubeVoxelShape index=2, single occupied cell=4
    const double* coordinates(int axis) const;
    bool full(const int cell[3]) const;
};
static_assert(sizeof(VoxelGeometry) == 16);

struct VoxelRef {
    // Geometry is allocated at 8-byte alignment. Bit 0 records an explicit translation,
    // including +0 (which must not change an unshifted -0 coordinate).
    std::uintptr_t geometryTag;
    double offset[3];
    const VoxelGeometry* geometry() const {
        return reinterpret_cast<const VoxelGeometry*>(geometryTag & ~std::uintptr_t{1});
    }
    bool translated() const { return (geometryTag & 1) != 0; }
    double coordinate(int axis, int index) const;
    int index(int axis, double value) const;
};
static_assert(sizeof(VoxelRef) == 32);

double clipVoxel(const VoxelRef& shape, int axis, const double* box, double distance);
void clipMovement(const double* requested, const double* box, const VoxelRef* shapes, int count, double* result);
}

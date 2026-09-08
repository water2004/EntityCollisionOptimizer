#include "eco/collision_api.h"
#include "motion/collision_body.h"
#include "geometry/voxel_geometry.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace {
// Shared packet layout with NativeMovement. No Java callback executes while native borrows a body.
constexpr int BOX = 0, REQUEST = 6, POSITION = 9, RESULT = 12, TARGET = 15,
              STEP_BASE = 18, STEP_SCAN = 24, MAX_STEP = 30, GROUNDED = 31,
              NEEDS_STEP = 32, ENTITY_QUERY = 33;
double horizontal(const double* value) { return value[0] * value[0] + value[2] * value[2]; }
double lengthSquared(const double* value) { return value[0] * value[0] + value[1] * value[1] + value[2] * value[2]; }

void initial(double* data, const eco::VoxelRef* shapes, int count) {
    auto* request = data + REQUEST;
    auto* clipped = data + RESULT;
    if (data[ENTITY_QUERY] != 0 && lengthSquared(request) == 0.0) std::copy_n(request, 3, clipped);
    else eco::clipMovement(request, data + BOX, shapes, count, clipped);
    bool landed = request[1] != clipped[1] && request[1] < 0.0;
    data[NEEDS_STEP] = data[MAX_STEP] > 0 && (landed || data[GROUNDED] != 0)
                      && (request[0] != clipped[0] || request[2] != clipped[2]);
    if (data[NEEDS_STEP] == 0) return;
    std::copy_n(data + BOX, 6, data + STEP_BASE);
    if (landed) {
        // AABB.move adds all three components, including positive zero.
        for (int i = 0; i < 6; ++i) data[STEP_BASE + i] += i % 3 == 1 ? clipped[1] : 0.0;
    }
    std::copy_n(data + STEP_BASE, 6, data + STEP_SCAN);
    double expansion[3]{request[0], data[MAX_STEP], request[2]};
    for (int axis = 0; axis < 3; ++axis) {
        if (expansion[axis] < 0) data[STEP_SCAN + axis] += expansion[axis];
        else if (expansion[axis] > 0) data[STEP_SCAN + axis + 3] += expansion[axis];
    }
    if (!landed) data[STEP_SCAN + 1] += -9.999999747378752E-6;
}

void step(double* data, const eco::VoxelRef* shapes, int count) {
    std::vector<float> heights;
    for (int i = 0; i < count; ++i) {
        const auto& shape = shapes[i];
        for (int y = 0; y <= shape.geometry()->size[1]; ++y) {
            float height = static_cast<float>(shape.coordinate(1, y) - data[STEP_BASE + 1]);
            if (height < 0 || height == static_cast<float>(data[RESULT + 1])) continue;
            if (height > static_cast<float>(data[MAX_STEP])) break;
            heights.push_back(height);
        }
    }
    std::sort(heights.begin(), heights.end());
    heights.erase(std::unique(heights.begin(), heights.end()), heights.end());
    for (float height : heights) {
        double request[3]{data[REQUEST], height, data[REQUEST + 2]}, result[3];
        eco::clipMovement(request, data + STEP_BASE, shapes, count, result);
        if (horizontal(result) > horizontal(data + RESULT)) {
            data[RESULT] = result[0] - 0.0;
            data[RESULT + 1] = result[1] - (data[BOX + 1] - data[STEP_BASE + 1]);
            data[RESULT + 2] = result[2] - 0.0;
            break;
        }
    }
}
}

int prepareMovement(const double* bounds, double* data) {
    if (!data) return -1;
    if (bounds) std::copy_n(bounds, 6, data + BOX);
    std::copy_n(data + BOX, 6, data + STEP_SCAN);
    for (int axis = 0; axis < 3; ++axis) {
        double delta = data[REQUEST + axis];
        if (delta < 0) data[STEP_SCAN + axis] += delta;
        else if (delta > 0) data[STEP_SCAN + axis + 3] += delta;
    }
    return 0;
}

int solveMovement(const void* rawBody, double* data, const void* rawShapes, int count, int phase) {
    if (!data || count < 0 || (count && !rawShapes) || phase < 0 || phase > 1) return -1;
    try {
        const auto* shapes = static_cast<const eco::VoxelRef*>(rawShapes);
        if (phase == 0) initial(data, shapes, count);
        else step(data, shapes, count);
        if (rawBody) {
            const auto& body = *static_cast<const eco::CollisionBody*>(rawBody);
            data[POSITION] = body.x;
            data[POSITION + 1] = body.y;
            data[POSITION + 2] = body.z;
        }
        for (int axis = 0; axis < 3; ++axis) data[TARGET + axis] = data[POSITION + axis] + data[RESULT + axis];
        return 0;
    } catch (...) { return -2; }
}

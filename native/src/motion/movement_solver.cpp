#include "eco/collision_api.h"
#include "native_error.h"
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
double horizontal(const double* vector) { return vector[0] * vector[0] + vector[2] * vector[2]; }
double lengthSquared(const double* vector) {
    return vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2];
}

void initial(double* movementData, const eco::VoxelRef* shapeReferences, int shapeCount) {
    auto* request = movementData + REQUEST;
    auto* clipped = movementData + RESULT;
    if (movementData[ENTITY_QUERY] != 0 && lengthSquared(request) == 0.0) std::copy_n(request, 3, clipped);
    else eco::clipMovement(request, movementData + BOX, shapeReferences, shapeCount, clipped);
    bool landed = request[1] != clipped[1] && request[1] < 0.0;
    movementData[NEEDS_STEP] = movementData[MAX_STEP] > 0 && (landed || movementData[GROUNDED] != 0)
                      && (request[0] != clipped[0] || request[2] != clipped[2]);
    if (movementData[NEEDS_STEP] == 0) return;
    std::copy_n(movementData + BOX, 6, movementData + STEP_BASE);
    if (landed) {
        // AABB.move adds all three components, including positive zero.
        for (int i = 0; i < 6; ++i) movementData[STEP_BASE + i] += i % 3 == 1 ? clipped[1] : 0.0;
    }
    std::copy_n(movementData + STEP_BASE, 6, movementData + STEP_SCAN);
    double expansion[3]{request[0], movementData[MAX_STEP], request[2]};
    for (int axis = 0; axis < 3; ++axis) {
        if (expansion[axis] < 0) movementData[STEP_SCAN + axis] += expansion[axis];
        else if (expansion[axis] > 0) movementData[STEP_SCAN + axis + 3] += expansion[axis];
    }
    if (!landed) movementData[STEP_SCAN + 1] += -9.999999747378752E-6;
}

void step(double* movementData, const eco::VoxelRef* shapeReferences, int shapeCount) {
    std::vector<float> heights;
    for (int i = 0; i < shapeCount; ++i) {
        const auto& shape = shapeReferences[i];
        for (int y = 0; y <= shape.geometry()->size[1]; ++y) {
            float height = static_cast<float>(shape.coordinate(1, y) - movementData[STEP_BASE + 1]);
            if (height < 0 || height == static_cast<float>(movementData[RESULT + 1])) continue;
            if (height > static_cast<float>(movementData[MAX_STEP])) break;
            heights.push_back(height);
        }
    }
    std::sort(heights.begin(), heights.end());
    heights.erase(std::unique(heights.begin(), heights.end()), heights.end());
    for (float height : heights) {
        double request[3]{movementData[REQUEST], height, movementData[REQUEST + 2]}, result[3];
        eco::clipMovement(request, movementData + STEP_BASE, shapeReferences, shapeCount, result);
        if (horizontal(result) > horizontal(movementData + RESULT)) {
            movementData[RESULT] = result[0] - 0.0;
            movementData[RESULT + 1] = result[1] - (movementData[BOX + 1] - movementData[STEP_BASE + 1]);
            movementData[RESULT + 2] = result[2] - 0.0;
            break;
        }
    }
}
}

int prepareMovement(const double* entityBounds, double* movementData) {
    if (!movementData) return -1;
    if (entityBounds) std::copy_n(entityBounds, 6, movementData + BOX);
    std::copy_n(movementData + BOX, 6, movementData + STEP_SCAN);
    for (int axis = 0; axis < 3; ++axis) {
        double delta = movementData[REQUEST + axis];
        if (delta < 0) movementData[STEP_SCAN + axis] += delta;
        else if (delta > 0) movementData[STEP_SCAN + axis + 3] += delta;
    }
    return 0;
}

int solveMovement(
        const void* bodyPointer,
        double* movementData,
        const void* shapeReferencesPointer,
        int shapeCount,
        int movementPhase
) {
    if (!movementData || shapeCount < 0 || (shapeCount && !shapeReferencesPointer)
            || movementPhase < 0 || movementPhase > 1) return -1;
    try {
        const auto* shapeReferences = static_cast<const eco::VoxelRef*>(shapeReferencesPointer);
        if (movementPhase == 0) initial(movementData, shapeReferences, shapeCount);
        else step(movementData, shapeReferences, shapeCount);
        if (bodyPointer) {
            const auto& body = *static_cast<const eco::CollisionBody*>(bodyPointer);
            movementData[POSITION] = body.x;
            movementData[POSITION + 1] = body.y;
            movementData[POSITION + 2] = body.z;
        }
        for (int axis = 0; axis < 3; ++axis) {
            movementData[TARGET + axis] = movementData[POSITION + axis] + movementData[RESULT + axis];
        }
        return 0;
    } catch (...) { return eco::recordNativeException(); }
}

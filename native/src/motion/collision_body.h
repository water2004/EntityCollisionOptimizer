#pragma once

#include <cstddef>
#include <cstdint>

namespace eco {
// Shared ABI with CollisionStateTable. No C++ bool or platform-dependent fields.
struct CollisionBody {
    double x, z, vx, vy, vz;
    std::uint64_t velocityVersion;
    std::int32_t state, root, needsSync, reserved;
    double y;
    std::uint64_t positionVersion;
};
static_assert(sizeof(CollisionBody) == 80);
static_assert(offsetof(CollisionBody, y) == 64);
static_assert(offsetof(CollisionBody, positionVersion) == 72);
static_assert(offsetof(CollisionBody, velocityVersion) == 40);
static_assert(offsetof(CollisionBody, state) == 48);
static_assert(offsetof(CollisionBody, root) == 52);
static_assert(offsetof(CollisionBody, needsSync) == 56);
constexpr int PUSHABLE = 1, VEHICLE = 2, PASSENGER = 4, SLEEPING = 8, NO_PHYSICS = 16;
}

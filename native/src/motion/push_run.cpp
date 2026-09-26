#include "eco/collision_api.h"
#include "motion/collision_body.h"

#include <algorithm>
#include <cmath>

namespace {

constexpr double PUSH_EPSILON = 0.009999999776482582;

// Entity.push(Entity), retaining each vanilla division/multiplication in its original order.
inline bool pushImpulse(double sourceX, double sourceZ, double targetX, double targetZ,
                        double& x, double& z) noexcept {
    x = sourceX - targetX;
    z = sourceZ - targetZ;
    const double maximum = std::max(std::abs(x), std::abs(z));
    if (!(maximum >= PUSH_EPSILON)) return false;
    const double root = std::sqrt(maximum);
    x /= root;
    z /= root;
    const double inverse = std::min(1.0, 1.0 / root);
    x *= inverse;
    z *= inverse;
    x *= 0.05000000074505806;
    z *= 0.05000000074505806;
    return true;
}

void push(eco::CollisionBody& body, double x, double z) noexcept {
    // Minecraft 1.21.1 accepts non-finite inputs and sums without filtering.
    const double vx = body.vx + x;
    const double vy = body.vy + 0.0; // Preserve vanilla's signed-zero addition, too.
    const double vz = body.vz + z;
    body.needsSync = 1;
    body.vx = vx;
    body.vy = vy;
    body.vz = vz;
    ++body.velocityVersion;
}

bool acceptsImpulse(const eco::CollisionBody& body) noexcept {
    return (body.state & (eco::PUSHABLE | eco::VEHICLE)) == eco::PUSHABLE;
}

} // namespace

int executePushRun(
        void* sourceBodyPointer,
        void* targetBodiesPointer,
        int targetCapacity,
        const int* targetSlots,
        int targetCount
) {
    if (sourceBodyPointer == nullptr || targetBodiesPointer == nullptr || targetSlots == nullptr
            || targetCount < 0 || targetCapacity < 0) return -1;
    // Validate the whole request before modifying persistent state. Query deduplication supplies
    // unique targets; their original order, including source accumulation, remains unchanged.
    for (int i = 0; i < targetCount; ++i) {
        if (targetSlots[i] < 0 || targetSlots[i] >= targetCapacity) return -1;
    }
    auto& source = *static_cast<eco::CollisionBody*>(sourceBodyPointer);
    auto* targetBodies = static_cast<eco::CollisionBody*>(targetBodiesPointer);
    const bool pushSource = acceptsImpulse(source);
    if (source.state & eco::NO_PHYSICS) return 0;
    for (int i = 0; i < targetCount; ++i) {
        auto& target = targetBodies[targetSlots[i]];
        if (target.state & (eco::NO_PHYSICS | eco::SLEEPING)) continue;
        if (((source.state | target.state) & eco::PASSENGER) && source.root == target.root) continue;
        const bool pushTarget = acceptsImpulse(target);
        if (!pushSource && !pushTarget) continue;
        double x, z;
        if (!pushImpulse(source.x, source.z, target.x, target.z, x, z)) continue;
        if (pushTarget) push(target, -x, -z);
        // Source accumulation is strictly sequential: never reduce a sum of impulses first.
        if (pushSource) push(source, x, z);
    }
    return 0;
}

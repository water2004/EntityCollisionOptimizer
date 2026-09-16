#include "eco/collision_api.h"
#include "motion/collision_body.h"
#include "motion/push_math.h"

#include <cmath>

namespace {

void push(eco::CollisionBody& body, double x, double z) noexcept {
    // Entity.push first rejects non-finite input, then setDeltaMovement rejects non-finite sums.
    // The latter still sets needsSync; it does not partially accept individual components.
    if (!std::isfinite(x) || !std::isfinite(z)) return;
    const double vx = body.vx + x;
    const double vy = body.vy + 0.0; // Preserve vanilla's signed-zero addition, too.
    const double vz = body.vz + z;
    body.needsSync = 1;
    if (!std::isfinite(vx) || !std::isfinite(vy) || !std::isfinite(vz)) return;
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
        void* sourcePointer,
        void* targetPointer,
        int targetCapacity,
        const int* targetSlots,
        int count
) {
    if (sourcePointer == nullptr || targetPointer == nullptr || targetSlots == nullptr
            || count < 0 || targetCapacity < 0) return -1;
    // Validate the whole request before modifying persistent state. Query deduplication supplies
    // unique targets; their original order, including source accumulation, remains unchanged.
    for (int i = 0; i < count; ++i) {
        if (targetSlots[i] < 0 || targetSlots[i] >= targetCapacity) return -1;
    }
    auto& source = *static_cast<eco::CollisionBody*>(sourcePointer);
    auto* targets = static_cast<eco::CollisionBody*>(targetPointer);
    const bool pushSource = acceptsImpulse(source);
    if (source.state & eco::NO_PHYSICS) return 0;
    for (int i = 0; i < count; ++i) {
        auto& target = targets[targetSlots[i]];
        if (target.state & (eco::NO_PHYSICS | eco::SLEEPING)) continue;
        if (((source.state | target.state) & eco::PASSENGER) && source.root == target.root) continue;
        const bool pushTarget = acceptsImpulse(target);
        if (!pushSource && !pushTarget) continue;
        double x, z;
        if (!eco::pushImpulse(source.x, source.z, target.x, target.z, x, z)) continue;
        if (pushTarget) push(target, -x, -z);
        // Source accumulation is strictly sequential: never reduce a sum of impulses first.
        if (pushSource) push(source, x, z);
    }
    return 0;
}

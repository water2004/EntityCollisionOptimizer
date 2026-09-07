#include "collision_api.h"
#include "push_math.h"

#include <cmath>

namespace {

// Shared wire format with PushBatch: [x, z, vx, vy, vz]; source first, then unique targets.
constexpr int STRIDE = 5;
constexpr int PUSH_TARGET = 1;
constexpr int PUSH_SOURCE = 2;
constexpr int SYNC = 1;
constexpr int WRITE_VELOCITY = 2;

int push(double* body, double x, double z) noexcept {
    // Entity.push first rejects non-finite input, then setDeltaMovement rejects non-finite sums.
    // The latter still sets needsSync; it does not partially accept individual components.
    if (!std::isfinite(x) || !std::isfinite(z)) return 0;
    const double vx = body[2] + x;
    const double vy = body[3] + 0.0; // Preserve vanilla's signed-zero addition, too.
    const double vz = body[4] + z;
    if (!std::isfinite(vx) || !std::isfinite(vy) || !std::isfinite(vz)) return SYNC;
    body[2] = vx;
    body[3] = vy;
    body[4] = vz;
    return SYNC | WRITE_VELOCITY;
}

} // namespace

int executePushRun(double* bodies, int* actionsAndUpdates, int count) {
    if (bodies == nullptr || actionsAndUpdates == nullptr || count < 0) return -1;
    // Input slots 1..count: PUSH_TARGET / PUSH_SOURCE. Output slots 0..count: SYNC / WRITE.
    actionsAndUpdates[0] = 0;
    for (int i = 1; i <= count; ++i) {
        const int actions = actionsAndUpdates[i];
        actionsAndUpdates[i] = 0;
        if (actions == 0) continue;
        double* target = bodies + static_cast<std::size_t>(i) * STRIDE;
        double x, z;
        if (!eco::pushImpulse(bodies[0], bodies[1], target[0], target[1], x, z)) continue;
        if (actions & PUSH_TARGET) actionsAndUpdates[i] = push(target, -x, -z);
        // Source accumulation is strictly sequential: never reduce a sum of impulses first.
        if (actions & PUSH_SOURCE) actionsAndUpdates[0] |= push(bodies, x, z);
    }
    return 0;
}

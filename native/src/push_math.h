#pragma once

#include "collision_types.h"

#include <algorithm>
#include <cmath>

namespace eco {

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

} // namespace eco

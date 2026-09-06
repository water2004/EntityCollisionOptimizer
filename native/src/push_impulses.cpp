#include "collision_api.h"
#include "collision_types.h"

#include <algorithm>
#include <cmath>
#include <limits>

int calculatePushImpulses(double sourceX, double sourceZ, const double* positions, int count, double* impulses) {
    if (positions == nullptr || impulses == nullptr || count < 0) return -1;
    for (int i = 0; i < count; ++i) {
        double x = sourceX - positions[2 * i];
        double z = sourceZ - positions[2 * i + 1];
        const double maximum = std::max(std::abs(x), std::abs(z));
        if (!(maximum >= eco::PUSH_EPSILON)) {
            // No call to Entity.push(DDD), rather than a zero push that sets needsSync.
            impulses[2 * i] = std::numeric_limits<double>::quiet_NaN();
            impulses[2 * i + 1] = std::numeric_limits<double>::quiet_NaN();
            continue;
        }
        const double root = std::sqrt(maximum);
        x /= root;
        z /= root;
        const double inverse = std::min(1.0, 1.0 / root);
        x *= inverse;
        z *= inverse;
        x *= 0.05000000074505806;
        z *= 0.05000000074505806;
        impulses[2 * i] = x;
        impulses[2 * i + 1] = z;
    }
    return 0;
}

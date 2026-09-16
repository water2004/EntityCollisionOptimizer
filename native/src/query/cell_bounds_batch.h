#pragma once

#include "geometry/aabb.h"
#include "spatial/cell_bounds_soa.h"

#include <cstddef>
#include <cstring>

#if defined(AR_X64)
#include <immintrin.h>
#endif

namespace eco {

// The low four result bits correspond to four consecutive cell member slots.
inline unsigned intersectCellBounds4(
        const Aabb& source,
        const CellBoundsSoa& bounds,
        std::size_t index
) noexcept {
#if defined(AR_X64)
    const __m256d x = _mm256_and_pd(_mm256_cmp_pd(
            _mm256_loadu_pd(bounds.minX.data() + index),
            _mm256_set1_pd(source.maxX),
            _CMP_LT_OQ
    ), _mm256_cmp_pd(
            _mm256_loadu_pd(bounds.maxX.data() + index),
            _mm256_set1_pd(source.minX),
            _CMP_GT_OQ
    ));
    const __m256d y = _mm256_and_pd(_mm256_cmp_pd(
            _mm256_loadu_pd(bounds.minY.data() + index),
            _mm256_set1_pd(source.maxY),
            _CMP_LT_OQ
    ), _mm256_cmp_pd(
            _mm256_loadu_pd(bounds.maxY.data() + index),
            _mm256_set1_pd(source.minY),
            _CMP_GT_OQ
    ));
    const __m256d z = _mm256_and_pd(_mm256_cmp_pd(
            _mm256_loadu_pd(bounds.minZ.data() + index),
            _mm256_set1_pd(source.maxZ),
            _CMP_LT_OQ
    ), _mm256_cmp_pd(
            _mm256_loadu_pd(bounds.maxZ.data() + index),
            _mm256_set1_pd(source.minZ),
            _CMP_GT_OQ
    ));
    return static_cast<unsigned>(_mm256_movemask_pd(_mm256_and_pd(_mm256_and_pd(x, y), z)));
#else
    unsigned mask = 0;
    for (unsigned lane = 0; lane < 4; ++lane) {
        const std::size_t slot = index + lane;
        if (source.minX < bounds.maxX[slot] && source.maxX > bounds.minX[slot]
                && source.minY < bounds.maxY[slot] && source.maxY > bounds.minY[slot]
                && source.minZ < bounds.maxZ[slot] && source.maxZ > bounds.minZ[slot]) {
            mask |= 1U << lane;
        }
    }
    return mask;
#endif
}

inline unsigned intersectCellBounds8(
        const Aabb& source,
        const CellBoundsSoa& bounds,
        std::size_t index
) noexcept {
    return intersectCellBounds4(source, bounds, index)
            | (intersectCellBounds4(source, bounds, index + 4) << 4U);
}

inline unsigned queryableMask8(const std::uint8_t* values) noexcept {
#if defined(AR_X64)
    std::uint64_t packed;
    std::memcpy(&packed, values, sizeof(packed));
    const __m128i bytes = _mm_cvtsi64_si128(static_cast<long long>(packed));
    const __m128i zeros = _mm_setzero_si128();
    return static_cast<unsigned>(~_mm_movemask_epi8(_mm_cmpeq_epi8(bytes, zeros))) & 0xffU;
#else
    unsigned mask = 0;
    for (unsigned lane = 0; lane < 8; ++lane) {
        if (values[lane] != 0) mask |= 1U << lane;
    }
    return mask;
#endif
}

} // namespace eco

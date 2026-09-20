#include "eco/collision_api.h"

#include "geometry/aabb.h"
#include "query/collision_rules.h"
#include "state/collision_context.h"
#include "spatial/cell_bounds_soa.h"
#include "spatial/section_index.h"
#include "spatial/spatial_index.h"

#include <algorithm>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <cstring>

#if defined(AR_X64)
#include <immintrin.h>
#endif

namespace {

// The low four result bits correspond to four consecutive cell member slots.
inline unsigned intersectCellBounds4(
        const eco::Aabb& source,
        const eco::CellBoundsSoa& bounds,
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
        const eco::Aabb& source,
        const eco::CellBoundsSoa& bounds,
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

template<class Visitor>
bool visitPackedRange(std::int64_t minimum, std::int64_t maximum, Visitor&& visitor) {
    if (maximum >= 0) {
        for (std::int64_t value = std::max<std::int64_t>(minimum, 0); value <= maximum; ++value) {
            if (!visitor(value)) return false;
        }
    }
    if (minimum < 0) {
        for (std::int64_t value = minimum; value <= std::min<std::int64_t>(maximum, -1); ++value) {
            if (!visitor(value)) return false;
        }
    }
    return true;
}

template<class Visitor>
bool visitOrderedSections(const eco::LookupSections& sections, Visitor&& visitor) {
    for (std::int64_t x = sections.minX; x <= sections.maxX; ++x) {
        if (!visitPackedRange(sections.minZ, sections.maxZ, [&](std::int64_t z) {
            return visitPackedRange(sections.minY, sections.maxY, [&](std::int64_t y) {
                return visitor(x, y, z);
            });
        })) return false;
    }
    return true;
}

template<class Consumer>
bool visitIntersecting(
        const eco::Aabb& scan,
        const eco::CellMembers& members,
    Consumer&& consume
) {
    std::size_t index = 0;
    for (; index + 8 <= members.ids.size(); index += 8) {
        unsigned hits = intersectCellBounds8(scan, members.bounds, index);
        while (hits != 0) {
            const unsigned lane = std::countr_zero(hits);
            if (!consume(index + lane)) return false;
            hits &= hits - 1;
        }
    }
    for (; index + 4 <= members.ids.size(); index += 4) {
        unsigned hits = intersectCellBounds4(scan, members.bounds, index);
        while (hits != 0) {
            const unsigned lane = std::countr_zero(hits);
            if (!consume(index + lane)) return false;
            hits &= hits - 1;
        }
    }
    for (; index < members.ids.size(); ++index) {
        if (scan.minX >= members.bounds.maxX[index]
                || scan.maxX <= members.bounds.minX[index]
                || scan.minY >= members.bounds.maxY[index]
                || scan.maxY <= members.bounds.minY[index]
                || scan.minZ >= members.bounds.maxZ[index]
                || scan.maxZ <= members.bounds.minZ[index]) continue;
        if (!consume(index)) return false;
    }
    return true;
}

} // namespace

int queryHardCollisionEntities(
        void* contextPointer,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int excludedNativeId,
        int hardOnly,
        int* outputNativeIds,
        int outputCapacity
) {
    if (contextPointer == nullptr || outputNativeIds == nullptr || outputCapacity < 0) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (excludedNativeId < -1
                || (excludedNativeId >= 0
                    && static_cast<std::size_t>(excludedNativeId) >= context.metadata.size())) return -1;
        const eco::Aabb scan = eco::makeAabb(minX, minY, minZ, maxX, maxY, maxZ);
        if (!eco::isIndexable(scan) || (hardOnly != 0 && context.hardEntityCount == 0)) return 0;

        const eco::LookupSections sections(scan);
        int resultSize = 0;
        const bool complete = visitOrderedSections(sections, [&](std::int64_t x, std::int64_t y, std::int64_t z) {
            const eco::CellMembers* members = eco::sectionEntities(context, {x, y, z});
            if (members == nullptr || (hardOnly != 0 && members->hardCount == 0)) return true;
            return visitIntersecting(scan, *members, [&](std::size_t index) {
                const int candidateNativeId = members->ids[index];
                if (candidateNativeId == excludedNativeId
                        || (hardOnly != 0 && !context.metadata[candidateNativeId].hardCollidable)) {
                    return true;
                }
                if (resultSize >= outputCapacity) return false;
                outputNativeIds[resultSize++] = candidateNativeId;
                return true;
            });
        });
        return complete ? resultSize : -2;
    } catch (...) {
        return -3;
    }
}

int queryEntitiesInBox(
        void* contextPointer,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int* outputNativeIds,
        int outputCapacity
) {
    if (contextPointer == nullptr || outputNativeIds == nullptr || outputCapacity < 0) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        const eco::Aabb scan = eco::makeAabb(minX, minY, minZ, maxX, maxY, maxZ);
        if (!eco::isIndexable(scan)) return 0;
        const eco::LookupSections sections(scan);
        int resultSize = 0;
        const bool complete = visitOrderedSections(sections, [&](std::int64_t x, std::int64_t y, std::int64_t z) {
            const eco::CellMembers* members = eco::sectionEntities(context, {x, y, z});
            if (members == nullptr) return true;
            return visitIntersecting(scan, *members, [&](std::size_t index) {
                if (resultSize >= outputCapacity) return false;
                outputNativeIds[resultSize++] = members->ids[index];
                return true;
            });
        });
        return complete ? resultSize : -4;
    } catch (...) {
        return -2;
    }
}

int queryPushableEntities(
        void* contextPointer,
        const double* sourceBounds,
        int excludedNativeId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceNativePushEligible,
        int* outputBuffer,
        int* nativePushFlags,
        int outputCapacity
) {
    if (contextPointer == nullptr || sourceBounds == nullptr || excludedNativeId < -1 || outputBuffer == nullptr
            || nativePushFlags == nullptr || outputCapacity < 0
            || sourceCollisionRule < eco::COLLISION_ALWAYS
            || sourceCollisionRule > eco::COLLISION_PUSH_OTHER_TEAMS) return -1;
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (excludedNativeId >= 0
                && static_cast<std::size_t>(excludedNativeId) >= context.metadata.size()) return -1;
        const eco::Aabb source = eco::makeAabb(
                sourceBounds[0], sourceBounds[1], sourceBounds[2],
                sourceBounds[3], sourceBounds[4], sourceBounds[5]
        );
        if (!eco::isIndexable(source)) {
            outputBuffer[0] = outputBuffer[1] = outputBuffer[2] = 0;
            return 0;
        }

        const eco::LookupSections sections(source);
        const eco::TeamFilter teamFilter(sourceTeamId, sourceCollisionRule);
        const bool nativePushSource = sourceNativePushEligible != 0;
        int* const bodySlots = outputBuffer + 3 + outputCapacity;
        context.metadataMisses.clear();
        int nonPassengerCount = 0;
        int actionableCount = 0;
        const auto consume = [&](int candidateNativeId) -> int {
            const eco::EntityMetadata& target = context.metadata[candidateNativeId];
            if (!target.selectableValid || (target.selectable && !target.teamValid)) {
                if (context.metadataMisses.size() >= static_cast<std::size_t>(outputCapacity)) return -2;
                context.metadataMisses.push_back(candidateNativeId);
                return 0;
            }
            if (!target.selectable || !teamFilter.accepts(target.teamId, target.collisionRule)) return 0;
            if (!target.passenger) ++nonPassengerCount;
            if (actionableCount >= outputCapacity) return -2;
            outputBuffer[3 + actionableCount] = candidateNativeId;
            bodySlots[actionableCount] = target.bodySlot;
            nativePushFlags[actionableCount] = nativePushSource
                    && target.vanillaEntityPush
                    && target.allowsDeferredVelocityWrites;
            ++actionableCount;
            return 0;
        };

        int status = 0;
        const bool complete = visitOrderedSections(sections, [&](std::int64_t x, std::int64_t y, std::int64_t z) {
            const eco::CellMembers* members = eco::sectionEntities(context, {x, y, z});
            if (members == nullptr) return true;

            const bool allQueryable = members->queryableCount == members->ids.size();
            std::size_t index = 0;
            for (; index + 8 <= members->ids.size(); index += 8) {
                const unsigned active = allQueryable
                        ? 0xffU : queryableMask8(members->queryable.data() + index);
                unsigned hits = active & intersectCellBounds8(source, members->bounds, index);
                while (hits != 0) {
                    const unsigned lane = std::countr_zero(hits);
                    const std::size_t candidateIndex = index + lane;
                    const int candidateNativeId = members->ids[candidateIndex];
                    if (candidateNativeId == excludedNativeId) {
                        hits &= hits - 1;
                        continue;
                    }
                    status = consume(candidateNativeId);
                    if (status != 0) return false;
                    hits &= hits - 1;
                }
            }
            for (; index + 4 <= members->ids.size(); index += 4) {
                unsigned active = 0;
                for (unsigned lane = 0; lane < 4; ++lane) {
                    if ((allQueryable || members->queryable[index + lane] != 0)) active |= 1U << lane;
                }
                unsigned hits = active & intersectCellBounds4(source, members->bounds, index);
                while (hits != 0) {
                    const unsigned lane = std::countr_zero(hits);
                    const std::size_t candidateIndex = index + lane;
                    const int candidateNativeId = members->ids[candidateIndex];
                    if (candidateNativeId == excludedNativeId) {
                        hits &= hits - 1;
                        continue;
                    }
                    status = consume(candidateNativeId);
                    if (status != 0) return false;
                    hits &= hits - 1;
                }
            }
            for (; index < members->ids.size(); ++index) {
                if ((!allQueryable && members->queryable[index] == 0)
                        || source.minX >= members->bounds.maxX[index]
                        || source.maxX <= members->bounds.minX[index]
                        || source.minY >= members->bounds.maxY[index]
                        || source.maxY <= members->bounds.minY[index]
                        || source.minZ >= members->bounds.maxZ[index]
                        || source.maxZ <= members->bounds.minZ[index]) continue;
                const int candidateNativeId = members->ids[index];
                if (candidateNativeId == excludedNativeId) continue;
                status = consume(candidateNativeId);
                if (status != 0) return false;
            }
            return true;
        });
        if (!complete) return status == 0 ? -3 : status;

        if (!context.metadataMisses.empty()) {
            std::copy(context.metadataMisses.begin(), context.metadataMisses.end(), outputBuffer + 3);
            outputBuffer[0] = 1;
            outputBuffer[1] = 0;
            outputBuffer[2] = 0;
            return static_cast<int>(context.metadataMisses.size());
        }
        outputBuffer[0] = 0;
        outputBuffer[1] = actionableCount;
        outputBuffer[2] = nonPassengerCount;
        return actionableCount;
    } catch (...) {
        return -3;
    }
}

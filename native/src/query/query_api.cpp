#include "eco/collision_api.h"

#include "query/cell_bounds_batch.h"
#include "query/collision_rules.h"
#include "state/collision_context.h"
#include "spatial/spatial_index.h"
#include "spatial/section_index.h"
#include "spatial/unordered_candidates.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstddef>
#include <limits>

namespace {

#if ECO_VANILLA_ORDER
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
#endif

template<class Visitor>
bool visitSourceCells(const eco::Aabb& source, int gridSize, Visitor&& visitor) {
    if (!eco::isIndexable(source)) return true;
    const double negativeInfinity = -std::numeric_limits<double>::infinity();
    const std::int64_t minX = eco::cellCoordinate(source.minX, gridSize);
    const std::int64_t maxX = eco::cellCoordinate(std::nextafter(source.maxX, negativeInfinity), gridSize);
    const std::int64_t minY = eco::cellCoordinate(source.minY, gridSize);
    const std::int64_t maxY = eco::cellCoordinate(std::nextafter(source.maxY, negativeInfinity), gridSize);
    const std::int64_t minZ = eco::cellCoordinate(source.minZ, gridSize);
    const std::int64_t maxZ = eco::cellCoordinate(std::nextafter(source.maxZ, negativeInfinity), gridSize);
    for (std::int64_t x = minX; x <= maxX; ++x) {
        for (std::int64_t z = minZ; z <= maxZ; ++z) {
            for (std::int64_t y = minY; y <= maxY; ++y) {
                if (!visitor(eco::Cell{x, y, z})) return false;
            }
        }
    }
    return true;
}

} // namespace

int queryCollisionEntities(void* contextPointer, int sourceId, int* output, int outputCapacity) {
    if (contextPointer == nullptr || sourceId < 0 || output == nullptr || outputCapacity < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(sourceId) >= context.boxes.size()) {
            return -1;
        }

        eco::beginQuery(context);
        const eco::Aabb& source = context.boxes[sourceId];
        int resultSize = 0;
        for (const eco::Cell& cell : context.memberships[sourceId]) {
            const eco::CellMembers* members = context.cells.find(cell);
            if (members == nullptr) {
                continue;
            }
            for (const int candidateId : members->ids) {
                if (candidateId == sourceId
                        || context.queryMarks[candidateId] == context.queryGeneration) {
                    continue;
                }
                context.queryMarks[candidateId] = context.queryGeneration;
                if (eco::intersects(source, context.boxes[candidateId])) {
                    if (resultSize >= outputCapacity) {
                        return -2;
                    }
                    output[resultSize++] = candidateId;
                }
            }
        }
        return resultSize;
    } catch (...) {
        return -3;
    }
}

int queryHardCollisionEntities(
        void* contextPointer,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int excludeId,
        int hardOnly,
        int* output,
        int outputCapacity
) {
    if (contextPointer == nullptr || output == nullptr || outputCapacity < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (excludeId < -1
                || (excludeId >= 0
                        && static_cast<std::size_t>(excludeId) >= context.boxes.size())) {
            return -1;
        }

        const eco::Aabb scan = eco::makeAabb(minX, minY, minZ, maxX, maxY, maxZ);
        if (!eco::isIndexable(scan)) {
            return 0;
        }
        if (hardOnly != 0 && context.hardEntityCount == 0) {
            return 0;
        }

        eco::beginQuery(context);
        const double negativeInfinity = -std::numeric_limits<double>::infinity();
        const std::int64_t minCellX = eco::cellCoordinate(scan.minX, context.gridSize);
        const std::int64_t maxCellX = eco::cellCoordinate(std::nextafter(scan.maxX, negativeInfinity), context.gridSize);
        const std::int64_t minCellY = eco::cellCoordinate(scan.minY, context.gridSize);
        const std::int64_t maxCellY = eco::cellCoordinate(std::nextafter(scan.maxY, negativeInfinity), context.gridSize);
        const std::int64_t minCellZ = eco::cellCoordinate(scan.minZ, context.gridSize);
        const std::int64_t maxCellZ = eco::cellCoordinate(std::nextafter(scan.maxZ, negativeInfinity), context.gridSize);
        int resultSize = 0;
        const auto scanMembers = [&](const eco::CellMembers& members) {
            if (hardOnly != 0 && members.hardCount == 0) return true;
            for (const int id : members.ids) {
                if (id == excludeId
                        || (hardOnly != 0 && !context.metadata[id].hardCollidable)
                        || context.queryMarks[id] == context.queryGeneration) {
                    continue;
                }
                context.queryMarks[id] = context.queryGeneration;
                if (!eco::intersects(scan, context.boxes[id])) continue;
                if (resultSize >= outputCapacity) return false;
                output[resultSize++] = id;
            }
            return true;
        };

        // A movement scan normally stays inside the source entity's covered
        // cells.  In that case every possible candidate cell is already in
        // the source's backreference list, so scan it directly and avoid one
        // hash lookup per cell.  The AABB test still rejects candidates whose
        // boxes do not overlap the actual scan box.
        if (excludeId >= 0) {
            const auto& memberships = context.memberships[excludeId];
            if (!memberships.empty()) {
                const auto& first = memberships.front();
                const auto& last = memberships.back();
                const bool scanInsideSource = minCellX >= first.x && maxCellX <= last.x
                        && minCellY >= first.y && maxCellY <= last.y
                        && minCellZ >= first.z && maxCellZ <= last.z;
                if (scanInsideSource
                        && context.memberSlots[excludeId].size() == memberships.size()) {
                    const auto& slots = context.memberSlots[excludeId];
                    for (std::size_t index = 0; index < slots.size(); ++index) {
                        const auto& cell = memberships[index];
                        if (cell.x < minCellX || cell.x > maxCellX
                                || cell.y < minCellY || cell.y > maxCellY
                                || cell.z < minCellZ || cell.z > maxCellZ) {
                            continue;
                        }
                        const auto& slot = slots[index];
                        if (slot.members != nullptr && !scanMembers(*slot.members)) return -2;
                    }
                    return resultSize;
                }
            }
        }
        for (std::int64_t cellX = minCellX; cellX <= maxCellX; ++cellX) {
            for (std::int64_t cellZ = minCellZ; cellZ <= maxCellZ; ++cellZ) {
                for (std::int64_t cellY = minCellY; cellY <= maxCellY; ++cellY) {
                    const eco::CellMembers* members = context.cells.find({cellX, cellY, cellZ});
                    if (members == nullptr) continue;
                    if (!scanMembers(*members)) return -2;
                }
            }
        }
        return resultSize;
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
        int* output,
        int outputCapacity
) {
    if (contextPointer == nullptr || output == nullptr || outputCapacity < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        const eco::Aabb scan = eco::makeAabb(minX, minY, minZ, maxX, maxY, maxZ);
        if (!eco::isIndexable(scan)) return 0;

        const eco::LookupSections sections(scan);

        int resultSize = 0;
        const auto scanSection = [&](std::int64_t sectionX, std::int64_t sectionY, std::int64_t sectionZ) {
            const eco::CellMembers* members = eco::sectionEntities(
                    context, {sectionX, sectionY, sectionZ}
            );
            if (members == nullptr) return true;
#if ECO_VANILLA_ORDER
            std::size_t index = 0;
            for (; index + 4 <= members->ids.size(); index += 4) {
                unsigned hits = eco::intersectCellBounds4(scan, members->bounds, index);
                while (hits != 0) {
                    const unsigned lane = std::countr_zero(hits);
                    if (resultSize >= outputCapacity) return false;
                    output[resultSize++] = members->ids[index + lane];
                    hits &= hits - 1;
                }
            }
            for (; index < members->ids.size(); ++index) {
                if (!eco::intersects(scan, context.boxes[members->ids[index]])) continue;
                if (resultSize >= outputCapacity) return false;
                output[resultSize++] = members->ids[index];
            }
#else
            for (const int id : members->ids) {
                if (!eco::intersects(scan, context.boxes[id])) continue;
                if (resultSize >= outputCapacity) return false;
                output[resultSize++] = id;
            }
#endif
            return true;
        };
#if ECO_VANILLA_ORDER
        if (!visitOrderedSections(sections, scanSection)) return -4;
#else
        for (std::int64_t sectionX = sections.minX; sectionX <= sections.maxX; ++sectionX) {
            for (std::int64_t sectionZ = sections.minZ; sectionZ <= sections.maxZ; ++sectionZ) {
                for (std::int64_t sectionY = sections.minY; sectionY <= sections.maxY; ++sectionY) {
                    if (!scanSection(sectionX, sectionY, sectionZ)) return -4;
                }
            }
        }
#endif
        return resultSize;
    } catch (...) {
        return -2;
    }
}

int queryPushableEntities(
        void* contextPointer,
        const double* sourceBounds,
        int excludedEntityId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceUsesNativePush,
        int* output,
        int* nativePushOutput,
        int outputCapacity
) {
    if (contextPointer == nullptr || sourceBounds == nullptr || excludedEntityId < -1 || output == nullptr
            || nativePushOutput == nullptr || outputCapacity < 0
            || sourceCollisionRule < eco::COLLISION_ALWAYS
            || sourceCollisionRule > eco::COLLISION_PUSH_OTHER_TEAMS) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (excludedEntityId >= 0
                && static_cast<std::size_t>(excludedEntityId) >= context.boxes.size()) {
            return -1;
        }

        const eco::Aabb source = eco::makeAabb(
                sourceBounds[0], sourceBounds[1], sourceBounds[2],
                sourceBounds[3], sourceBounds[4], sourceBounds[5]
        );
        if (!eco::isIndexable(source)) {
            output[0] = output[1] = output[2] = 0;
            return 0;
        }
        const eco::LookupSections lookup(source);
        const eco::TeamFilter teamFilter(sourceTeamId, sourceCollisionRule);
        const bool nativeSource = sourceUsesNativePush != 0;
        int* const bodySlots = output + 3 + outputCapacity;
        context.metadataMisses.clear();
        int nonPassengerCount = 0;
        int actionableCount = 0;
        auto consume = [&](int candidateId) -> int {
            if (candidateId == excludedEntityId) return 0;
            const eco::EntityMetadata& target = context.metadata[candidateId];
#if !ECO_VANILLA_ORDER
            if (!eco::intersects(source, context.boxes[candidateId])
                    || !lookup.contains(target)) {
                return 0;
            }
#endif
            if (!target.selectableValid || (target.selectable && !target.teamValid)) {
                if (context.metadataMisses.size()
                        >= static_cast<std::size_t>(outputCapacity)) {
                    return -2;
                }
                context.metadataMisses.push_back(candidateId);
                return 0;
            }
            if (!target.selectable
                    || !teamFilter.accepts(target.teamId, target.collisionRule)) {
                return 0;
            }
            if (!target.passenger) {
                ++nonPassengerCount;
            }
            if (actionableCount >= outputCapacity) {
                return -2;
            }
            output[3 + actionableCount] = candidateId;
            bodySlots[actionableCount] = target.bodySlot;
            nativePushOutput[actionableCount] = nativeSource
                    && target.vanillaEntityPush && target.vanillaVectorPush;
            ++actionableCount;
            return 0;
        };
#if ECO_VANILLA_ORDER
        // The fine grid answers membership; the persistent section index supplies
        // Minecraft's section/insertion order.  This avoids sorting and heap-merging
        // every overlapping fine-cell stream.
        context.orderedSectionCount = 0;
        if (!visitOrderedSections(lookup, [&](std::int64_t x, std::int64_t y, std::int64_t z) {
            const eco::CellMembers* members = eco::sectionEntities(context, {x, y, z});
            if (members == nullptr) return true;
            const std::size_t scratchIndex = context.orderedSectionCount++;
            if (scratchIndex == context.orderedSections.size()) {
                context.orderedSections.emplace_back();
            }
            auto& scratch = context.orderedSections[scratchIndex];
            scratch.ids = &members->ids;
            scratch.bits.resize((members->ids.size() + 63) / 64);
            std::fill(scratch.bits.begin(), scratch.bits.end(), std::uint64_t{0});
            return true;
        })) return -3;

        eco::beginQuery(context);
        if (!visitSourceCells(source, context.gridSize, [&](const eco::Cell& cell) {
            const eco::CellMembers* memberPointer = context.cells.find(cell);
            if (memberPointer == nullptr) return true;
            const eco::CellMembers& members = *memberPointer;
            const auto selectCandidate = [&](int id) {
                if (!lookup.contains(context.metadata[id])) return;
                const eco::CellSlot sectionSlot = context.sectionSlots[id];
                for (std::size_t sectionIndex = 0; sectionIndex < context.orderedSectionCount; ++sectionIndex) {
                    auto& scratch = context.orderedSections[sectionIndex];
                    if (scratch.ids != &sectionSlot.members->ids) continue;
                    scratch.bits[sectionSlot.index / 64] |= std::uint64_t{1} << (sectionSlot.index % 64);
                    break;
                }
            };

            std::size_t index = 0;
            for (; index + 4 <= members.queryableCount; index += 4) {
                unsigned active = 0;
                for (unsigned lane = 0; lane < 4; ++lane) {
                    const int id = members.ids[index + lane];
                    if (id == excludedEntityId || context.queryMarks[id] == context.queryGeneration) continue;
                    context.queryMarks[id] = context.queryGeneration;
                    active |= 1U << lane;
                }
                unsigned hits = active & eco::intersectCellBounds4(source, members.bounds, index);
                while (hits != 0) {
                    const unsigned lane = std::countr_zero(hits);
                    selectCandidate(members.ids[index + lane]);
                    hits &= hits - 1;
                }
            }
            for (; index < members.queryableCount; ++index) {
                const int id = members.ids[index];
                if (id == excludedEntityId || context.queryMarks[id] == context.queryGeneration) continue;
                context.queryMarks[id] = context.queryGeneration;
                if (source.minX >= members.bounds.maxX[index]
                        || source.maxX <= members.bounds.minX[index]
                        || source.minY >= members.bounds.maxY[index]
                        || source.maxY <= members.bounds.minY[index]
                        || source.minZ >= members.bounds.maxZ[index]
                        || source.maxZ <= members.bounds.minZ[index]) {
                    continue;
                }
                selectCandidate(id);
            }
            return true;
        })) return -3;

        for (std::size_t sectionIndex = 0; sectionIndex < context.orderedSectionCount; ++sectionIndex) {
            const auto& scratch = context.orderedSections[sectionIndex];
            for (std::size_t wordIndex = 0; wordIndex < scratch.bits.size(); ++wordIndex) {
                std::uint64_t bits = scratch.bits[wordIndex];
                while (bits != 0) {
                    const unsigned bit = std::countr_zero(bits);
                    const std::size_t memberIndex = wordIndex * 64 + bit;
                    const int status = consume((*scratch.ids)[memberIndex]);
                    if (status != 0) return status;
                    bits &= bits - 1;
                }
            }
        }
#else
        eco::beginQuery(context);
        int status = 0;
        if (!visitSourceCells(source, context.gridSize, [&](const eco::Cell& cell) {
            const eco::CellMembers* members = context.cells.find(cell);
            if (members == nullptr) return true;
            for (std::size_t index = 0; index < members->queryableCount; ++index) {
                const int id = members->ids[index];
                if (id == excludedEntityId || context.queryMarks[id] == context.queryGeneration) continue;
                context.queryMarks[id] = context.queryGeneration;
                if (!eco::intersects(source, context.boxes[id])) continue;
                status = consume(id);
                if (status != 0) return false;
            }
            return true;
        })) return status == 0 ? -3 : status;
#endif
        if (!context.metadataMisses.empty()) {
            std::copy(
                    context.metadataMisses.begin(),
                    context.metadataMisses.end(),
                    output + 3
            );
            output[0] = 1;
            output[1] = 0;
            output[2] = 0;
            return static_cast<int>(context.metadataMisses.size());
        }
        output[0] = 0;
        output[1] = actionableCount;
        output[2] = nonPassengerCount;
        return actionableCount;
    } catch (...) {
        return -3;
    }
}

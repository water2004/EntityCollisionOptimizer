#include "eco/collision_api.h"

#include "query/collision_rules.h"
#include "state/collision_context.h"
#include "spatial/spatial_index.h"
#include "spatial/ordered_candidates.h"
#include "spatial/unordered_candidates.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>

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

int queryPushableEntities(
        void* contextPointer,
        int sourceId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceUsesVanillaPush,
        int* output,
        int* nativePushOutput,
        int outputCapacity
) {
    if (contextPointer == nullptr || sourceId < 0 || output == nullptr
            || nativePushOutput == nullptr || outputCapacity < 0
            || sourceCollisionRule < eco::COLLISION_ALWAYS
            || sourceCollisionRule > eco::COLLISION_PUSH_OTHER_TEAMS) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(sourceId) >= context.boxes.size()) {
            return -1;
        }

        // Snapshot source-only inputs: candidate iteration mutates scratch state, not this query.
        // Empty/non-indexable sources have no candidates; do not convert NaN/Inf bounds to integers.
        if (context.memberships[sourceId].empty()) {
            output[0] = output[1] = output[2] = 0;
            return 0;
        }
        const eco::Aabb source = context.boxes[sourceId];
        const eco::LookupSections lookup(source);
        const eco::TeamFilter teamFilter(sourceTeamId, sourceCollisionRule);
        const bool nativeSource = sourceUsesVanillaPush != 0 && context.metadata[sourceId].vanillaVectorPush;
        int* const bodySlots = output + 3 + outputCapacity;
        context.metadataMisses.clear();
        int nonPassengerCount = 0;
        int actionableCount = 0;
        auto consume = [&](int candidateId) -> int {
            if (candidateId == sourceId) return 0;
            const eco::EntityMetadata& target = context.metadata[candidateId];
            if (
#if ECO_VANILLA_ORDER
                    !eco::intersects(source, context.boxes[candidateId]) ||
#endif
                    !lookup.contains(target)) {
                return 0;
            }
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
        eco::OrderedCandidates candidates(context, sourceId);
        for (int id = candidates.next(); id != -1; id = candidates.next()) {
            int status = consume(id);
            if (status != 0) return status;
        }
#else
        int status = eco::visitIntersectingCandidates(context, sourceId, consume);
        if (status != 0) return status;
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

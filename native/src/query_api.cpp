#include "collision_api.h"

#include "collision_rules.h"
#include "collision_types.h"
#include "spatial_index.h"
#include "ordered_candidates.h"

#include <algorithm>
#include <cstddef>

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
            const auto iterator = context.cells.find(cell);
            if (iterator == context.cells.end()) {
                continue;
            }
            for (const int candidateId : iterator->second.ids) {
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
        const bool nativeSource = sourceUsesVanillaPush != 0 && context.metadata[sourceId].vanillaVectorPush;
        int* const bodySlots = output + 3 + outputCapacity;
        context.metadataMisses.clear();
        int pushableCount = 0;
        int nonPassengerCount = 0;
        int actionableCount = 0;
        eco::OrderedCandidates candidates(context, sourceId);
        for (int candidateId = candidates.next(); candidateId != -1; candidateId = candidates.next()) {
            if (candidateId == sourceId) continue;
            const eco::EntityMetadata& target = context.metadata[candidateId];
            if (!eco::intersects(source, context.boxes[candidateId])
                    || !lookup.contains(target)) {
                continue;
            }
            if (!target.selectableValid || (target.selectable && !target.teamValid)) {
                if (context.metadataMisses.size()
                        >= static_cast<std::size_t>(outputCapacity)) {
                    return -2;
                }
                context.metadataMisses.push_back(candidateId);
                continue;
            }
            if (!target.selectable
                    || !eco::passesTeamRules(
                            sourceTeamId,
                            sourceCollisionRule,
                            target.teamId,
                            target.collisionRule
                    )) {
                continue;
            }
            ++pushableCount;
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
        }
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
        output[1] = pushableCount;
        output[2] = nonPassengerCount;
        return actionableCount;
    } catch (...) {
        return -3;
    }
}

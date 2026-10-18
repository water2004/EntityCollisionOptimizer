#include "eco/collision_api.h"
#include "native_error.h"
#include "state/index_updates.h"
#include "state/collision_context.h"
#include "spatial/section_index.h"
#include "spatial/spatial_index.h"

namespace eco {
namespace {

void invalidate(CollisionContext& context, int nativeId) {
    auto& metadata = context.metadata[nativeId];
    if (metadata.selectableValid && !metadata.selectable) {
        updateEntityQueryability(context, nativeId, true);
    }
    metadata.selectableValid = false;
}

void updateState(CollisionContext& context, int nativeId, int flags) {
    auto& metadata = context.metadata[nativeId];
    const bool wasQueryable = !metadata.selectableValid || metadata.selectable;
    metadata.selectable = (flags & 1) != 0;
    metadata.passenger = (flags & 2) != 0;
    metadata.vanillaEntityPush = (flags & 4) != 0;
    metadata.allowsDeferredVelocityWrites = (flags & 8) != 0;
    const bool hard = (flags & 16) != 0;
    if (metadata.hardCollidable != hard) {
        if (hard) ++context.hardEntityCount; else --context.hardEntityCount;
        updateSectionHardCollidability(context, nativeId, hard);
    }
    metadata.hardCollidable = hard;
    metadata.selectableValid = true;
    if (wasQueryable != metadata.selectable) {
        updateEntityQueryability(context, nativeId, metadata.selectable);
    }
}

} // namespace

void applyIndexUpdates(CollisionContext& context) {
    auto& updates = *context.updates;
    // Keep publication order: bounds/section changes invalidate previously
    // resolved metadata, and remove/reinsert must never inherit queued old state.
    for (std::uint32_t index = 0; index < updates.count; ++index) {
        const auto& update = updates.records[index];
        const int nativeId = update.nativeId;
        switch (update.kind) {
            case IndexUpdateKind::Bounds: {
                const auto& bounds = update.value.bounds;
                updateEntityBounds(context, nativeId, makeAabb(
                        bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
                invalidate(context, nativeId);
                break;
            }
            case IndexUpdateKind::Section:
                updateSectionEntity(context, nativeId,
                        update.value.section.x, update.value.section.y, update.value.section.z);
                invalidate(context, nativeId);
                break;
            case IndexUpdateKind::State:
                updateState(context, nativeId, update.value.flags);
                break;
            case IndexUpdateKind::Team: {
                auto& metadata = context.metadata[nativeId];
                metadata.teamId = update.value.team.id;
                metadata.collisionRule = update.value.team.rule;
                metadata.teamQueryEpoch = update.value.team.epoch;
                metadata.teamValid = true;
                break;
            }
            case IndexUpdateKind::InvalidateEntity:
                invalidate(context, nativeId);
                break;
            case IndexUpdateKind::InvalidateFields:
                for (std::size_t id = 0; id < context.metadata.size(); ++id) {
                    if (update.value.flags & METADATA_STATE) invalidate(context, static_cast<int>(id));
                    if (update.value.flags & METADATA_TEAM) context.metadata[id].teamValid = false;
                }
                break;
        }
    }
    // Only acknowledge a fully consumed batch. A native exception is fatal;
    // neither side retries or repairs a partially applied batch.
    updates.count = 0;
}

} // namespace eco

int flushCollisionUpdates(void* contextPointer) {
    try {
        eco::applyIndexUpdates(*static_cast<eco::CollisionContext*>(contextPointer));
        return 0;
    } catch (...) { return eco::recordNativeException(); }
}

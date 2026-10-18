#pragma once

#include <cstddef>
#include <cstdint>

namespace eco {

struct CollisionContext;

enum class IndexUpdateKind : std::int32_t {
    Bounds = 1, Section = 2, State = 3, Team = 4, InvalidateEntity = 5, InvalidateFields = 6
};

// Java IndexUpdates publishes records under the owning Context monitor. Native
// consumes them synchronously, without callbacks, before observing index state.
struct IndexUpdate {
    IndexUpdateKind kind;
    std::int32_t nativeId;
    union {
        double bounds[6];
        struct { std::int32_t x, y, z; } section;
        // State bits: selectable, passenger, vanilla push, deferred velocity, hard.
        // InvalidateFields uses the independent METADATA_STATE/METADATA_TEAM bits.
        std::int32_t flags;
        struct { std::int32_t id, rule; std::uint64_t epoch; } team;
    } value;
};

struct IndexUpdates {
    const IndexUpdate* records;
    std::uint32_t count;
};

static_assert(sizeof(IndexUpdate) == 56 && offsetof(IndexUpdate, value) == 8);
static_assert(sizeof(IndexUpdates) == 16 && offsetof(IndexUpdates, count) == 8);

void applyIndexUpdates(CollisionContext& context);

} // namespace eco

#pragma once

#include <cstdint>

namespace eco {
// Every section coordinate enters through an int32 ABI parameter. Keep the
// complete query record in one half cache line; no per-cell metadata copies.
struct alignas(32) EntityMetadata {
    std::int32_t sectionX = 0, sectionY = 0, sectionZ = 0;
    int teamId = -1;
    int bodySlot = -1;
    std::uint32_t collisionRule : 2 = 0;
    std::uint32_t selectable : 1 = false;
    std::uint32_t passenger : 1 = false;
    std::uint32_t vanillaEntityPush : 1 = false;
    std::uint32_t allowsDeferredVelocityWrites : 1 = false;
    std::uint32_t hardCollidable : 1 = false;
    std::uint32_t selectableValid : 1 = false;
    std::uint32_t teamValid : 1 = false;
};
static_assert(sizeof(EntityMetadata) == 32);

inline constexpr int METADATA_SELECTABLE = 1;
inline constexpr int METADATA_TEAM = 2;
inline constexpr int COLLISION_ALWAYS = 0;
inline constexpr int COLLISION_NEVER = 1;
inline constexpr int COLLISION_PUSH_OWN_TEAM = 2;
inline constexpr int COLLISION_PUSH_OTHER_TEAMS = 3;
} // namespace eco

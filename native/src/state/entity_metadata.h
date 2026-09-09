#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

#include <cstdint>

namespace eco {
// Every section coordinate enters through an int32 ABI parameter. Keep the
// complete query record in one half cache line; no per-cell metadata copies.
struct alignas(32) EntityMetadata {
#if ECO_VANILLA_ORDER
    std::int64_t sectionOrder = 0;
    std::int32_t sectionX = 0, sectionY = 0, sectionZ = 0;
    int teamId = -1;
    int bodySlot = -1;
    std::uint32_t collisionRule : 2 = 0;
    std::uint32_t selectable : 1 = false;
    std::uint32_t passenger : 1 = false;
    std::uint32_t vehicle : 1 = false;
    std::uint32_t noPhysics : 1 = false;
    std::uint32_t vanillaEntityPush : 1 = false;
    std::uint32_t vanillaVectorPush : 1 = false;
    std::uint32_t hardCollidable : 1 = false;
    std::uint32_t selectableValid : 1 = false;
    std::uint32_t teamValid : 1 = false;
#else
    std::int32_t sectionX = 0, sectionY = 0, sectionZ = 0;
    int teamId = -1;
    int collisionRule = 0;
    int bodySlot = -1;
    bool selectable : 1 = false;
    bool passenger : 1 = false;
    bool vehicle : 1 = false;
    bool noPhysics : 1 = false;
    bool vanillaEntityPush : 1 = false;
    bool vanillaVectorPush : 1 = false;
    bool hardCollidable : 1 = false;
    bool selectableValid : 1 = false;
    bool teamValid : 1 = false;
#endif
};
static_assert(sizeof(EntityMetadata) == 32);

inline constexpr int METADATA_SELECTABLE = 1;
inline constexpr int METADATA_TEAM = 2;
inline constexpr int COLLISION_ALWAYS = 0;
inline constexpr int COLLISION_NEVER = 1;
inline constexpr int COLLISION_PUSH_OWN_TEAM = 2;
inline constexpr int COLLISION_PUSH_OTHER_TEAMS = 3;
} // namespace eco

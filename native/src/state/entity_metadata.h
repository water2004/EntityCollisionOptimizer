#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

#include <cstdint>

namespace eco {
#if ECO_VANILLA_ORDER
struct EntityMetadata {
    std::int64_t sectionX = 0;
    std::int64_t sectionY = 0;
    std::int64_t sectionZ = 0;
    std::int64_t sectionOrder = 0;
    int teamId = -1;
    int collisionRule = 0;
    int bodySlot = -1;
    bool selectable = false;
    bool passenger = false;
    bool vehicle = false;
    bool noPhysics = false;
    bool vanillaEntityPush = false;
    bool vanillaVectorPush = false;
    bool hardCollidable = false;
    bool selectableValid = false;
    bool teamValid = false;
};
#else
// Every section coordinate enters through an int32 ABI parameter. Keep the
// complete query record in one half cache line; no per-cell metadata copies.
struct alignas(32) EntityMetadata {
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
};
static_assert(sizeof(EntityMetadata) == 32);
#endif

inline constexpr int METADATA_SELECTABLE = 1;
inline constexpr int METADATA_TEAM = 2;
inline constexpr int COLLISION_ALWAYS = 0;
inline constexpr int COLLISION_NEVER = 1;
inline constexpr int COLLISION_PUSH_OWN_TEAM = 2;
inline constexpr int COLLISION_PUSH_OTHER_TEAMS = 3;
} // namespace eco

#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

#include <cstdint>

namespace eco {
struct EntityMetadata {
    std::int64_t sectionX = 0;
    std::int64_t sectionY = 0;
    std::int64_t sectionZ = 0;
#if ECO_VANILLA_ORDER
    std::int64_t sectionOrder = 0;
#endif
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

inline constexpr int METADATA_SELECTABLE = 1;
inline constexpr int METADATA_TEAM = 2;
inline constexpr int COLLISION_ALWAYS = 0;
inline constexpr int COLLISION_NEVER = 1;
inline constexpr int COLLISION_PUSH_OWN_TEAM = 2;
inline constexpr int COLLISION_PUSH_OTHER_TEAMS = 3;
} // namespace eco

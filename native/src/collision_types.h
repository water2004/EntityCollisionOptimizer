#pragma once

#include <cstddef>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace eco {

struct Aabb {
    double minX;
    double minY;
    double minZ;
    double maxX;
    double maxY;
    double maxZ;
};

struct EntityMetadata {
    double positionX = 0.0;
    double positionZ = 0.0;
    std::int64_t sectionX = 0;
    std::int64_t sectionY = 0;
    std::int64_t sectionZ = 0;
    int teamId = -1;
    int collisionRule = 0;
    bool selectable = false;
    bool passenger = false;
    bool vehicle = false;
    bool noPhysics = false;
    bool vanillaEntityPush = false;
    bool vanillaVectorPush = false;
    bool selectableValid = false;
    bool teamValid = false;
};

struct Cell {
    std::int64_t x;
    std::int64_t z;

    bool operator==(const Cell&) const = default;
};

struct CellHash {
    std::size_t operator()(const Cell& cell) const noexcept;
};

struct CollisionContext {
    int gridSize = 1;
    std::vector<Aabb> boxes;
    std::vector<EntityMetadata> metadata;
    std::vector<std::vector<Cell>> memberships;
    std::unordered_map<Cell, std::vector<int>, CellHash> cells;
    std::vector<std::uint32_t> queryMarks;
    std::vector<int> metadataMisses;
    std::uint32_t queryGeneration = 0;
};

inline constexpr int METADATA_SELECTABLE = 1;
inline constexpr int METADATA_TEAM = 2;
inline constexpr int COLLISION_ALWAYS = 0;
inline constexpr int COLLISION_NEVER = 1;
inline constexpr int COLLISION_PUSH_OWN_TEAM = 2;
inline constexpr int COLLISION_PUSH_OTHER_TEAMS = 3;
inline constexpr double PUSH_EPSILON = 0.009999999776482582;

} // namespace eco

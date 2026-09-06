#include "entity_data.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

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
    bool ordinaryLivingTarget = false;
    bool selectableValid = false;
    bool teamValid = false;
};

struct Cell {
    std::int64_t x;
    std::int64_t z;

    bool operator==(const Cell&) const = default;
};

struct CellHash {
    std::size_t operator()(const Cell& cell) const noexcept {
        std::uint64_t x = static_cast<std::uint64_t>(cell.x);
        std::uint64_t z = static_cast<std::uint64_t>(cell.z);
        x ^= x >> 30;
        x *= 0xbf58476d1ce4e5b9ULL;
        x ^= x >> 27;
        x *= 0x94d049bb133111ebULL;
        x ^= x >> 31;
        z ^= z >> 30;
        z *= 0xbf58476d1ce4e5b9ULL;
        z ^= z >> 27;
        z *= 0x94d049bb133111ebULL;
        z ^= z >> 31;
        return static_cast<std::size_t>(x ^ (z + 0x9e3779b97f4a7c15ULL + (x << 6) + (x >> 2)));
    }
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

constexpr int METADATA_SELECTABLE = 1;
constexpr int METADATA_TEAM = 2;
constexpr int COLLISION_ALWAYS = 0;
constexpr int COLLISION_NEVER = 1;
constexpr int COLLISION_PUSH_OWN_TEAM = 2;
constexpr int COLLISION_PUSH_OTHER_TEAMS = 3;
constexpr double PUSH_EPSILON = 0.009999999776482582;

Aabb makeAabb(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
) noexcept {
    return {minX, minY, minZ, maxX, maxY, maxZ};
}

bool isIndexable(const Aabb& box) noexcept {
    return std::isfinite(box.minX)
            && std::isfinite(box.minY)
            && std::isfinite(box.minZ)
            && std::isfinite(box.maxX)
            && std::isfinite(box.maxY)
            && std::isfinite(box.maxZ)
            && box.minX < box.maxX
            && box.minY < box.maxY
            && box.minZ < box.maxZ;
}

std::int64_t cellCoordinate(double value, int gridSize) noexcept {
    return static_cast<std::int64_t>(std::floor(value / static_cast<double>(gridSize)));
}

std::vector<Cell> coveredCells(const Aabb& box, int gridSize) {
    std::vector<Cell> result;
    if (!isIndexable(box)) {
        return result;
    }

    const double negativeInfinity = -std::numeric_limits<double>::infinity();
    std::int64_t minCellX = cellCoordinate(box.minX, gridSize);
    std::int64_t maxCellX = cellCoordinate(std::nextafter(box.maxX, negativeInfinity), gridSize);
    std::int64_t minCellZ = cellCoordinate(box.minZ, gridSize);
    std::int64_t maxCellZ = cellCoordinate(std::nextafter(box.maxZ, negativeInfinity), gridSize);

    std::size_t width = static_cast<std::size_t>(maxCellX - minCellX + 1);
    std::size_t depth = static_cast<std::size_t>(maxCellZ - minCellZ + 1);
    result.reserve(width * depth);
    for (std::int64_t x = minCellX; x <= maxCellX; ++x) {
        for (std::int64_t z = minCellZ; z <= maxCellZ; ++z) {
            result.push_back({x, z});
        }
    }
    return result;
}

void insertMemberships(CollisionContext& context, int entityId, const std::vector<Cell>& memberships) {
    for (const Cell& cell : memberships) {
        context.cells[cell].push_back(entityId);
    }
}

void removeMemberships(CollisionContext& context, int entityId, const std::vector<Cell>& memberships) {
    for (const Cell& cell : memberships) {
        auto cellIterator = context.cells.find(cell);
        if (cellIterator == context.cells.end()) {
            continue;
        }
        std::vector<int>& entities = cellIterator->second;
        auto entityIterator = std::find(entities.begin(), entities.end(), entityId);
        if (entityIterator != entities.end()) {
            *entityIterator = entities.back();
            entities.pop_back();
        }
        if (entities.empty()) {
            context.cells.erase(cellIterator);
        }
    }
}

void rebuild(CollisionContext& context) {
    context.cells.clear();
    context.memberships.clear();
    context.memberships.resize(context.boxes.size());
    context.queryMarks.assign(context.boxes.size(), 0);
    context.queryGeneration = 0;
    for (std::size_t index = 0; index < context.boxes.size(); ++index) {
        std::vector<Cell> memberships = coveredCells(context.boxes[index], context.gridSize);
        insertMemberships(context, static_cast<int>(index), memberships);
        context.memberships[index] = std::move(memberships);
    }
}

void update(CollisionContext& context, int entityId, const Aabb& box) {
    std::vector<Cell> newMemberships = coveredCells(box, context.gridSize);
    std::vector<Cell>& oldMemberships = context.memberships[entityId];
    if (oldMemberships != newMemberships) {
        removeMemberships(context, entityId, oldMemberships);
        insertMemberships(context, entityId, newMemberships);
        oldMemberships = std::move(newMemberships);
    }
    context.boxes[entityId] = box;
}

bool intersects(const Aabb& first, const Aabb& second) noexcept {
    return first.minX < second.maxX
            && first.maxX > second.minX
            && first.minY < second.maxY
            && first.maxY > second.minY
            && first.minZ < second.maxZ
            && first.maxZ > second.minZ;
}

bool isInLookupSections(const Aabb& source, const EntityMetadata& target) noexcept {
    const auto minSectionX = cellCoordinate(source.minX - 2.0, 16);
    const auto minSectionY = cellCoordinate(source.minY - 4.0, 16);
    const auto minSectionZ = cellCoordinate(source.minZ - 2.0, 16);
    const auto maxSectionX = cellCoordinate(source.maxX + 2.0, 16);
    const auto maxSectionY = cellCoordinate(source.maxY, 16);
    const auto maxSectionZ = cellCoordinate(source.maxZ + 2.0, 16);
    return target.sectionX >= minSectionX && target.sectionX <= maxSectionX
            && target.sectionY >= minSectionY && target.sectionY <= maxSectionY
            && target.sectionZ >= minSectionZ && target.sectionZ <= maxSectionZ;
}

bool passesTeamRules(
        int sourceTeamId,
        int sourceRule,
        int targetTeamId,
        int targetRule
) noexcept {
    if (sourceRule == COLLISION_NEVER || targetRule == COLLISION_NEVER) {
        return false;
    }
    const bool allied = sourceTeamId >= 0 && sourceTeamId == targetTeamId;
    if ((sourceRule == COLLISION_PUSH_OWN_TEAM || targetRule == COLLISION_PUSH_OWN_TEAM)
            && allied) {
        return false;
    }
    return (sourceRule != COLLISION_PUSH_OTHER_TEAMS
            && targetRule != COLLISION_PUSH_OTHER_TEAMS) || allied;
}

bool pushHasNoEffect(
        const EntityMetadata& source,
        const EntityMetadata& target,
        bool sourceUsesVanillaPush
) noexcept {
    if (!sourceUsesVanillaPush || !target.ordinaryLivingTarget) {
        return false;
    }
    const double deltaX = source.positionX - target.positionX;
    const double deltaZ = source.positionZ - target.positionZ;
    return std::max(std::abs(deltaX), std::abs(deltaZ)) < PUSH_EPSILON;
}

} // namespace

void* createCollisionContext() {
    try {
        return new CollisionContext();
    } catch (...) {
        return nullptr;
    }
}

void destroyCollisionContext(void* context) {
    delete static_cast<CollisionContext*>(context);
}

int setCollisionGridSize(void* contextPointer, int gridSize) {
    if (contextPointer == nullptr || gridSize <= 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        if (context.gridSize != gridSize) {
            context.gridSize = gridSize;
            rebuild(context);
        }
        return 0;
    } catch (...) {
        return -2;
    }
}

int beginCollisionFrame(
        void* contextPointer,
        const double* aabbs,
        const double* positions,
        const int* sections,
        int entityCount,
        int gridSize
) {
    if (contextPointer == nullptr || entityCount < 0 || gridSize <= 0
            || (entityCount > 0
                    && (aabbs == nullptr || positions == nullptr || sections == nullptr))) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        context.gridSize = gridSize;
        context.boxes.resize(static_cast<std::size_t>(entityCount));
        context.metadata.assign(static_cast<std::size_t>(entityCount), {});
        for (int index = 0; index < entityCount; ++index) {
            const double* box = aabbs + static_cast<std::size_t>(index) * 6;
            const double* position = positions + static_cast<std::size_t>(index) * 2;
            const int* section = sections + static_cast<std::size_t>(index) * 3;
            context.boxes[index] = makeAabb(
                    box[0], box[1], box[2], box[3], box[4], box[5]
            );
            EntityMetadata& metadata = context.metadata[index];
            metadata.positionX = position[0];
            metadata.positionZ = position[1];
            metadata.sectionX = section[0];
            metadata.sectionY = section[1];
            metadata.sectionZ = section[2];
        }
        rebuild(context);
        return 0;
    } catch (...) {
        return -2;
    }
}

int addCollisionEntity(
        void* contextPointer,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        double positionX,
        double positionZ,
        int sectionX,
        int sectionY,
        int sectionZ
) {
    if (contextPointer == nullptr) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        int entityId = static_cast<int>(context.boxes.size());
        Aabb box = makeAabb(minX, minY, minZ, maxX, maxY, maxZ);
        context.boxes.push_back(box);
        EntityMetadata metadata;
        metadata.positionX = positionX;
        metadata.positionZ = positionZ;
        metadata.sectionX = sectionX;
        metadata.sectionY = sectionY;
        metadata.sectionZ = sectionZ;
        context.metadata.push_back(metadata);
        context.memberships.push_back(coveredCells(box, context.gridSize));
        context.queryMarks.push_back(0);
        insertMemberships(context, entityId, context.memberships.back());
        return entityId;
    } catch (...) {
        return -2;
    }
}

int updateCollisionEntity(
        void* contextPointer,
        int entityId,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        double positionX,
        double positionZ,
        int sectionX,
        int sectionY,
        int sectionZ
) {
    if (contextPointer == nullptr || entityId < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(entityId) >= context.boxes.size()) {
            return -1;
        }
        update(
                context,
                entityId,
                makeAabb(minX, minY, minZ, maxX, maxY, maxZ)
        );
        EntityMetadata& metadata = context.metadata[entityId];
        metadata.positionX = positionX;
        metadata.positionZ = positionZ;
        metadata.sectionX = sectionX;
        metadata.sectionY = sectionY;
        metadata.sectionZ = sectionZ;
        metadata.selectableValid = false;
        return 0;
    } catch (...) {
        return -2;
    }
}

int updateCollisionEntityMetadata(
        void* contextPointer,
        int entityId,
        int selectable,
        int passenger,
        int ordinaryLivingTarget,
        int teamId,
        int collisionRule
) {
    if (contextPointer == nullptr || entityId < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(entityId) >= context.metadata.size()) {
            return -1;
        }
        EntityMetadata& metadata = context.metadata[entityId];
        metadata.selectable = selectable != 0;
        metadata.passenger = passenger != 0;
        metadata.ordinaryLivingTarget = ordinaryLivingTarget != 0;
        metadata.teamId = teamId;
        metadata.collisionRule = collisionRule;
        metadata.selectableValid = true;
        metadata.teamValid = true;
        return 0;
    } catch (...) {
        return -2;
    }
}

int invalidateCollisionEntityMetadata(void* contextPointer, int entityId) {
    if (contextPointer == nullptr || entityId < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(entityId) >= context.metadata.size()) {
            return -1;
        }
        context.metadata[entityId].selectableValid = false;
        return 0;
    } catch (...) {
        return -2;
    }
}

int invalidateCollisionMetadata(void* contextPointer, int mask) {
    if (contextPointer == nullptr || (mask & ~(METADATA_SELECTABLE | METADATA_TEAM)) != 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        for (EntityMetadata& metadata : context.metadata) {
            if ((mask & METADATA_SELECTABLE) != 0) {
                metadata.selectableValid = false;
            }
            if ((mask & METADATA_TEAM) != 0) {
                metadata.teamValid = false;
            }
        }
        return 0;
    } catch (...) {
        return -2;
    }
}

int queryCollisionEntities(void* contextPointer, int sourceId, int* output, int outputCapacity) {
    if (contextPointer == nullptr || sourceId < 0 || output == nullptr || outputCapacity < 0) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(sourceId) >= context.boxes.size()) {
            return -1;
        }

        ++context.queryGeneration;
        if (context.queryGeneration == 0) {
            std::fill(context.queryMarks.begin(), context.queryMarks.end(), 0);
            context.queryGeneration = 1;
        }

        const Aabb& source = context.boxes[sourceId];
        int resultSize = 0;
        for (const Cell& cell : context.memberships[sourceId]) {
            auto iterator = context.cells.find(cell);
            if (iterator == context.cells.end()) {
                continue;
            }
            for (int candidateId : iterator->second) {
                if (candidateId == sourceId
                        || context.queryMarks[candidateId] == context.queryGeneration) {
                    continue;
                }
                context.queryMarks[candidateId] = context.queryGeneration;
                if (intersects(source, context.boxes[candidateId])) {
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
        int outputCapacity
) {
    if (contextPointer == nullptr || sourceId < 0 || output == nullptr || outputCapacity < 0
            || sourceCollisionRule < COLLISION_ALWAYS
            || sourceCollisionRule > COLLISION_PUSH_OTHER_TEAMS) {
        return -1;
    }
    try {
        auto& context = *static_cast<CollisionContext*>(contextPointer);
        if (static_cast<std::size_t>(sourceId) >= context.boxes.size()) {
            return -1;
        }

        ++context.queryGeneration;
        if (context.queryGeneration == 0) {
            std::fill(context.queryMarks.begin(), context.queryMarks.end(), 0);
            context.queryGeneration = 1;
        }

        const Aabb& source = context.boxes[sourceId];
        const EntityMetadata& sourceMetadata = context.metadata[sourceId];
        context.metadataMisses.clear();
        int pushableCount = 0;
        int nonPassengerCount = 0;
        int actionableCount = 0;
        for (const Cell& cell : context.memberships[sourceId]) {
            auto iterator = context.cells.find(cell);
            if (iterator == context.cells.end()) {
                continue;
            }
            for (int candidateId : iterator->second) {
                if (candidateId == sourceId
                        || context.queryMarks[candidateId] == context.queryGeneration) {
                    continue;
                }
                context.queryMarks[candidateId] = context.queryGeneration;
                const EntityMetadata& target = context.metadata[candidateId];
                if (!intersects(source, context.boxes[candidateId])
                        || !isInLookupSections(source, target)) {
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
                        || !passesTeamRules(
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
                if (!pushHasNoEffect(
                        sourceMetadata,
                        target,
                        sourceUsesVanillaPush != 0
                )) {
                    if (actionableCount >= outputCapacity) {
                        return -2;
                    }
                    output[3 + actionableCount++] = candidateId;
                }
            }
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

#include "eco/collision_api.h"

#include "state/collision_context.h"
#include "spatial/spatial_index.h"
#include "spatial/section_index.h"

#include <cstddef>
#include <new>
#include <utility>

void* createCollisionContext() {
    try {
        return new eco::CollisionContext();
    } catch (...) {
        return nullptr;
    }
}

void destroyCollisionContext(void* context) {
    delete static_cast<eco::CollisionContext*>(context);
}

int beginCollisionFrame(
        void* contextPointer,
        const double* aabbs,
        const int* sections,
        int entityCount
) {
    if (contextPointer == nullptr || entityCount < 0
            || (entityCount > 0
                    && (aabbs == nullptr || sections == nullptr))) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        context.boxes.resize(static_cast<std::size_t>(entityCount));
        context.metadata.assign(static_cast<std::size_t>(entityCount), {});
        context.hardEntityCount = 0;
        for (int index = 0; index < entityCount; ++index) {
            const double* box = aabbs + static_cast<std::size_t>(index) * 6;
            const int* section = sections + static_cast<std::size_t>(index) * 3;
            context.boxes[index] = eco::makeAabb(
                    box[0], box[1], box[2], box[3], box[4], box[5]
            );
            eco::EntityMetadata& metadata = context.metadata[index];
            metadata.sectionX = section[0];
            metadata.sectionY = section[1];
            metadata.sectionZ = section[2];
            metadata.sectionOrder = index;
        }
        eco::rebuildSectionIndex(context);
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
        int sectionX,
        int sectionY,
        int sectionZ
) {
    if (contextPointer == nullptr) {
        return -1;
    }
    try {
        auto& context = *static_cast<eco::CollisionContext*>(contextPointer);
        const int entityId = static_cast<int>(context.boxes.size());
        const eco::Aabb box = eco::makeAabb(minX, minY, minZ, maxX, maxY, maxZ);
        context.boxes.push_back(box);
        eco::EntityMetadata metadata;
        metadata.sectionX = sectionX;
        metadata.sectionY = sectionY;
        metadata.sectionZ = sectionZ;
        context.metadata.push_back(metadata);
        eco::insertSectionEntity(context, entityId);
        return entityId;
    } catch (...) {
        return -2;
    }
}

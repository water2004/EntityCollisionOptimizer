#include "eco/collision_api.h"

#include "state/collision_context.h"

#include <new>

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

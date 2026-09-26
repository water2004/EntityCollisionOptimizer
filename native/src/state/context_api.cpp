#include "eco/collision_api.h"
#include "native_error.h"

#include "state/collision_context.h"

void* createCollisionContext() {
    try {
        return new eco::CollisionContext();
    } catch (...) {
        eco::recordNativeException();
        return nullptr;
    }
}

void destroyCollisionContext(void* context) {
    delete static_cast<eco::CollisionContext*>(context);
}

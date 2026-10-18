#include "eco/collision_api.h"
#include "native_error.h"

#include "state/collision_context.h"

void* createCollisionContext(void* indexUpdatesPointer) {
    try {
        auto* context = new eco::CollisionContext();
        context->updates = static_cast<eco::IndexUpdates*>(indexUpdatesPointer);
        return context;
    } catch (...) {
        eco::recordNativeException();
        return nullptr;
    }
}

void destroyCollisionContext(void* context) {
    delete static_cast<eco::CollisionContext*>(context);
}

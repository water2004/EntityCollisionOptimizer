package org.edtp.entitycollisionoptimizer.collision;

import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.natives.CollisionStateTable;

/** Shared body access boundary, including field accessors merged into Entity by other mixins. */
public interface CollisionBodyAccess {
    Vec3 eco$readVelocity();
    void eco$writeVelocity(Vec3 value);
    boolean eco$readNeedsSync();
    void eco$writeNeedsSync(boolean value);
    void eco$writeNoPhysics(boolean value);
    void eco$writePosition(Vec3 value);
    void eco$invalidatePushState();

    /** Current owner-qualified slot, or -1 when this table does not own the body. */
    int eco$boundBodySlot(CollisionStateTable table);
    void eco$bindBody(CollisionStateTable table, int slot);
    void eco$detachBody(CollisionStateTable table, int slot);
}

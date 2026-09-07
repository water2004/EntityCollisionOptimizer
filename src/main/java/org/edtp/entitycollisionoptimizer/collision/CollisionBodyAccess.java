package org.edtp.entitycollisionoptimizer.collision;

import org.edtp.entitycollisionoptimizer.natives.CollisionStateTable;
import net.minecraft.world.phys.Vec3;

/** Shared body access boundary, including field accessors merged into Entity by other mixins. */
public interface CollisionBodyAccess {
    Vec3 eco$readVelocity();
    void eco$writeVelocity(Vec3 value);
    boolean eco$readNeedsSync();
    void eco$writeNeedsSync(boolean value);
    void eco$writeNoPhysics(boolean value);
    Vec3 eco$readPosition();
    void eco$writePosition(Vec3 value);
    java.lang.foreign.MemorySegment eco$positionBody();
    net.minecraft.world.phys.AABB eco$readBounds();
    void eco$writeBounds(net.minecraft.world.phys.AABB value);
    java.lang.foreign.MemorySegment eco$movementBody();
    void eco$invalidatePushState();
    void eco$bindBody(CollisionStateTable table, int slot);
    void eco$detachBody(CollisionStateTable table, int slot);
}

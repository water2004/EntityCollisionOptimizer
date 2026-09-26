package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.CollisionBodyAccess;
import org.edtp.entitycollisionoptimizer.natives.CollisionStateTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Position, velocity and hasImpulse use vanilla fields only while the entity is unbound. */
@Mixin(Entity.class)
public abstract class EntityBodyMixin implements CollisionBodyAccess {
    @Shadow private Vec3 position;
    @Shadow private net.minecraft.world.phys.AABB bb;
    @Shadow private Vec3 deltaMovement;
    @Shadow public boolean hasImpulse;
    @Shadow public boolean noPhysics;
    @Unique private CollisionStateTable eco$bodyTable;
    @Unique private int eco$bodySlot;

    @Override public final Vec3 eco$readVelocity() {
        return eco$bodyTable == null ? deltaMovement : eco$bodyTable.velocity(eco$bodySlot);
    }

    @Override public final void eco$writeVelocity(Vec3 value) {
        // This replaces the field store, not setDeltaMovement: vanilla's finite guard stays in place.
        if (eco$bodyTable == null) deltaMovement = value;
        else eco$bodyTable.velocity(eco$bodySlot, value);
    }

    @Override public final boolean eco$readNeedsSync() {
        return eco$bodyTable == null ? hasImpulse : eco$bodyTable.needsSync(eco$bodySlot);
    }

    @Override public final void eco$writeNeedsSync(boolean value) {
        if (eco$bodyTable == null) hasImpulse = value;
        else eco$bodyTable.needsSync(eco$bodySlot, value);
    }

    @Override public final void eco$writeNoPhysics(boolean value) {
        noPhysics = value;
        eco$invalidatePushState();
    }

    @Override public final Vec3 eco$readPosition() {
        return eco$bodyTable == null ? position : eco$bodyTable.position(eco$bodySlot);
    }

    @Override public final void eco$writePosition(Vec3 value) {
        // Publish at the original field store, before section/world callbacks, never during a query.
        if (eco$bodyTable == null) position = value;
        else eco$bodyTable.position(eco$bodySlot, value);
    }

    @Override public final java.lang.foreign.MemorySegment eco$positionBody() {
        return eco$bodyTable == null ? java.lang.foreign.MemorySegment.NULL : eco$bodyTable.row(eco$bodySlot);
    }

    @Override public final net.minecraft.world.phys.AABB eco$readBounds() {
        return eco$bodyTable == null ? bb : eco$bodyTable.bounds(eco$bodySlot);
    }

    @Override public final void eco$writeBounds(net.minecraft.world.phys.AABB value) {
        if (eco$bodyTable == null) bb = value;
        else eco$bodyTable.bounds(eco$bodySlot, value);
    }

    @Override public final java.lang.foreign.MemorySegment eco$movementBody() {
        return eco$bodyTable == null ? java.lang.foreign.MemorySegment.NULL : eco$bodyTable.movementRow(eco$bodySlot);
    }

    @Override public final void eco$invalidatePushState() {
        if (eco$bodyTable != null) eco$bodyTable.invalidatePushState(eco$bodySlot);
    }

    @Override public final void eco$bindBody(CollisionStateTable table, int slot) {
        if (eco$bodyTable != table || eco$bodySlot != slot) {
            // Also handles moving an entity between tables: read the former owner before rebinding.
            table.velocity(slot, eco$readVelocity());
            table.needsSync(slot, eco$readNeedsSync());
            table.position(slot, eco$readPosition());
            table.bounds(slot, eco$readBounds());
            if (eco$bodyTable != null) eco$bodyTable.unbind(eco$bodySlot);
            eco$bodyTable = table;
            eco$bodySlot = slot;
            table.bind(slot);
        }
    }

    @Override public final void eco$detachBody(CollisionStateTable table, int slot) {
        if (eco$bodyTable == table && eco$bodySlot == slot) {
            deltaMovement = table.velocity(slot);
            hasImpulse = table.needsSync(slot);
            position = table.position(slot);
            bb = table.bounds(slot);
            table.unbind(slot);
            eco$bodyTable = null;
        }
    }
}

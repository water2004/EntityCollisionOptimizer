package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.CollisionBodyAccess;
import org.edtp.entitycollisionoptimizer.natives.CollisionStateTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Native pushing owns bound velocity and sync state; position remains a vanilla field. */
@Mixin(Entity.class)
public abstract class EntityBodyMixin implements CollisionBodyAccess {
    @Shadow private Vec3 position;
    @Shadow private Vec3 deltaMovement;
    @Shadow public boolean needsSync;
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
        return eco$bodyTable == null ? needsSync : eco$bodyTable.needsSync(eco$bodySlot);
    }

    @Override public final void eco$writeNeedsSync(boolean value) {
        if (eco$bodyTable == null) needsSync = value;
        else eco$bodyTable.needsSync(eco$bodySlot, value);
    }

    @Override public final void eco$writeNoPhysics(boolean value) {
        noPhysics = value;
        eco$invalidatePushState();
    }

    @Override public final void eco$writePosition(Vec3 value) {
        // Publish at the original field store, before section/world callbacks, never during a query.
        position = value;
        if (eco$bodyTable != null) eco$bodyTable.position(eco$bodySlot, value);
    }

    @Override public final void eco$invalidatePushState() {
        if (eco$bodyTable != null) eco$bodyTable.invalidatePushState(eco$bodySlot);
    }

    @Override public final int eco$boundBodySlot(CollisionStateTable table) {
        return eco$bodyTable == table ? eco$bodySlot : -1;
    }

    @Override public final void eco$bindBody(CollisionStateTable table, int slot) {
        if (eco$bodyTable != table || eco$bodySlot != slot) {
            // Also handles moving an entity between tables: read the former owner before rebinding.
            table.velocity(slot, eco$readVelocity());
            table.needsSync(slot, eco$readNeedsSync());
            table.position(slot, position);
            if (eco$bodyTable != null) eco$bodyTable.transferBodyOut(eco$bodySlot);
            eco$bodyTable = table;
            eco$bodySlot = slot;
            table.bind(slot);
        }
    }

    @Override public final void eco$detachBody(CollisionStateTable table, int slot) {
        if (eco$bodyTable == table && eco$bodySlot == slot) {
            deltaMovement = table.velocity(slot);
            needsSync = table.needsSync(slot);
            table.unbind(slot);
            eco$bodyTable = null;
        }
    }
}

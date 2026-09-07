package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Independent merged consumers, not calls to production state-table helpers. */
@Mixin(Entity.class)
public interface EntityBodyTestAccess {
    @Accessor("needsSync") boolean eco$rawNeedsSync();
    @Accessor("needsSync") void eco$rawNeedsSync(boolean value);
    @Accessor("position") Vec3 eco$rawPosition();
    @Accessor("position") void eco$rawPosition(Vec3 value);
    @Invoker("unsetRemoved") void eco$unsetRemoved();
}

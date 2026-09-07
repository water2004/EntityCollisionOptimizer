package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityVelocityAccessor {
    @Accessor("deltaMovement") Vec3 eco$rawVelocity();
    @Accessor("deltaMovement") void eco$rawVelocity(Vec3 value);
}

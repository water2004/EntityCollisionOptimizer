package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface AttackStrengthAccessor {
    @Accessor("attackStrengthTicker") void eco$attackStrength(int ticks);
}

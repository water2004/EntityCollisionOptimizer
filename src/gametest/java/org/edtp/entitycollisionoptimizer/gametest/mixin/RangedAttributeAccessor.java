package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RangedAttribute.class)
public interface RangedAttributeAccessor {
    @Accessor("maxValue")
    double entityCollisionOptimizer$getMaxValue();

    @Mutable
    @Accessor("maxValue")
    void entityCollisionOptimizer$setMaxValue(double maxValue);
}

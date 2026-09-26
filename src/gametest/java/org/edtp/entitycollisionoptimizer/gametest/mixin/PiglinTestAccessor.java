package org.edtp.entitycollisionoptimizer.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.world.entity.monster.piglin.AbstractPiglin.class)
public interface PiglinTestAccessor {
    @Accessor("timeInOverworld")
    void eco$timeInOverworld(int value);
}

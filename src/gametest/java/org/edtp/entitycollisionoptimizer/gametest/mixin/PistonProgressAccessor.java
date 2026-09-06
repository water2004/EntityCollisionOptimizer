package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PistonMovingBlockEntity.class)
public interface PistonProgressAccessor {
    @Accessor("progress") void eco$progress(float progress);
    @Accessor("progressO") void eco$previousProgress(float progress);
}

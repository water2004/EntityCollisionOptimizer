package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Zombie.class)
public interface ZombieTestInvoker {
    @Invoker("startUnderWaterConversion")
    void entityCollisionOptimizer$startUnderWaterConversion(int ticks);
}

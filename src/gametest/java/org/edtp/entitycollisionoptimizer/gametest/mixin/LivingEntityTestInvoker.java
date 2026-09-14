package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityTestInvoker {
    @Invoker("pushEntities") void entityCollisionOptimizer$invokePushEntities();
    @Invoker("doPush") void entityCollisionOptimizer$invokeDoPush(Entity entity);
}

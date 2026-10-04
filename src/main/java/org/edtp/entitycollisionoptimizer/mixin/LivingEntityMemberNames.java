package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Name source only; see {@link EntityMemberNames}. */
@Mixin(LivingEntity.class)
public interface LivingEntityMemberNames {
    @Invoker("doPush")
    void eco$invokeDoPush(Entity entity);
}

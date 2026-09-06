package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractWindCharge.class)
public interface WindChargeTestInvoker {
    @Invoker("onHit") void eco$hit(HitResult hit);
}

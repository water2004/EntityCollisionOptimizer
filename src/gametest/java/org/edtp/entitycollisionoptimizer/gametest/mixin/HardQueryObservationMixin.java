package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.gametest.MovementShortCircuitChecks;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Unit-test observer only; not loaded in benchmarks or the released mod. */
@Mixin(value = CollisionFrame.class, remap = false)
public abstract class HardQueryObservationMixin {
    @Inject(method = "hardCollisionIds", at = @At("HEAD"))
    private static void eco$observeQuery(Entity source, AABB scan, CallbackInfoReturnable<int[]> cir) {
        MovementShortCircuitChecks.queried(source);
    }
}

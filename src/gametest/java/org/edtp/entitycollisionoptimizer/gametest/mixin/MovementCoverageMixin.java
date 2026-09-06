package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;
import org.edtp.entitycollisionoptimizer.gametest.MovementTakeoverCoverage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityMovementCollision.class, remap = false)
public abstract class MovementCoverageMixin {
    @Inject(method = "collide", at = @At("HEAD"))
    private static void eco$cover(Entity entity, Vec3 requested, CallbackInfoReturnable<Vec3> cir) {
        MovementTakeoverCoverage.entered();
    }
}

package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.CollisionImpulseState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Defers Vec3 allocation, not arithmetic or observable velocity/synchronization semantics. */
@Mixin(Entity.class)
public abstract class EntityImpulseMixin implements CollisionImpulseState {
    @Shadow private Vec3 deltaMovement;
    @Shadow public boolean needsSync;
    @Unique private boolean eco$pending;
    @Unique private double eco$x, eco$y, eco$z;

    @Override
    public void entityCollisionOptimizer$queueCollisionImpulse(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return;
        double nextX = (eco$pending ? eco$x : deltaMovement.x) + x;
        double nextY = (eco$pending ? eco$y : deltaMovement.y) + 0.0;
        double nextZ = (eco$pending ? eco$z : deltaMovement.z) + z;
        // Vanilla rejects a non-finite resulting Vec3, but still marks a finite push for sync.
        if (Double.isFinite(nextX) && Double.isFinite(nextY) && Double.isFinite(nextZ)) {
            eco$x = nextX;
            eco$y = nextY;
            eco$z = nextZ;
            eco$pending = true;
        }
        needsSync = true;
    }

    @Override
    public void entityCollisionOptimizer$flushCollisionImpulse() {
        if (!eco$pending) return;
        deltaMovement = new Vec3(eco$x, eco$y, eco$z);
        eco$pending = false;
    }

    @Inject(method = "getDeltaMovement", at = @At("HEAD"))
    private void eco$observe(CallbackInfoReturnable<Vec3> cir) {
        entityCollisionOptimizer$flushCollisionImpulse();
    }

    @Inject(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void eco$replace(Vec3 replacement, CallbackInfo ci) {
        if (replacement.isFinite()) eco$pending = false;
    }
}

package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.CollisionImpulseState;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin implements CollisionCacheState, CollisionImpulseState {
    @Shadow
    private Vec3 deltaMovement;

    @Shadow
    private boolean needsSync;

    @Unique
    private long entityCollisionOptimizer$collisionRevision;

    @Unique
    private double entityCollisionOptimizer$pendingCollisionX;

    @Unique
    private double entityCollisionOptimizer$pendingCollisionZ;

    @Override
    public void entityCollisionOptimizer$queueCollisionImpulse(double x, double z) {
        entityCollisionOptimizer$pendingCollisionX += x;
        entityCollisionOptimizer$pendingCollisionZ += z;
    }

    @Override
    public void entityCollisionOptimizer$flushCollisionImpulse() {
        double x = entityCollisionOptimizer$pendingCollisionX;
        double z = entityCollisionOptimizer$pendingCollisionZ;
        if (x == 0.0 && z == 0.0) {
            return;
        }
        entityCollisionOptimizer$pendingCollisionX = 0.0;
        entityCollisionOptimizer$pendingCollisionZ = 0.0;
        deltaMovement = deltaMovement.add(x, 0.0, z);
        needsSync = true;
    }

    @Inject(method = "getDeltaMovement", at = @At("HEAD"))
    private void entityCollisionOptimizer$flushBeforeVelocityRead(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Vec3> cir
    ) {
        entityCollisionOptimizer$flushCollisionImpulse();
    }

    @Override
    public long entityCollisionOptimizer$collisionRevision() {
        return entityCollisionOptimizer$collisionRevision;
    }

    @Override
    public void entityCollisionOptimizer$invalidateCollisionCache() {
        entityCollisionOptimizer$collisionRevision++;
        CollisionFrame.invalidateEntity((Entity) (Object) this);
    }

    @Inject(
            method = "setBoundingBox(Lnet/minecraft/world/phys/AABB;)V",
            at = @At("RETURN")
    )
    private void entityCollisionOptimizer$onSetBoundingBox(AABB boundingBox, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        entityCollisionOptimizer$collisionRevision++;
        CollisionFrame.updateBoundingBox(self, boundingBox);
    }

    @Inject(method = "setRemoved", at = @At("RETURN"))
    private void entityCollisionOptimizer$onSetRemoved(
            Entity.RemovalReason reason,
            CallbackInfo ci
    ) {
        entityCollisionOptimizer$invalidateCollisionCache();
    }

    @Inject(method = "unsetRemoved", at = @At("RETURN"))
    private void entityCollisionOptimizer$onUnsetRemoved(CallbackInfo ci) {
        entityCollisionOptimizer$invalidateCollisionCache();
    }

    @Inject(method = "setSharedFlag", at = @At("RETURN"))
    private void entityCollisionOptimizer$onSetSharedFlag(
            int flag,
            boolean value,
            CallbackInfo ci
    ) {
        if (flag == 7) {
            entityCollisionOptimizer$invalidateCollisionCache();
        }
    }

    @Inject(method = "setPose", at = @At("RETURN"))
    private void entityCollisionOptimizer$onSetPose(Pose pose, CallbackInfo ci) {
        entityCollisionOptimizer$invalidateCollisionCache();
    }

    @Inject(method = "addPassenger", at = @At("RETURN"))
    private void entityCollisionOptimizer$onAddPassenger(Entity passenger, CallbackInfo ci) {
        entityCollisionOptimizer$invalidateCollisionCache();
        ((CollisionCacheState) passenger).entityCollisionOptimizer$invalidateCollisionCache();
    }

    @Inject(method = "removePassenger", at = @At("RETURN"))
    private void entityCollisionOptimizer$onRemovePassenger(Entity passenger, CallbackInfo ci) {
        entityCollisionOptimizer$invalidateCollisionCache();
        ((CollisionCacheState) passenger).entityCollisionOptimizer$invalidateCollisionCache();
    }
}

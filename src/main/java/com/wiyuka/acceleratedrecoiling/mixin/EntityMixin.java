package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.collision.CollisionCacheState;
import com.wiyuka.acceleratedrecoiling.natives.CollisionFrame;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin implements CollisionCacheState {
    @Unique
    private long acceleratedRecoiling$collisionRevision;

    @Override
    public long acceleratedRecoiling$collisionRevision() {
        return acceleratedRecoiling$collisionRevision;
    }

    @Override
    public void acceleratedRecoiling$invalidateCollisionCache() {
        acceleratedRecoiling$collisionRevision++;
        CollisionFrame.invalidateEntity((Entity) (Object) this);
    }

    @Inject(
            method = "setBoundingBox(Lnet/minecraft/world/phys/AABB;)V",
            at = @At("RETURN")
    )
    private void acceleratedRecoiling$onSetBoundingBox(AABB boundingBox, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        acceleratedRecoiling$collisionRevision++;
        CollisionFrame.updateBoundingBox(self, boundingBox);
    }

    @Inject(method = "setRemoved", at = @At("RETURN"))
    private void acceleratedRecoiling$onSetRemoved(
            Entity.RemovalReason reason,
            CallbackInfo ci
    ) {
        acceleratedRecoiling$invalidateCollisionCache();
    }

    @Inject(method = "unsetRemoved", at = @At("RETURN"))
    private void acceleratedRecoiling$onUnsetRemoved(CallbackInfo ci) {
        acceleratedRecoiling$invalidateCollisionCache();
    }

    @Inject(method = "setSharedFlag", at = @At("RETURN"))
    private void acceleratedRecoiling$onSetSharedFlag(
            int flag,
            boolean value,
            CallbackInfo ci
    ) {
        if (flag == 7) {
            acceleratedRecoiling$invalidateCollisionCache();
        }
    }

    @Inject(method = "setPose", at = @At("RETURN"))
    private void acceleratedRecoiling$onSetPose(Pose pose, CallbackInfo ci) {
        acceleratedRecoiling$invalidateCollisionCache();
    }

    @Inject(method = "addPassenger", at = @At("RETURN"))
    private void acceleratedRecoiling$onAddPassenger(Entity passenger, CallbackInfo ci) {
        acceleratedRecoiling$invalidateCollisionCache();
        ((CollisionCacheState) passenger).acceleratedRecoiling$invalidateCollisionCache();
    }

    @Inject(method = "removePassenger", at = @At("RETURN"))
    private void acceleratedRecoiling$onRemovePassenger(Entity passenger, CallbackInfo ci) {
        acceleratedRecoiling$invalidateCollisionCache();
        ((CollisionCacheState) passenger).acceleratedRecoiling$invalidateCollisionCache();
    }
}

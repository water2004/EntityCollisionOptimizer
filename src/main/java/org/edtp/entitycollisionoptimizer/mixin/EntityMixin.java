package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.CollisionOrderState;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin implements CollisionCacheState, CollisionOrderState {
    @Unique private long eco$sectionOrder;

    @Override public long eco$sectionOrder() { return eco$sectionOrder; }

    @Override public void eco$sectionOrder(long order) {
        eco$sectionOrder = order;
        entityCollisionOptimizer$invalidateCollisionCache();
    }

    @Unique
    private long entityCollisionOptimizer$collisionRevision;

    @Unique
    private long entityCollisionOptimizer$pushableEntityRevision = Long.MIN_VALUE;

    @Unique
    private long entityCollisionOptimizer$pushableBlockRevision = Long.MIN_VALUE;

    @Unique
    private boolean entityCollisionOptimizer$pushable;

    @Override
    public boolean entityCollisionOptimizer$isPushableCached() {
        long blocks = CollisionCacheEpochs.blockRevision();
        if (entityCollisionOptimizer$pushableEntityRevision != entityCollisionOptimizer$collisionRevision
                || entityCollisionOptimizer$pushableBlockRevision != blocks) {
            entityCollisionOptimizer$pushable = ((Entity) (Object) this).isPushable();
            entityCollisionOptimizer$pushableEntityRevision = entityCollisionOptimizer$collisionRevision;
            entityCollisionOptimizer$pushableBlockRevision = blocks;
        }
        return entityCollisionOptimizer$pushable;
    }

    @Override
    public void entityCollisionOptimizer$resetPushabilityCache() {
        entityCollisionOptimizer$pushableEntityRevision = Long.MIN_VALUE;
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

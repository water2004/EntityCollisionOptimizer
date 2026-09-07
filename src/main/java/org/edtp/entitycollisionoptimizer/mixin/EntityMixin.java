package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.CollisionBodyAccess;
import org.edtp.entitycollisionoptimizer.collision.CollisionOrderState;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

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
    private int entityCollisionOptimizer$pushState;

    @Override
    public boolean entityCollisionOptimizer$isPushableCached() {
        return (entityCollisionOptimizer$pushState() & PUSHABLE) != 0;
    }

    @Override
    public int entityCollisionOptimizer$pushState() {
        long blocks = CollisionCacheEpochs.blockRevision();
        if (entityCollisionOptimizer$pushableEntityRevision != entityCollisionOptimizer$collisionRevision
                || entityCollisionOptimizer$pushableBlockRevision != blocks) {
            Entity self = (Entity) (Object) this;
            entityCollisionOptimizer$pushState = (self.isPushable() ? PUSHABLE : 0)
                    | (self.isVehicle() ? VEHICLE : 0) | (self.isPassenger() ? PASSENGER : 0)
                    | (self instanceof LivingEntity living && living.isSleeping() ? SLEEPING : 0);
            entityCollisionOptimizer$pushableEntityRevision = entityCollisionOptimizer$collisionRevision;
            entityCollisionOptimizer$pushableBlockRevision = blocks;
        }
        return entityCollisionOptimizer$pushState;
    }

    @Override
    public void entityCollisionOptimizer$resetPushabilityCache() {
        entityCollisionOptimizer$pushableEntityRevision = Long.MIN_VALUE;
        ((CollisionBodyAccess) this).eco$invalidatePushState();
    }

    @Override
    public long entityCollisionOptimizer$collisionRevision() {
        return entityCollisionOptimizer$collisionRevision;
    }

    @Override
    public void entityCollisionOptimizer$invalidateCollisionCache() {
        entityCollisionOptimizer$collisionRevision++;
        ((CollisionBodyAccess) this).eco$invalidatePushState();
        CollisionFrame.invalidateEntity((Entity) (Object) this);
    }

    @Inject(
            method = "setBoundingBox(Lnet/minecraft/world/phys/AABB;)V",
            at = @At("RETURN")
    )
    private void entityCollisionOptimizer$onSetBoundingBox(AABB boundingBox, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        entityCollisionOptimizer$collisionRevision++;
        ((CollisionBodyAccess) this).eco$invalidatePushState();
        CollisionFrame.updateBoundingBox(self);
    }

    @Inject(method = "setRemoved", at = @At("RETURN"))
    private void entityCollisionOptimizer$onSetRemoved(
            Entity.RemovalReason reason,
            CallbackInfo ci
    ) {
        entityCollisionOptimizer$invalidateCollisionCache();
    }

    @Inject(method = {"baseTick", "setPosRaw"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/Entity;inBlockState:Lnet/minecraft/world/level/block/state/BlockState;",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void eco$onInBlockStateExpired(CallbackInfo ci) {
        // Pushability depends on vanilla's cached in-block state, not just the world block revision.
        // Expire derived state at the same point, including raw position updates without an AABB update.
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
        CollisionCacheEpochs.invalidateVehicles();
        entityCollisionOptimizer$invalidateCollisionCache();
        ((CollisionCacheState) passenger).entityCollisionOptimizer$invalidateCollisionCache();
    }

    @Inject(method = "removePassenger", at = @At("RETURN"))
    private void entityCollisionOptimizer$onRemovePassenger(Entity passenger, CallbackInfo ci) {
        CollisionCacheEpochs.invalidateVehicles();
        entityCollisionOptimizer$invalidateCollisionCache();
        ((CollisionCacheState) passenger).entityCollisionOptimizer$invalidateCollisionCache();
    }
}

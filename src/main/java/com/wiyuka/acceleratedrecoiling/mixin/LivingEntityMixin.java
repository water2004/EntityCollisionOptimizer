package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.CollisionMapData;
import com.wiyuka.acceleratedrecoiling.natives.TempID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LivingEntity.class, priority = 1100)
public abstract class LivingEntityMixin {
    @Shadow
    protected abstract void doPush(Entity entity);

    @Unique
    private int acceleratedRecoiling$lastClimbableCheckTick = -1;

    @Unique
    private boolean acceleratedRecoiling$cachedClimbableResult;

    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void acceleratedRecoiling$pushDenseEntities(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!FoldConfig.enableEntityCollision
                || self instanceof Player
                || self.level().isClientSide()
                || ((ICustomData) self).getDensity() < FoldConfig.densityThreshold) {
            return;
        }

        ci.cancel();
        int collisionStart = CollisionMapData.getCollisionStart(self);
        int collisionEnd = CollisionMapData.getCollisionEnd(self);
        if (collisionStart == collisionEnd) {
            return;
        }

        int[] collisionTargets = CollisionMapData.collisionTargets();
        AABB selfBox = self.getBoundingBox();
        if (self.level() instanceof ServerLevel serverLevel) {
            int maxEntityCramming = serverLevel.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
            int collisionCount = collisionEnd - collisionStart;
            if (maxEntityCramming > 0
                    && collisionCount > maxEntityCramming - 1
                    && self.getRandom().nextInt(4) == 0) {
                int nonPassengers = 0;
                for (int i = collisionStart; i < collisionEnd; i++) {
                    Entity target = TempID.getEntity(collisionTargets[i]);
                    if (target != null
                            && !target.isPassenger()
                            && selfBox.intersects(target.getBoundingBox())) {
                        nonPassengers++;
                    }
                }
                if (nonPassengers > maxEntityCramming - 1) {
                    self.hurtServer(serverLevel, self.damageSources().cramming(), 6.0F);
                }
            }
        }

        for (int i = collisionStart; i < collisionEnd; i++) {
            Entity target = TempID.getEntity(collisionTargets[i]);
            if (target != null && selfBox.intersects(target.getBoundingBox())) {
                doPush(target);
            }
        }
    }

    @WrapOperation(
            method = "pushEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;doPush(Lnet/minecraft/world/entity/Entity;)V"
            )
    )
    private void acceleratedRecoiling$verifyIntersection(
            LivingEntity instance,
            Entity entity,
            Operation<Void> original
    ) {
        if (!FoldConfig.enableEntityCollision || instance.getBoundingBox().intersects(entity.getBoundingBox())) {
            original.call(instance, entity);
        }
    }

    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void acceleratedRecoiling$useCachedClimbable(CallbackInfoReturnable<Boolean> cir) {
        if (!FoldConfig.enableEntityCollision) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.tickCount == acceleratedRecoiling$lastClimbableCheckTick) {
            cir.setReturnValue(acceleratedRecoiling$cachedClimbableResult);
        }
    }

    @Inject(method = "onClimbable", at = @At("RETURN"))
    private void acceleratedRecoiling$cacheClimbable(CallbackInfoReturnable<Boolean> cir) {
        if (!FoldConfig.enableEntityCollision) {
            return;
        }
        acceleratedRecoiling$lastClimbableCheckTick = ((LivingEntity) (Object) this).tickCount;
        acceleratedRecoiling$cachedClimbableResult = cir.getReturnValueZ();
    }

    @WrapOperation(
            method = "pushEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;isPassenger()Z"
            )
    )
    private boolean acceleratedRecoiling$treatNonIntersectingAsPassenger(
            Entity instance,
            Operation<Boolean> original
    ) {
        if (!FoldConfig.enableEntityCollision) {
            return original.call(instance);
        }
        AABB selfBox = ((LivingEntity) (Object) this).getBoundingBox();
        return original.call(instance) || !selfBox.intersects(instance.getBoundingBox());
    }
}

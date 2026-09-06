package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.collision.VanillaEntityCollision;
import com.wiyuka.acceleratedrecoiling.compat.CarpetCompatibility;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.CollisionFrame;
import com.wiyuka.acceleratedrecoiling.natives.FFMBackend;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LivingEntity.class, priority = 1100)
public abstract class LivingEntityMixin {
    @Shadow
    protected abstract void doPush(Entity entity);

    @Unique
    private Entity[] acceleratedRecoiling$pushableTargets = new Entity[0];

    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void acceleratedRecoiling$pushEntities(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!FoldConfig.enableEntityCollision
                || CarpetCompatibility.ownsEntityCollisions()
                || !(self.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        ci.cancel();
        PlayerTeam sourceTeam = self.getTeam();
        Team.CollisionRule sourceRule = VanillaEntityCollision.collisionRule(sourceTeam);
        if (sourceRule == Team.CollisionRule.NEVER) {
            return;
        }

        boolean sourceUsesVanillaDoPush = VanillaEntityCollision.usesVanillaDoPush(self);
        FFMBackend.QueryResult candidates = CollisionFrame.queryPushable(
                self,
                sourceTeam,
                sourceRule,
                sourceUsesVanillaDoPush
        );
        int pushableCount = candidates.pushableCount();
        int nonPassengerCount = candidates.nonPassengerCount();
        int actionableCount = candidates.size();
        if (pushableCount == 0) {
            return;
        }

        acceleratedRecoiling$ensureTargetCapacity(actionableCount);
        for (int index = 0; index < actionableCount; index++) {
            int targetId = candidates.get(index);
            Entity target = CollisionFrame.entity(self, targetId);
            if (target == null) {
                throw new IllegalStateException(
                        "Native collision query returned unknown entity " + targetId
                );
            }
            acceleratedRecoiling$pushableTargets[index] = target;
        }

        int maxEntityCramming = serverLevel.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        if (maxEntityCramming > 0
                && pushableCount > maxEntityCramming - 1
                && self.getRandom().nextInt(4) == 0) {
            if (nonPassengerCount > maxEntityCramming - 1) {
                self.hurtServer(serverLevel, self.damageSources().cramming(), 6.0F);
            }
        }

        for (int index = 0; index < actionableCount; index++) {
            Entity target = acceleratedRecoiling$pushableTargets[index];
            doPush(target);
            acceleratedRecoiling$pushableTargets[index] = null;
        }
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void acceleratedRecoiling$onSetHealth(float health, CallbackInfo ci) {
        ((com.wiyuka.acceleratedrecoiling.collision.CollisionCacheState) this)
                .acceleratedRecoiling$invalidateCollisionCache();
    }

    @Unique
    private void acceleratedRecoiling$ensureTargetCapacity(int requiredCapacity) {
        if (requiredCapacity <= acceleratedRecoiling$pushableTargets.length) {
            return;
        }
        int currentCapacity = acceleratedRecoiling$pushableTargets.length;
        int grownCapacity = currentCapacity + (currentCapacity >> 1) + 1;
        acceleratedRecoiling$pushableTargets = java.util.Arrays.copyOf(
                acceleratedRecoiling$pushableTargets,
                Math.max(requiredCapacity, grownCapacity)
        );
    }
}

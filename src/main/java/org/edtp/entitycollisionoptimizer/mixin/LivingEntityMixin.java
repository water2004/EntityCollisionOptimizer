package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.VanillaEntityCollision;
import org.edtp.entitycollisionoptimizer.collision.CollisionImpulseState;
import org.edtp.entitycollisionoptimizer.compat.CarpetCompatibility;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;
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
    private Entity[] entityCollisionOptimizer$pushableTargets = new Entity[0];

    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void entityCollisionOptimizer$pushEntities(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!CollisionOptimizerConfig.enableEntityCollision
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

        entityCollisionOptimizer$ensureTargetCapacity(actionableCount);
        for (int index = 0; index < actionableCount; index++) {
            int targetId = candidates.get(index);
            Entity target = CollisionFrame.entity(self, targetId);
            if (target == null) {
                throw new IllegalStateException(
                        "Native collision query returned unknown entity " + targetId
                );
            }
            entityCollisionOptimizer$pushableTargets[index] = target;
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
            Entity target = entityCollisionOptimizer$pushableTargets[index];
            if (candidates.hasNativeImpulse(index)
                    && !self.noPhysics
                    && !target.noPhysics
                    && !self.isPassenger()
                    && !target.isPassenger()
                    && !self.isVehicle()
                    && !target.isVehicle()
                    && !self.isPassengerOfSameVehicle(target)) {
                ((CollisionImpulseState) self).entityCollisionOptimizer$queueCollisionImpulse(
                        candidates.sourceImpulseX(index),
                        candidates.sourceImpulseZ(index)
                );
                ((CollisionImpulseState) target).entityCollisionOptimizer$queueCollisionImpulse(
                        candidates.targetImpulseX(index),
                        candidates.targetImpulseZ(index)
                );
            } else {
                doPush(target);
            }
            entityCollisionOptimizer$pushableTargets[index] = null;
        }
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void entityCollisionOptimizer$onSetHealth(float health, CallbackInfo ci) {
        ((org.edtp.entitycollisionoptimizer.collision.CollisionCacheState) this)
                .entityCollisionOptimizer$invalidateCollisionCache();
    }

    @Unique
    private void entityCollisionOptimizer$ensureTargetCapacity(int requiredCapacity) {
        if (requiredCapacity <= entityCollisionOptimizer$pushableTargets.length) {
            return;
        }
        int currentCapacity = entityCollisionOptimizer$pushableTargets.length;
        int grownCapacity = currentCapacity + (currentCapacity >> 1) + 1;
        entityCollisionOptimizer$pushableTargets = java.util.Arrays.copyOf(
                entityCollisionOptimizer$pushableTargets,
                Math.max(requiredCapacity, grownCapacity)
        );
    }
}

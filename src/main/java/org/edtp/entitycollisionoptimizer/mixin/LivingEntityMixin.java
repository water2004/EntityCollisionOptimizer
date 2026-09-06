package org.edtp.entitycollisionoptimizer.mixin;

import org.edtp.entitycollisionoptimizer.collision.VanillaEntityCollision;
import org.edtp.entitycollisionoptimizer.compat.CarpetCompatibility;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.PushBatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LivingEntity.class, priority = 1100)
public abstract class LivingEntityMixin {
    @Shadow
    protected abstract void doPush(Entity entity);

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
        try (PushBatch candidates = CollisionFrame.collectPushable(
                self,
                sourceTeam,
                sourceRule,
                sourceUsesVanillaDoPush
        )) {
            int pushableCount = candidates.pushableCount();
            int nonPassengerCount = candidates.nonPassengerCount();
            if (pushableCount == 0) {
                return;
            }

            int maxEntityCramming = serverLevel.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
            if (maxEntityCramming > 0
                    && pushableCount > maxEntityCramming - 1
                    && self.getRandom().nextInt(4) == 0) {
                if (nonPassengerCount > maxEntityCramming - 1) {
                    self.hurtServer(serverLevel, self.damageSources().cramming(), 6.0F);
                }
            }

            for (int index = 0; index < candidates.size();) {
                if (candidates.usesNativePush(index)) {
                    int end = index + 1;
                    while (end < candidates.size() && candidates.usesNativePush(end)) {
                        end++;
                    }
                    candidates.applyNativeRun(self, index, end);
                    index = end;
                } else {
                    doPush(candidates.target(index++));
                }
            }
        }
    }
}

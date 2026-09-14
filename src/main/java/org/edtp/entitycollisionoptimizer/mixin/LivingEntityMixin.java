package org.edtp.entitycollisionoptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.edtp.entitycollisionoptimizer.collision.VanillaMethodDetector;
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

@Mixin(value = LivingEntity.class, priority = 1100)
public abstract class LivingEntityMixin {
    @Shadow
    protected abstract void doPush(Entity entity);

    // Own the method outside HEAD injections, without depending on their execution order.
    @WrapMethod(method = "pushEntities")
    private void entityCollisionOptimizer$pushEntities(Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel serverLevel)) {
            original.call();
            return;
        }

        PlayerTeam sourceTeam = self.getTeam();
        Team.CollisionRule sourceRule = sourceTeam == null
                ? Team.CollisionRule.ALWAYS : sourceTeam.getCollisionRule();
        if (sourceRule == Team.CollisionRule.NEVER) {
            return;
        }

        boolean sourceUsesVanillaDoPush = VanillaMethodDetector.usesVanillaDoPush(self);
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

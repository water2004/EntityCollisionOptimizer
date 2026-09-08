package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

final class CollisionParity {
    private CollisionParity() {
    }

    static void verifyLowDensity(GameTestHelper helper) {
        boolean originalCollisionMode = CollisionOptimizerConfig.enableEntityCollision;
        ServerLevel level = helper.getLevel();
        int originalCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        try {
            CollisionPredicateParity.verifyPushabilityPredicate(helper);
            CollisionPredicateParity.verifyLiveSpatialIndex(helper);
            HardCollisionParity.verify(helper);
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
            CollisionPushParity.verifyPushOutcome(helper, false);
            CollisionPushParity.verifyPushOutcome(helper, true);
            CollisionCrammingParity.verifyDamage(helper);
            CollisionCrammingParity.verifyExactOverlap(helper);
            CollisionCrammingParity.verifyPassengerExclusion(helper);
            CollisionDispatchParity.verify(helper);
            CollisionTransitionParity.verify(helper);
            CollisionRepeatedFrameParity.verify(helper);
        } finally {
            CollisionOptimizerConfig.enableEntityCollision = originalCollisionMode;
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, originalCramming, level.getServer());
            CollisionFrame.end(level);
        }
    }

    static void verifyMediumDensity(GameTestHelper helper) {
        boolean originalCollisionMode = CollisionOptimizerConfig.enableEntityCollision;
        ServerLevel level = helper.getLevel();
        int originalCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        try {
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
            CollisionMediumDensityParity.verify(helper, "all-rules-always", 20, false, 0.5);
            CollisionMediumDensityParity.verify(helper, "mixed-rules-and-state", 24, true, 8.5);
        } finally {
            CollisionOptimizerConfig.enableEntityCollision = originalCollisionMode;
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, originalCramming, level.getServer());
            CollisionFrame.end(level);
        }
    }

    static void verifyConcurrentLevelIsolation(GameTestHelper helper) {
        CollisionIsolationParity.verify(helper);
    }
}

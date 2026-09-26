package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

final class CollisionParity {
    private CollisionParity() {
    }

    static void verifyLowDensity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int originalCramming = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        try {
            CollisionPredicateParity.verifyPushabilityPredicate(helper);
            CollisionPredicateParity.verifyLiveSpatialIndex(helper);
            HardCollisionParity.verify(helper);
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(0, level.getServer());
            CollisionPushParity.verifyPushOutcome(helper, false);
            CollisionPushParity.verifyPushOutcome(helper, true);
            CollisionCrammingParity.verifyDamage(helper);
            CollisionCrammingParity.verifyExactOverlap(helper);
            CollisionCrammingParity.verifyPassengerExclusion(helper);
            CollisionDispatchParity.verify(helper);
            CollisionTransitionParity.verify(helper);
            CollisionRepeatedFrameParity.verify(helper);
        } finally {
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(originalCramming, level.getServer());
            CollisionFrame.end(level);
        }
    }

    static void verifyMediumDensity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int originalCramming = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        try {
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(0, level.getServer());
            CollisionMediumDensityParity.verify(helper, "all-rules-always", 20, false, 0.5);
            CollisionMediumDensityParity.verify(helper, "mixed-rules-and-state", 24, true, 8.5);
        } finally {
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(originalCramming, level.getServer());
            CollisionFrame.end(level);
        }
    }

    static void verifyConcurrentLevelIsolation(GameTestHelper helper) {
        CollisionIsolationParity.verify(helper);
    }
}

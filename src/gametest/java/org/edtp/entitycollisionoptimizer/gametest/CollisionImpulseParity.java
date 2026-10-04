package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.scores.Team;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.*;

/** Exercises observations between pushing and the first subsequent velocity read. */
final class CollisionImpulseParity {
    static void verify(GameTestHelper helper) {
        var level = helper.getLevel();
        int cramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        try {
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
            for (int scenario = 0; scenario < 5; scenario++) {
                Outcome expected = run(helper, false, scenario);
                Outcome actual = run(helper, true, scenario);
                assertVectorEqual(helper, actual.source, expected.source, "impulse observation source " + scenario);
                assertVectorEqual(helper, actual.target, expected.target, "impulse observation target " + scenario);
                helper.assertValueEqual(actual.sync, expected.sync, "immediate sync flag " + scenario);
                helper.assertValueEqual(actual.health, expected.health, "impulse observation health " + scenario);
            }
        } finally {
            CollisionFrame.end(level);
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, cramming, level.getServer());
        }
    }

    private static Outcome run(GameTestHelper helper, boolean enabled, int scenario) {
        var level = helper.getLevel();
        CollisionFrame.end(level);
        Zombie source = spawnZombie(helper, new Vec3(1.5, 1, 18.5));
        Zombie target = spawnZombie(helper, new Vec3(1.7, 1, 18.6));
        try {
            source.setDeltaMovement(new Vec3(0.125, 0.25, -0.375));
            target.setDeltaMovement(Vec3.ZERO);
            source.needsSync = target.needsSync = false;
            if (scenario == 4) {
                level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 1, level.getServer());
                source.setInvulnerable(false);
                source.setHealth(1.0F);
                source.getRandom().setSeed(seedWhoseNextIntSucceeds(source, 4));
            }
            if (enabled) {
                CollisionFrame.begin(level);
                boolean covered = false;
                try (var batch = CollisionFrame.collectPushable(source, source.getTeam(),
                        source.getTeam() == null
                                ? Team.CollisionRule.ALWAYS : source.getTeam().getCollisionRule(), true)) {
                    for (int i = 0; i < batch.size(); i++) {
                        if (batch.target(i) == target) covered = batch.usesNativePush(i);
                    }
                }
                helper.assertTrue(covered, "ordinary zombie pair must use native impulse calculation");
            }
            ((LivingEntityTestInvoker) source).entityCollisionOptimizer$invokePushEntities();
            boolean immediateSync = target.needsSync;
            switch (scenario) {
                case 0 -> target.setDeltaMovement(new Vec3(0.75, 0.5, -0.25));
                case 1 -> target.setDeltaMovement(0.75, 0.5, -0.25);
                case 2 -> target.setDeltaMovement(new Vec3(Double.NaN, 0, 0));
                case 3 -> target.push(0.5, 0.25, -0.125);
                case 4 -> helper.assertTrue(!source.isAlive(), "cramming must kill source before pushing");
                default -> throw new AssertionError(scenario);
            }
            return new Outcome(source.getDeltaMovement(), target.getDeltaMovement(), immediateSync, source.getHealth());
        } finally {
            CollisionFrame.end(level);
            source.discard();
            target.discard();
            level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
        }
    }

    private record Outcome(Vec3 source, Vec3 target, boolean sync, float health) {}
}

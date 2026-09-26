package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.seedWhoseNextIntSucceeds;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.zeroVelocities;

final class CollisionCrammingParity {
    private CollisionCrammingParity() {
    }

    static void verifyDamage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int previousCramming = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        Vec3 vanillaAnchor = new Vec3(6.5, 1.0, 0.5);
        Vec3 acceleratedAnchor = new Vec3(9.5, 1.0, 0.5);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        List<Zombie> entities = new ArrayList<>(List.of(vanillaSource, acceleratedSource));
        entities.add(spawnZombie(helper, vanillaAnchor.add(0.1, 0.0, 0.0)));
        entities.add(spawnZombie(helper, vanillaAnchor.add(-0.1, 0.0, 0.0)));
        entities.add(spawnZombie(helper, acceleratedAnchor.add(0.1, 0.0, 0.0)));
        entities.add(spawnZombie(helper, acceleratedAnchor.add(-0.1, 0.0, 0.0)));

        try {
            vanillaSource.setInvulnerable(false);
            acceleratedSource.setInvulnerable(false);
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(1, level.getServer());
            long seed = seedWhoseNextIntSucceeds(vanillaSource, 4);

            vanillaSource.getRandom().setSeed(seed);
            VanillaReference.pushEntities(vanillaSource);

            acceleratedSource.getRandom().setSeed(seed);
            CollisionFrame.begin(level);
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            helper.assertValueEqual(
                    acceleratedSource.getHealth(),
                    vanillaSource.getHealth(),
                    "cramming damage parity"
            );
            helper.assertTrue(
                    vanillaSource.getHealth() < vanillaSource.getMaxHealth(),
                    "cramming parity setup must trigger damage"
            );
        } finally {
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(previousCramming, level.getServer());
            for (Zombie entity : entities) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }

    static void verifyPassengerExclusion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int previousCramming = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        Vec3 vanillaAnchor = new Vec3(7.5, 1.0, 8.5);
        Vec3 acceleratedAnchor = new Vec3(10.5, 1.0, 8.5);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaPassenger = spawnZombie(helper, vanillaAnchor);
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedPassenger = spawnZombie(helper, acceleratedAnchor);
        List<Zombie> entities = List.of(
                vanillaSource,
                vanillaPassenger,
                acceleratedSource,
                acceleratedPassenger
        );

        try {
            vanillaSource.setInvulnerable(false);
            acceleratedSource.setInvulnerable(false);
            vanillaPassenger.startRiding(vanillaSource, true);
            acceleratedPassenger.startRiding(acceleratedSource, true);
            vanillaPassenger.setPos(vanillaSource.position());
            acceleratedPassenger.setPos(acceleratedSource.position());
            helper.assertTrue(
                    vanillaPassenger.isPassenger() && acceleratedPassenger.isPassenger(),
                    "passenger cramming setup must create passengers"
            );
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(1, level.getServer());
            long seed = seedWhoseNextIntSucceeds(vanillaSource, 4);

            vanillaSource.getRandom().setSeed(seed);
            VanillaReference.pushEntities(vanillaSource);

            acceleratedSource.getRandom().setSeed(seed);
            CollisionFrame.begin(level);
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            helper.assertValueEqual(
                    acceleratedSource.getHealth(),
                    vanillaSource.getHealth(),
                    "passengers excluded from cramming parity"
            );
            helper.assertValueEqual(
                    vanillaSource.getHealth(),
                    vanillaSource.getMaxHealth(),
                    "passengers must not count toward cramming damage"
            );
        } finally {
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(previousCramming, level.getServer());
            for (Zombie entity : entities) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }

    static void verifyExactOverlap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int previousCramming = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        Vec3 vanillaAnchor = new Vec3(13.5, 1.0, 0.5);
        Vec3 acceleratedAnchor = new Vec3(16.5, 1.0, 0.5);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaFirst = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaSecond = spawnZombie(helper, vanillaAnchor);
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedFirst = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedSecond = spawnZombie(helper, acceleratedAnchor);
        List<Zombie> entities = List.of(
                vanillaSource,
                vanillaFirst,
                vanillaSecond,
                acceleratedSource,
                acceleratedFirst,
                acceleratedSecond
        );

        try {
            vanillaSource.setInvulnerable(false);
            acceleratedSource.setInvulnerable(false);
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(1, level.getServer());
            long seed = seedWhoseNextIntSucceeds(vanillaSource, 4);

            zeroVelocities(entities);
            vanillaSource.getRandom().setSeed(seed);
            VanillaReference.pushEntities(vanillaSource);

            zeroVelocities(entities);
            acceleratedSource.getRandom().setSeed(seed);
            CollisionFrame.begin(level);
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            helper.assertValueEqual(
                    acceleratedSource.getHealth(),
                    vanillaSource.getHealth(),
                    "exact-overlap no-op targets retain cramming damage"
            );
            helper.assertTrue(
                    vanillaSource.getHealth() < vanillaSource.getMaxHealth(),
                    "exact-overlap cramming setup must trigger damage"
            );
            for (Zombie entity : entities) {
                helper.assertTrue(
                        entity.getDeltaMovement().equals(Vec3.ZERO),
                        "exact-overlap vanilla push must remain a velocity no-op"
                );
            }
        } finally {
            level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(previousCramming, level.getServer());
            for (Zombie entity : entities) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }
}

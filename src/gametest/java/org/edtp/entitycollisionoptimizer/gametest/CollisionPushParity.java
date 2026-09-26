package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.List;
import java.util.UUID;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.assertVectorEqual;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnEntity;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnMockServerPlayer;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnPlayer;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.velocities;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.zeroVelocities;

final class CollisionPushParity {
    private CollisionPushParity() {
    }

    static void verifyPushOutcome(GameTestHelper helper, boolean playerSource) {
        Vec3 anchor = new Vec3(playerSource ? 3.5 : 0.5, 1.0, 3.5);
        LivingEntity source = playerSource
                ? spawnPlayer(helper, anchor)
                : spawnZombie(helper, anchor);
        Zombie first = spawnZombie(helper, anchor.add(0.18, 0.0, 0.07));
        Zombie second = spawnZombie(helper, anchor.add(-0.11, 0.0, 0.21));
        Zombie teamBlocked = spawnZombie(helper, anchor.add(0.05, 0.0, -0.16));
        Zombie exactOverlap = spawnZombie(helper, anchor);
        List<LivingEntity> entities = List.of(source, first, second, teamBlocked, exactOverlap);

        Scoreboard scoreboard = helper.getLevel().getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        PlayerTeam blockedTeam = scoreboard.addPlayerTeam("ar_b_" + suffix);
        blockedTeam.setCollisionRule(Team.CollisionRule.NEVER);
        scoreboard.addPlayerToTeam(teamBlocked.getScoreboardName(), blockedTeam);

        try {
            zeroVelocities(entities);
            VanillaReference.pushEntities(source);
            List<Vec3> vanilla = velocities(entities);

            zeroVelocities(entities);
            CollisionFrame.begin(helper.getLevel());
            ((LivingEntityTestInvoker) source).entityCollisionOptimizer$invokePushEntities();
            List<Vec3> accelerated = velocities(entities);

            for (int index = 0; index < entities.size(); index++) {
                Vec3 expected = vanilla.get(index);
                Vec3 actual = accelerated.get(index);
                helper.assertTrue(
                        expected.distanceToSqr(actual) <= 1.0E-24,
                        "push velocity parity (playerSource=" + playerSource + ", entity=" + index
                                + "): expected=" + expected + ", actual=" + actual
                );
            }
        } finally {
            scoreboard.removePlayerTeam(blockedTeam);
            for (LivingEntity entity : entities) {
                entity.discard();
            }
            CollisionFrame.end(helper.getLevel());
        }
    }

    static void verifyPlayerTargets(GameTestHelper helper, int firstScenarioIndex) {
        verifyParrotPlayerCollision(helper);
        verifyZombiePlayerCollision(helper, GameType.SURVIVAL, firstScenarioIndex);
        verifyZombiePlayerCollision(helper, GameType.CREATIVE, firstScenarioIndex + 1);
        verifyZombiePlayerCollision(helper, GameType.SPECTATOR, firstScenarioIndex + 2);
    }

    private static void verifyParrotPlayerCollision(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 anchor = new Vec3(0.5, 1.0, 35.0);
        ServerPlayer player = spawnPlayer(helper, anchor.add(0.18, 0.0, 0.07));
        Parrot vanillaSource = (Parrot) spawnEntity(helper, EntityType.PARROT, anchor);
        Parrot acceleratedSource = null;
        try {
            zeroVelocities(List.of(player, vanillaSource));
            VanillaReference.pushEntities(vanillaSource);
            Vec3 vanillaPlayerVelocity = player.getDeltaMovement();
            Vec3 vanillaSourceVelocity = vanillaSource.getDeltaMovement();

            vanillaSource.discard();
            player.setDeltaMovement(Vec3.ZERO);
            acceleratedSource = (Parrot) spawnEntity(helper, EntityType.PARROT, anchor);
            acceleratedSource.setDeltaMovement(Vec3.ZERO);
            CollisionFrame.begin(level);
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            assertVectorEqual(
                    helper,
                    acceleratedSource.getDeltaMovement(),
                    vanillaSourceVelocity,
                    "parrot source velocity against player"
            );
            assertVectorEqual(
                    helper,
                    player.getDeltaMovement(),
                    vanillaPlayerVelocity,
                    "parrot must not push player"
            );
        } finally {
            player.discard();
            vanillaSource.discard();
            if (acceleratedSource != null) {
                acceleratedSource.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static void verifyZombiePlayerCollision(
            GameTestHelper helper,
            GameType gameType,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        Vec3 anchor = new Vec3(1.5 + (scenarioIndex % 4) * 8.0, 1.0, 32.0);
        Vec3 playerPosition = anchor.add(0.18, 0.0, 0.07);
        Player player = spawnMockServerPlayer(helper, playerPosition, gameType);
        Zombie vanillaSource = spawnZombie(helper, anchor);
        Zombie acceleratedSource = null;
        try {
            helper.assertValueEqual(((net.minecraft.server.level.ServerPlayer) player).gameMode.getGameModeForPlayer(), gameType, "player mode setup: " + gameType);
            zeroVelocities(List.of(player, vanillaSource));
            CollisionFrame.end(level);
            VanillaReference.pushEntities(vanillaSource);
            Vec3 vanillaPlayerVelocity = player.getDeltaMovement();
            Vec3 vanillaSourceVelocity = vanillaSource.getDeltaMovement();

            vanillaSource.discard();
            player.setPos(helper.absoluteVec(playerPosition));
            player.setDeltaMovement(Vec3.ZERO);
            acceleratedSource = spawnZombie(helper, anchor);
            acceleratedSource.setDeltaMovement(Vec3.ZERO);
            CollisionFrame.begin(level);
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            assertVectorEqual(
                    helper,
                    acceleratedSource.getDeltaMovement(),
                    vanillaSourceVelocity,
                    "zombie source velocity against " + gameType + " player"
            );
            assertVectorEqual(
                    helper,
                    player.getDeltaMovement(),
                    vanillaPlayerVelocity,
                    gameType + " player target velocity"
            );
            if (gameType == GameType.SPECTATOR) {
                assertVectorEqual(helper, vanillaSourceVelocity, Vec3.ZERO, "spectator source exclusion");
                assertVectorEqual(helper, vanillaPlayerVelocity, Vec3.ZERO, "spectator target exclusion");
            }
        } finally {
            player.discard();
            vanillaSource.discard();
            if (acceleratedSource != null) {
                acceleratedSource.discard();
            }
            CollisionFrame.end(level);
        }
    }
}

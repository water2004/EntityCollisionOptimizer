package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.List;
import java.util.UUID;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.assertEntityOutcomeMatches;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.zeroVelocities;

final class CollisionTransitionParity {
    private CollisionTransitionParity() {
    }

    static void verify(GameTestHelper helper) {
        verifyPushabilityTransition(
                helper,
                "alive -> dead",
                0,
                TargetMutation.NONE,
                (level, target) -> target.setHealth(0.0F)
        );
        verifyPushabilityTransition(
                helper,
                "ground -> climbing",
                1,
                TargetMutation.NONE,
                (level, target) -> level.setBlockAndUpdate(
                        target.blockPosition(),
                        Blocks.SCAFFOLDING.defaultBlockState()
                )
        );
        verifyPushabilityTransition(
                helper,
                "climbing -> ground",
                2,
                (level, target) -> level.setBlockAndUpdate(
                        target.blockPosition(),
                        Blocks.SCAFFOLDING.defaultBlockState()
                ),
                (level, target) -> level.removeBlock(target.blockPosition(), false)
        );
        verifyTeamTransition(helper, false, 3);
        verifyTeamTransition(helper, true, 4);
        CollisionSpecialTransitionParity.verifyPlayerMode(
                helper,
                GameType.SURVIVAL,
                GameType.SPECTATOR,
                5
        );
        CollisionSpecialTransitionParity.verifyPlayerMode(
                helper,
                GameType.SPECTATOR,
                GameType.SURVIVAL,
                6
        );
        CollisionSpecialTransitionParity.verifyWardenPose(helper, false, 7);
        CollisionSpecialTransitionParity.verifyWardenPose(helper, true, 8);
        CollisionSpecialTransitionParity.verifyHorseVehicle(helper, false, 9);
        CollisionSpecialTransitionParity.verifyHorseVehicle(helper, true, 10);
    }

    private static void verifyPushabilityTransition(
            GameTestHelper helper,
            String scenario,
            int scenarioIndex,
            TargetMutation initialState,
            TargetMutation transition
    ) {
        ServerLevel level = helper.getLevel();
        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaTarget = spawnZombie(helper, vanillaAnchor.add(0.55, 0.0, 0.0));
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedTarget = spawnZombie(helper, acceleratedAnchor.add(0.55, 0.0, 0.0));
        try {
            initialState.apply(level, vanillaTarget);
            initialState.apply(level, acceleratedTarget);

            CollisionFrame.end(level);
            VanillaReference.pushEntities(vanillaSource);
            transition.apply(level, vanillaTarget);
            zeroVelocities(List.of(vanillaSource, vanillaTarget));
            VanillaReference.pushEntities(vanillaSource);

            CollisionFrame.begin(level);
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            transition.apply(level, acceleratedTarget);
            zeroVelocities(List.of(acceleratedSource, acceleratedTarget));
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, scenario + " source");
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, scenario + " target");
        } finally {
            level.removeBlock(vanillaTarget.blockPosition(), false);
            level.removeBlock(acceleratedTarget.blockPosition(), false);
            vanillaSource.discard();
            vanillaTarget.discard();
            acceleratedSource.discard();
            acceleratedTarget.discard();
            CollisionFrame.end(level);
        }
    }

    private static void verifyTeamTransition(
            GameTestHelper helper,
            boolean initiallyBlocked,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        Scoreboard scoreboard = level.getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 5);
        PlayerTeam vanillaAllowed = scoreboard.addPlayerTeam("tva" + suffix);
        PlayerTeam vanillaBlocked = scoreboard.addPlayerTeam("tvb" + suffix);
        PlayerTeam acceleratedAllowed = scoreboard.addPlayerTeam("taa" + suffix);
        PlayerTeam acceleratedBlocked = scoreboard.addPlayerTeam("tab" + suffix);
        vanillaBlocked.setCollisionRule(Team.CollisionRule.NEVER);
        acceleratedBlocked.setCollisionRule(Team.CollisionRule.NEVER);

        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie vanillaTarget = spawnZombie(helper, vanillaAnchor.add(0.55, 0.0, 0.0));
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Zombie acceleratedTarget = spawnZombie(helper, acceleratedAnchor.add(0.55, 0.0, 0.0));
        try {
            scoreboard.addPlayerToTeam(
                    vanillaTarget.getScoreboardName(),
                    initiallyBlocked ? vanillaBlocked : vanillaAllowed
            );
            scoreboard.addPlayerToTeam(
                    acceleratedTarget.getScoreboardName(),
                    initiallyBlocked ? acceleratedBlocked : acceleratedAllowed
            );

            CollisionFrame.end(level);
            VanillaReference.pushEntities(vanillaSource);
            scoreboard.addPlayerToTeam(
                    vanillaTarget.getScoreboardName(),
                    initiallyBlocked ? vanillaAllowed : vanillaBlocked
            );
            zeroVelocities(List.of(vanillaSource, vanillaTarget));
            VanillaReference.pushEntities(vanillaSource);

            CollisionFrame.begin(level);
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            scoreboard.addPlayerToTeam(
                    acceleratedTarget.getScoreboardName(),
                    initiallyBlocked ? acceleratedAllowed : acceleratedBlocked
            );
            zeroVelocities(List.of(acceleratedSource, acceleratedTarget));
            ((LivingEntityTestInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            String scenario = initiallyBlocked ? "team never -> always" : "team always -> never";
            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, scenario + " source");
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, scenario + " target");
        } finally {
            vanillaSource.discard();
            vanillaTarget.discard();
            acceleratedSource.discard();
            acceleratedTarget.discard();
            scoreboard.removePlayerTeam(vanillaAllowed);
            scoreboard.removePlayerTeam(vanillaBlocked);
            scoreboard.removePlayerTeam(acceleratedAllowed);
            scoreboard.removePlayerTeam(acceleratedBlocked);
            CollisionFrame.end(level);
        }
    }

    @FunctionalInterface
    private interface TargetMutation {
        TargetMutation NONE = (level, target) -> {
        };

        void apply(ServerLevel level, Zombie target);
    }
}

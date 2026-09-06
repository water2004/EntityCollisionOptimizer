package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.mixin.LivingEntityInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.assertEntityOutcomeMatches;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.zeroVelocities;

final class CollisionMediumDensityParity {
    private CollisionMediumDensityParity() {
    }

    static void verify(
            GameTestHelper helper,
            String scenario,
            int entityCount,
            boolean mixedRulesAndState,
            double baseZ
    ) {
        ServerLevel level = helper.getLevel();
        Scoreboard scoreboard = level.getScoreboard();
        String suffix = UUID.randomUUID().toString().substring(0, 5);
        PlayerTeam[] vanillaTeams = mixedRulesAndState
                ? createCollisionTeams(scoreboard, "mv" + suffix)
                : new PlayerTeam[0];
        PlayerTeam[] acceleratedTeams = mixedRulesAndState
                ? createCollisionTeams(scoreboard, "ma" + suffix)
                : new PlayerTeam[0];
        List<Zombie> vanilla = new ArrayList<>(entityCount);
        List<Zombie> accelerated = new ArrayList<>(entityCount);
        double vanillaBaseX = 2.5;
        double acceleratedBaseX = 14.5;

        if (mixedRulesAndState) {
            helper.setBlock(
                    new BlockPos((int) Math.floor(vanillaBaseX + 0.55), 1, (int) Math.floor(baseZ)),
                    Blocks.SCAFFOLDING
            );
            helper.setBlock(
                    new BlockPos((int) Math.floor(acceleratedBaseX + 0.55), 1, (int) Math.floor(baseZ)),
                    Blocks.SCAFFOLDING
            );
        }

        try {
            for (int index = 0; index < entityCount; index++) {
                Vec3 vanillaPosition = position(
                        vanillaBaseX,
                        baseZ,
                        index,
                        entityCount,
                        mixedRulesAndState
                );
                Vec3 acceleratedPosition = position(
                        acceleratedBaseX,
                        baseZ,
                        index,
                        entityCount,
                        mixedRulesAndState
                );
                Zombie vanillaEntity = spawnZombie(helper, vanillaPosition);
                Zombie acceleratedEntity = spawnZombie(helper, acceleratedPosition);
                vanilla.add(vanillaEntity);
                accelerated.add(acceleratedEntity);
                if (mixedRulesAndState) {
                    int teamIndex = index % vanillaTeams.length;
                    scoreboard.addPlayerToTeam(vanillaEntity.getScoreboardName(), vanillaTeams[teamIndex]);
                    scoreboard.addPlayerToTeam(acceleratedEntity.getScoreboardName(), acceleratedTeams[teamIndex]);
                }
            }

            if (mixedRulesAndState) {
                vanilla.get(entityCount - 2).setHealth(0.0F);
                accelerated.get(entityCount - 2).setHealth(0.0F);
                helper.assertTrue(
                        !vanilla.getLast().isPushable() && !accelerated.getLast().isPushable(),
                        "medium-density climbing targets must be unpushable"
                );
            }

            for (int index = 0; index < entityCount; index++) {
                long seed = 0xC0111DEL + index;
                vanilla.get(index).getRandom().setSeed(seed);
                accelerated.get(index).getRandom().setSeed(seed);
            }
            zeroVelocities(vanilla);
            zeroVelocities(accelerated);

            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            for (Zombie source : vanilla) {
                if (source.isAlive()) {
                    ((LivingEntityInvoker) source).entityCollisionOptimizer$invokePushEntities();
                }
            }

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            for (Zombie source : accelerated) {
                if (source.isAlive()) {
                    ((LivingEntityInvoker) source).entityCollisionOptimizer$invokePushEntities();
                }
            }

            for (int index = 0; index < entityCount; index++) {
                assertEntityOutcomeMatches(
                        helper,
                        vanilla.get(index),
                        accelerated.get(index),
                        scenario + " entity " + index
                );
            }
        } finally {
            for (Zombie entity : vanilla) {
                entity.discard();
            }
            for (Zombie entity : accelerated) {
                entity.discard();
            }
            for (PlayerTeam team : vanillaTeams) {
                scoreboard.removePlayerTeam(team);
            }
            for (PlayerTeam team : acceleratedTeams) {
                scoreboard.removePlayerTeam(team);
            }
            CollisionFrame.end(level);
        }
    }

    private static PlayerTeam[] createCollisionTeams(Scoreboard scoreboard, String prefix) {
        Team.CollisionRule[] rules = {
                Team.CollisionRule.ALWAYS,
                Team.CollisionRule.PUSH_OWN_TEAM,
                Team.CollisionRule.PUSH_OTHER_TEAMS,
                Team.CollisionRule.NEVER
        };
        PlayerTeam[] teams = new PlayerTeam[rules.length];
        for (int index = 0; index < rules.length; index++) {
            teams[index] = scoreboard.addPlayerTeam(prefix + index);
            teams[index].setCollisionRule(rules[index]);
        }
        return teams;
    }

    private static Vec3 position(
            double baseX,
            double baseZ,
            int index,
            int entityCount,
            boolean includeClimbingTarget
    ) {
        if (includeClimbingTarget && index == entityCount - 1) {
            return new Vec3(baseX + 0.55, 1.0, baseZ);
        }
        int column = index % 5;
        int row = index / 5;
        return new Vec3(
                baseX + (column - 2) * 0.08,
                1.0,
                baseZ + (row - 2) * 0.08
        );
    }
}

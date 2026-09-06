package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.mixin.LivingEntityInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.List;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.assertEntityOutcomeMatches;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.assertVectorEqual;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnEntity;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnMockServerPlayer;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.zeroVelocities;

final class CollisionSpecialTransitionParity {
    private CollisionSpecialTransitionParity() {
    }

    static void verifyPlayerMode(
            GameTestHelper helper,
            GameType initialMode,
            GameType transitionedMode,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        ServerPlayer player = spawnMockServerPlayer(
                helper,
                vanillaAnchor.add(0.55, 0.0, 0.0),
                initialMode
        );
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Zombie acceleratedSource = null;
        try {
            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            player.setGameMode(transitionedMode);
            helper.assertValueEqual(
                    player.gameMode(),
                    transitionedMode,
                    initialMode + " -> " + transitionedMode + " vanilla mode transition"
            );
            zeroVelocities(List.of(vanillaSource, player));
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            Vec3 vanillaSourceVelocity = vanillaSource.getDeltaMovement();
            Vec3 vanillaPlayerVelocity = player.getDeltaMovement();

            vanillaSource.discard();
            player.setGameMode(initialMode);
            player.setPos(helper.absoluteVec(acceleratedAnchor.add(0.55, 0.0, 0.0)));
            player.setDeltaMovement(Vec3.ZERO);
            acceleratedSource = spawnZombie(helper, acceleratedAnchor);
            acceleratedSource.setDeltaMovement(Vec3.ZERO);

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            player.setGameMode(transitionedMode);
            helper.assertValueEqual(
                    player.gameMode(),
                    transitionedMode,
                    initialMode + " -> " + transitionedMode + " accelerated mode transition"
            );
            zeroVelocities(List.of(acceleratedSource, player));
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            String scenario = initialMode + " -> " + transitionedMode;
            assertVectorEqual(
                    helper,
                    acceleratedSource.getDeltaMovement(),
                    vanillaSourceVelocity,
                    scenario + " source"
            );
            assertVectorEqual(
                    helper,
                    player.getDeltaMovement(),
                    vanillaPlayerVelocity,
                    scenario + " player target"
            );
            if (transitionedMode == GameType.SPECTATOR) {
                assertVectorEqual(helper, vanillaSourceVelocity, Vec3.ZERO, scenario + " exclusion");
                assertVectorEqual(helper, vanillaPlayerVelocity, Vec3.ZERO, scenario + " target exclusion");
            } else {
                helper.assertTrue(
                        vanillaPlayerVelocity.horizontalDistanceSqr() > 0.0,
                        scenario + " must restore player collision"
                );
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

    static void verifyWardenPose(
            GameTestHelper helper,
            boolean initiallyEmerging,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Warden vanillaTarget = (Warden) spawnEntity(
                helper,
                EntityTypes.WARDEN,
                vanillaAnchor.add(0.18, 0.0, 0.07)
        );
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Warden acceleratedTarget = (Warden) spawnEntity(
                helper,
                EntityTypes.WARDEN,
                acceleratedAnchor.add(0.18, 0.0, 0.07)
        );
        Pose initialPose = initiallyEmerging ? Pose.EMERGING : Pose.STANDING;
        Pose transitionedPose = initiallyEmerging ? Pose.STANDING : Pose.EMERGING;
        try {
            vanillaTarget.setPose(initialPose);
            acceleratedTarget.setPose(initialPose);

            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            vanillaTarget.setPose(transitionedPose);
            zeroVelocities(List.of(vanillaSource, vanillaTarget));
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            acceleratedTarget.setPose(transitionedPose);
            zeroVelocities(List.of(acceleratedSource, acceleratedTarget));
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            String scenario = initiallyEmerging
                    ? "warden emerging -> standing"
                    : "warden standing -> emerging";
            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, scenario + " source");
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, scenario + " target");
            if (transitionedPose == Pose.EMERGING) {
                assertVectorEqual(helper, vanillaTarget.getDeltaMovement(), Vec3.ZERO, scenario + " exclusion");
            } else {
                helper.assertTrue(
                        vanillaTarget.getDeltaMovement().horizontalDistanceSqr() > 0.0,
                        scenario + " must restore target collision"
                );
            }
        } finally {
            vanillaSource.discard();
            vanillaTarget.discard();
            acceleratedSource.discard();
            acceleratedTarget.discard();
            CollisionFrame.end(level);
        }
    }

    static void verifyHorseVehicle(
            GameTestHelper helper,
            boolean initiallyMounted,
            int scenarioIndex
    ) {
        ServerLevel level = helper.getLevel();
        double baseZ = 1.0 + scenarioIndex * 3.0;
        Vec3 vanillaAnchor = new Vec3(0.75, 1.0, baseZ);
        Vec3 acceleratedAnchor = new Vec3(8.75, 1.0, baseZ);
        Zombie vanillaSource = spawnZombie(helper, vanillaAnchor);
        Horse vanillaTarget = (Horse) spawnEntity(
                helper,
                EntityTypes.HORSE,
                vanillaAnchor.add(0.18, 0.0, 0.07)
        );
        ArmorStand vanillaPassenger = (ArmorStand) spawnEntity(
                helper,
                EntityTypes.ARMOR_STAND,
                vanillaAnchor.add(0.0, 4.0, 0.0)
        );
        Zombie acceleratedSource = spawnZombie(helper, acceleratedAnchor);
        Horse acceleratedTarget = (Horse) spawnEntity(
                helper,
                EntityTypes.HORSE,
                acceleratedAnchor.add(0.18, 0.0, 0.07)
        );
        ArmorStand acceleratedPassenger = (ArmorStand) spawnEntity(
                helper,
                EntityTypes.ARMOR_STAND,
                acceleratedAnchor.add(0.0, 4.0, 0.0)
        );
        try {
            if (initiallyMounted) {
                vanillaPassenger.startRiding(vanillaTarget, true, true);
                acceleratedPassenger.startRiding(acceleratedTarget, true, true);
            }

            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();
            transitionHorsePassenger(vanillaTarget, vanillaPassenger, initiallyMounted);
            zeroVelocities(List.of(vanillaSource, vanillaTarget, vanillaPassenger));
            ((LivingEntityInvoker) vanillaSource).entityCollisionOptimizer$invokePushEntities();

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();
            transitionHorsePassenger(acceleratedTarget, acceleratedPassenger, initiallyMounted);
            zeroVelocities(List.of(acceleratedSource, acceleratedTarget, acceleratedPassenger));
            ((LivingEntityInvoker) acceleratedSource).entityCollisionOptimizer$invokePushEntities();

            String scenario = initiallyMounted ? "horse mounted -> unmounted" : "horse unmounted -> mounted";
            assertEntityOutcomeMatches(helper, vanillaSource, acceleratedSource, scenario + " source");
            assertEntityOutcomeMatches(helper, vanillaTarget, acceleratedTarget, scenario + " target");
            helper.assertValueEqual(
                    acceleratedTarget.isVehicle(),
                    vanillaTarget.isVehicle(),
                    scenario + " vehicle state"
            );
            if (acceleratedTarget.isVehicle()) {
                assertVectorEqual(helper, vanillaTarget.getDeltaMovement(), Vec3.ZERO, scenario + " exclusion");
            } else {
                helper.assertTrue(
                        vanillaTarget.getDeltaMovement().horizontalDistanceSqr() > 0.0,
                        scenario + " must restore target collision"
                );
            }
        } finally {
            vanillaSource.discard();
            vanillaTarget.discard();
            vanillaPassenger.discard();
            acceleratedSource.discard();
            acceleratedTarget.discard();
            acceleratedPassenger.discard();
            CollisionFrame.end(level);
        }
    }

    private static void transitionHorsePassenger(
            Horse horse,
            ArmorStand passenger,
            boolean initiallyMounted
    ) {
        if (initiallyMounted) {
            passenger.stopRiding();
            passenger.setPos(horse.position().add(0.0, 4.0, 0.0));
        } else {
            passenger.startRiding(horse, true, true);
        }
    }
}

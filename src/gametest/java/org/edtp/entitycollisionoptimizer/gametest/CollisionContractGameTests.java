package org.edtp.entitycollisionoptimizer.gametest;

import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.ZombieTestInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;

/** Deterministic component and contract checks that may inspect optimizer internals. */
public final class CollisionContractGameTests {
    @GameTest(maxTicks = 20)
    public void piglinConversionLifecycle(GameTestHelper helper) {
        Piglin piglin = helper.spawn(EntityTypes.PIGLIN, new Vec3(1.5, 2.0, 1.5));
        piglin.setTimeInOverworld(300);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(piglin.isRemoved(), "piglin must complete its overworld conversion");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 20)
    public void zombieDrownedConversionLifecycle(GameTestHelper helper) {
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new Vec3(1.5, 2.0, 1.5));
        ((ZombieTestInvoker) zombie).entityCollisionOptimizer$startUnderWaterConversion(0);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(zombie.isRemoved(), "zombie must complete its drowned conversion");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 20)
    public void hoglinConversionLifecycle(GameTestHelper helper) {
        Hoglin hoglin = helper.spawn(EntityTypes.HOGLIN, new Vec3(1.5, 2.0, 1.5));
        hoglin.setTimeInOverworld(300);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(hoglin.isRemoved(), "hoglin must complete its overworld conversion");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void emptyWorldSpawnQuery(GameTestHelper helper) {
        org.edtp.entitycollisionoptimizer.natives.NativeHardQueryChecks.emptyWorld(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void nativeMovementContract(GameTestHelper helper) {
        NativeVoxelParity.edges(helper);
        SingleCellParity.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.MovementPublicationChecks.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.MovementBoundsChecks.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.NativeRowsChecks.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.MovementLeaseChecks.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void positionWriteParity(GameTestHelper helper) {
        PositionWriteParity.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.PositionMirrorChecks.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void nativeQueryContract(GameTestHelper helper) {
        org.edtp.entitycollisionoptimizer.natives.NativeQueryChecks.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.VerticalIndexChecks.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.NativeHardQueryChecks.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void orderedNativeIndexParity(GameTestHelper helper) {
        OrderedCandidateParity.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.NativeOrderChecks.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void sharedBodyStateParity(GameTestHelper helper) {
        BodyFieldConsumerCoverage.verify();
        SyncStateParity.verify(helper);
        PushStateParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void nativePushRunParity(GameTestHelper helper) {
        NativePushRunParity.verify(helper);
        PushRunBoundaryParity.verify(helper);
        PersistentBodyParity.verify(helper);
        AuthoritativeVelocityParity.verify(helper);
        org.edtp.entitycollisionoptimizer.natives.CollisionStateTableChecks.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void playerInteractions(GameTestHelper helper) {
        PlayerInteractionParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void mixedEntityInteractions(GameTestHelper helper) {
        MixedEntityInteractionParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void thrownProjectiles(GameTestHelper helper) {
        ProjectileInteractionParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void explosionInteractions(GameTestHelper helper) {
        ExplosionInteractionParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void tntCannonInteractions(GameTestHelper helper) {
        TntCannonParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void machineClearances(GameTestHelper helper) {
        MachineClearanceParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void fluidInteractions(GameTestHelper helper) {
        FluidInteractionParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void irregularInteractions(GameTestHelper helper) {
        IrregularMovementParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void surfaceInteractions(GameTestHelper helper) {
        SurfaceInteractionParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void pistonInteractions(GameTestHelper helper) {
        PistonInteractionParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void liveTeamContract(GameTestHelper helper) {
        CollisionContractParity.teams(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void orderedEntityContract(GameTestHelper helper) {
        CollisionContractParity.order(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void entitySectionQueryContract(GameTestHelper helper) {
        EntityQueryParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void canonicalVelocityContract(GameTestHelper helper) {
        CollisionContractParity.visibility(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void orderedBlockShapes(GameTestHelper helper) {
        BlockShapeParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void blockMovementParity(GameTestHelper helper) {
        BlockMovementParity.verify(helper);
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void impulseObservationParity(GameTestHelper helper) {
        CollisionImpulseParity.verify(helper);
        NativeImpulseParity.verify(helper);
        PushBatchParity.verify(helper);
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_PARITY_RESULT velocity_observations=5 kernel_bitwise_pairs=225 arithmetic_sequences=4 nested_batches=2 sleep_phases=3 result=passed"
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void lowDensityVanillaParity(GameTestHelper helper) {
        CollisionParity.verifyLowDensity(helper);
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_PARITY_RESULT density=low dispatch_pairs=34 state_transitions=11 repeated_frames=4 result=passed"
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void mediumDensityVanillaParity(GameTestHelper helper) {
        CollisionParity.verifyMediumDensity(helper);
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_PARITY_RESULT density=medium groups=2 entity_counts=20,24 result=passed"
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void concurrentLevelIsolation(GameTestHelper helper) {
        CollisionParity.verifyConcurrentLevelIsolation(helper);
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_PARITY_RESULT dimensions=3 concurrent_queries=13500 native_batches=4500 result=passed"
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 800, padding = 48, environment = "entity_collision_optimizer:chunk_load")
    public void chunkLoadBoundaries(GameTestHelper helper) {
        ChunkLoadParity.verify(helper);
    }
}

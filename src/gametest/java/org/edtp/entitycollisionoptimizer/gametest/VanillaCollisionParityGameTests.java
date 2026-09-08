package org.edtp.entitycollisionoptimizer.gametest;

import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class VanillaCollisionParityGameTests {
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
                "ECO_PARITY_RESULT density=low dispatch_pairs=34 state_transitions=12 repeated_frames=4 result=passed"
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

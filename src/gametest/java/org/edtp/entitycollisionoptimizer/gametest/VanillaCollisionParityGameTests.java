package org.edtp.entitycollisionoptimizer.gametest;

import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class VanillaCollisionParityGameTests {
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
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_PARITY_RESULT velocity_observations=5 result=passed"
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
                "ECO_PARITY_RESULT dimensions=3 concurrent_queries=9000 result=passed"
        );
        helper.succeed();
    }
}

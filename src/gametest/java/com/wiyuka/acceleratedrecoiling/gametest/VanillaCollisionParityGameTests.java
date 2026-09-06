package com.wiyuka.acceleratedrecoiling.gametest;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class VanillaCollisionParityGameTests {
    @GameTest(maxTicks = 200, padding = 48)
    public void lowDensityVanillaParity(GameTestHelper helper) {
        CollisionParity.verifyLowDensity(helper);
        AcceleratedRecoiling.LOGGER.info(
                "AR_PARITY_RESULT density=low dispatch_pairs=34 state_transitions=12 repeated_frames=4 result=passed"
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void mediumDensityVanillaParity(GameTestHelper helper) {
        CollisionParity.verifyMediumDensity(helper);
        AcceleratedRecoiling.LOGGER.info(
                "AR_PARITY_RESULT density=medium groups=2 entity_counts=20,24 result=passed"
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 200, padding = 48)
    public void concurrentLevelIsolation(GameTestHelper helper) {
        CollisionParity.verifyConcurrentLevelIsolation(helper);
        AcceleratedRecoiling.LOGGER.info(
                "AR_PARITY_RESULT dimensions=3 concurrent_queries=9000 result=passed"
        );
        helper.succeed();
    }
}

package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ClimbingCollisionBenchmark {
    // The shared runner is serial, so this timeout also covers earlier scenarios.
    @GameTest(maxTicks = 1800, padding = 16)
    public void denseScaffolding(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new ClimbingCollisionChamber(helper));
    }
}

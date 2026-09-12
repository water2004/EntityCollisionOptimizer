package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ClimbingCollisionBenchmark {
    @GameTest(maxTicks = 600, padding = 16)
    public void denseScaffolding(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new ClimbingCollisionChamber(helper));
    }
}

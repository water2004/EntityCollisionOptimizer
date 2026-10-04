package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class MinecartCollisionBenchmark {
    @GameTest(maxTicks = CollisionBenchmarkRunner.SUITE_MAX_TICKS)
    public void slidingMinecarts(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new MinecartBenchmarkChamber(helper));
    }
}

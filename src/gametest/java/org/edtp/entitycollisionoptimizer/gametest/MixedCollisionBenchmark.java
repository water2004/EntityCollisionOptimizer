package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class MixedCollisionBenchmark {
    @GameTest(maxTicks = 6000, padding = 16)
    public void denseHardAndSoftEntities(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new MixedCollisionChamber(helper));
    }
}

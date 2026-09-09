package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ElderGuardianBenchmark {
    @GameTest(maxTicks = 500, padding = 96)
    public void voidPipe(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new ElderGuardianPipe(helper));
    }
}

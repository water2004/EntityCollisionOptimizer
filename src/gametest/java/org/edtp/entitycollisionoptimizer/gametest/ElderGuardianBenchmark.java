package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ElderGuardianBenchmark {
    // The benchmark intentionally runs a 100-tick post-window drain.  Leave
    // headroom for heavily loaded profiling hosts that fall behind wall time.
    @GameTest(maxTicks = 1000, padding = 96)
    public void voidPipe(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new ElderGuardianPipe(helper));
    }
}

package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ElderGuardianBenchmark implements FabricGameTest {
    // The benchmark intentionally runs a 100-tick post-window drain.  Leave
    // headroom for heavily loaded profiling hosts that fall behind wall time.
    @GameTest(template = "entity_collision_optimizer:empty_128", timeoutTicks = 1600)
    public void voidPipe(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new ElderGuardianPipe(helper));
    }
}

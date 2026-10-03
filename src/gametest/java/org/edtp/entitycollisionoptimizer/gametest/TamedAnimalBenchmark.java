package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class TamedAnimalBenchmark {
    @GameTest(maxTicks = CollisionBenchmarkRunner.SUITE_MAX_TICKS, padding = 64)
    public void separatedPens(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new TamedAnimalPens(helper));
    }
}

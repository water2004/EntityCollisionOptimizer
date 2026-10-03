package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ZombieCollisionBenchmark {
    static final int DURATION_TICKS = CollisionBenchmarkRunner.DURATION_TICKS;
    @GameTest(maxTicks = CollisionBenchmarkRunner.SUITE_MAX_TICKS, padding = 96)
    public void fallingZombies(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new ZombieBenchmarkChamber(helper));
    }
}

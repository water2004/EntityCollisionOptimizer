package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ZombieCollisionBenchmark implements FabricGameTest {
    static final int DURATION_TICKS = CollisionBenchmarkRunner.DURATION_TICKS;
    @GameTest(template = "entity_collision_optimizer:empty_128", timeoutTicks = 500)
    public void fallingZombies(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new ZombieBenchmarkChamber(helper));
    }
}

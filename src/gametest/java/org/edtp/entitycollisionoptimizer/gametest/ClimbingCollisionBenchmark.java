package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ClimbingCollisionBenchmark implements FabricGameTest {
    // The shared runner is serial, so this timeout also covers earlier scenarios.
    @GameTest(template = "entity_collision_optimizer:empty_128", timeoutTicks = 1800)
    public void denseScaffolding(GameTestHelper helper) {
        CollisionBenchmarkRunner.run(helper, new ClimbingCollisionChamber(helper));
    }
}

package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;

import java.util.ArrayList;
import java.util.List;

final class BlockMovementTrace {
    static void verify(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.STONE_SLAB);
        List<State> expected = run(helper, false);
        List<State> actual = run(helper, true);
        for (int i = 0; i < expected.size(); i++) {
            State a = actual.get(i), e = expected.get(i);
            CollisionTestSupport.assertVectorEqual(helper, a.position, e.position, "trace position " + i);
            CollisionTestSupport.assertVectorEqual(helper, a.velocity, e.velocity, "trace velocity " + i);
            helper.assertValueEqual(a.flags, e.flags, "trace contact flags " + i);
            helper.assertTrue(Math.abs(a.fallDistance - e.fallDistance) <= 1.0E-12, "trace fall distance " + i);
        }
    }

    private static List<State> run(GameTestHelper helper, boolean enabled) {
        CollisionOptimizerConfig.enableEntityCollision = enabled;
        var entity = CollisionTestSupport.spawnZombie(helper, new Vec3(3.5, 1, 3.5));
        List<State> result = new ArrayList<>();
        try {
            entity.setOnGround(true);
            for (int tick = 0; tick < 32; tick++) {
                Vec3 movement = new Vec3(tick < 16 ? 0.08 : -0.08, -0.12, (tick & 1) == 0 ? 0.02 : -0.02);
                entity.setDeltaMovement(movement);
                entity.move(MoverType.SELF, movement);
                int flags = (entity.onGround() ? 1 : 0) | (entity.horizontalCollision ? 2 : 0)
                        | (entity.verticalCollision ? 4 : 0) | (entity.verticalCollisionBelow ? 8 : 0)
                        | (entity.minorHorizontalCollision ? 16 : 0);
                result.add(new State(entity.position(), entity.getDeltaMovement(), flags, entity.fallDistance));
            }
        } finally {
            entity.discard();
        }
        return result;
    }

    private record State(Vec3 position, Vec3 velocity, int flags, double fallDistance) {}
}

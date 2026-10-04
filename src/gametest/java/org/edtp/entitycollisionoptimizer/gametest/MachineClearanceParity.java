package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.List;

/** Machine-sized clearances have independent pass/block/route assertions in addition to parity. */
final class MachineClearanceParity {
    static void verify(GameTestHelper helper) {
        int cases = 0;
        var topSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        for (var type : List.of(EntityType.ITEM, EntityType.ZOMBIE, EntityType.PLAYER)) {
            var expected = trace(helper, false, type, topSlab, new Vec3(4, 1.01, 4.5), new Vec3(0.12, 0, 0), 0);
            compare(helper, trace(helper, true, type, topSlab, new Vec3(4, 1.01, 4.5), new Vec3(0.12, 0, 0), 0), expected, "half-block filter " + type);
            double exit = helper.absoluteVec(new Vec3(6, 0, 0)).x;
            helper.assertValueEqual(expected.getLast().position().x > exit, type == EntityType.ITEM,
                    "half-block tunnel passes items but blocks adult mobs/players");
            cases++;
        }
        // Notch-side lane in each east/west staircase: the 0.5-wide upper recess fits a 0.25-wide item.
        for (Direction facing : List.of(Direction.EAST, Direction.WEST)) {
            var stair = Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            int itemPasses = 0;
            for (double lane : new double[]{5.25, 5.75}) {
                for (var type : List.of(EntityType.ITEM, EntityType.ZOMBIE)) {
                    var start = new Vec3(lane, 1.501, 3.2);
                    var motion = new Vec3(0, 0, 0.12);
                    var expected = trace(helper, false, type, stair, start, motion, 0);
                    compare(helper, trace(helper, true, type, stair, start, motion, 0), expected, "stair filter " + facing + " " + lane + " " + type);
                    boolean passed = expected.getLast().position().z > helper.absoluteVec(new Vec3(0, 0, 5.5)).z;
                    if (type == EntityType.ITEM && passed) itemPasses++;
                    if (type == EntityType.ZOMBIE) helper.assertTrue(!passed, "adult must not fit staircase notch");
                    cases++;
                }
            }
            helper.assertValueEqual(itemPasses, 1, "exactly the staircase notch lane must pass items");
        }
        for (double epsilon : new double[]{-1E-6, -1E-7, 0, 1E-7, 1E-6}) {
            var start = new Vec3(4, 1.5 + epsilon, 4.5);
            var expected = trace(helper, false, EntityType.ITEM, topSlab, start, new Vec3(0.12, 0, 0), 0);
            compare(helper, trace(helper, true, EntityType.ITEM, topSlab, start, new Vec3(0.12, 0, 0), 0), expected, "slot edge epsilon=" + epsilon);
            cases++;
        }
        for (var type : List.of(EntityType.ITEM, EntityType.ZOMBIE, EntityType.PLAYER)) {
            var start = new Vec3(4, 1.1, 4.5);
            var stone = trace(helper, false, type, Blocks.STONE.defaultBlockState(), start, new Vec3(0.12, 0, 0), 0);
            var honey = trace(helper, false, type, Blocks.HONEY_BLOCK.defaultBlockState(), start, new Vec3(0.12, 0, 0), 0);
            compare(helper, trace(helper, true, type, Blocks.HONEY_BLOCK.defaultBlockState(), start, new Vec3(0.12, 0, 0), 0), honey, "honey inset " + type);
            helper.assertTrue(Math.abs(honey.getLast().position().x - stone.getLast().position().x - 0.0625) < 1E-8,
                    "honey side must expose its real 1/16-block inset " + type);
            cases++;
        }
        for (int branch : new int[]{-1, 1}) {
            var start = new Vec3(4, 1.01, 4.5 + branch * 0.1);
            var motion = new Vec3(0.16, 0, branch * 0.08);
            var expected = trace(helper, false, EntityType.ITEM, Blocks.STONE.defaultBlockState(), start, motion, 0);
            compare(helper, trace(helper, true, EntityType.ITEM, Blocks.STONE.defaultBlockState(), start, motion, 0), expected, "splitter branch " + branch);
            var end = expected.getLast().position();
            helper.assertTrue(end.x > helper.absoluteVec(new Vec3(6, 0, 0)).x
                    && Math.signum(end.z - helper.absoluteVec(new Vec3(0, 0, 4.5)).z) == branch,
                    "item must slide around splitter and leave through designated branch");
            cases++;
        }
        for (var type : List.of(EntityType.ZOMBIE, EntityType.PLAYER)) {
            var start = new Vec3(4.7, 3.4, 4.5);
            var expected = trace(helper, false, type, Blocks.HONEY_BLOCK.defaultBlockState(), start, new Vec3(0.12, -0.3, 0), 1);
            compare(helper, trace(helper, true, type, Blocks.HONEY_BLOCK.defaultBlockState(), start, new Vec3(0.12, -0.3, 0), 1), expected, "honey slide " + type);
            helper.assertTrue(expected.stream().anyMatch(s -> s.velocity().y < 0 && s.velocity().y > -0.2),
                    "honey side must actually slow falling " + type);
            cases++;
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_MACHINE_CLEARANCES cases={} result=passed", cases);
    }

    private static List<InteractionScene.State> trace(GameTestHelper helper, boolean enabled, EntityType<?> type,
                                                       BlockState obstacle, Vec3 start, Vec3 motion, int wall) {
        try (var scene = new InteractionScene(helper)) {
            scene.floor(Blocks.STONE);
            scene.block(5, 1, 4, obstacle);
            if (wall != 0) for (int y = 2; y <= 4; y++) scene.block(5, y, 4, obstacle);
            var entity = scene.spawn(type, start);
            entity.setOnGround(false); // Test exact gaps; automatic step-up would route adults over the fixture.
            List<InteractionScene.State> states = new ArrayList<>();
            for (int step = 0; step < 28; step++) {
                Vec3 before = entity.position();
                entity.setDeltaMovement(motion);
                entity.move(MoverType.SELF, motion);
                entity.applyEffectsFromBlocks(before, entity.position());
                states.add(InteractionScene.State.of(entity));
            }
            return states;
        }
    }

    private static void compare(GameTestHelper helper, List<InteractionScene.State> actual,
                                List<InteractionScene.State> expected, String label) {
        for (int step = 0; step < expected.size(); step++) actual.get(step).compare(helper, expected.get(step), label + " step=" + step);
    }
}

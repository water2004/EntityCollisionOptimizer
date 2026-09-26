package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.List;

/** Tick real moving block entities so piston displacement, slime impulse and honey carrying execute. */
final class PistonInteractionParity {
    static void verify(GameTestHelper helper) {
        int cases = 0, displaced = 0;
        for (Direction direction : Direction.values()) {
            for (boolean extending : new boolean[]{true, false}) {
                for (Block block : List.of(Blocks.STONE, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK)) {
                    for (var type : List.of(EntityType.ZOMBIE, EntityType.ITEM, EntityType.PLAYER,
                            EntityType.BOAT, EntityType.MAGMA_CUBE, EntityType.TNT)) {
                        var expected = run(helper, false, direction, extending, block, type, false);
                        var actual = run(helper, true, direction, extending, block, type, false);
                        actual.after.compare(helper, expected.after, "piston " + direction + " " + extending + " " + block + " " + type);
                        if (!expected.before.equals(expected.after.position())) displaced++;
                        cases++;
                    }
                }
            }
        }
        for (var type : List.of(EntityType.ZOMBIE, EntityType.ITEM, EntityType.PLAYER)) {
            var expected = run(helper, false, Direction.EAST, true, Blocks.HONEY_BLOCK, type, true);
            var actual = run(helper, true, Direction.EAST, true, Blocks.HONEY_BLOCK, type, true);
            actual.after.compare(helper, expected.after, "honey passenger " + type);
            helper.assertTrue(expected.after.position().x > expected.before.x, "honey must really carry " + type);
            cases++;
        }
        helper.assertTrue(displaced > 0, "piston tests must actually displace entities");
        EntityCollisionOptimizer.LOGGER.info("ECO_PISTON_INTERACTIONS cases={} displaced_cases={} result=passed", cases, displaced);
    }

    private static Outcome run(GameTestHelper helper, boolean enabled, Direction facing, boolean extending,
                               Block block, EntityType<?> type, boolean ridingHoney) {
        try (var scene = new InteractionScene(helper)) {
            var moving = Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
            scene.block(7, 4, 4, moving);
            BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 4));
            var piston = new PistonMovingBlockEntity(pos, moving, block.defaultBlockState(), facing, extending, false);
            helper.getLevel().setBlockEntity(piston);
            var entity = scene.spawn(type, new Vec3(7.5, 4, 4.5));
            Vec3 direction = Vec3.atLowerCornerOf(facing.getNormal());
            Vec3 center = Vec3.atCenterOf(pos).add(direction.scale(extending ? -0.1 : 0.1));
            entity.setPos(center.x, center.y - entity.getBbHeight() / 2.0, center.z);
            if (ridingHoney) {
                entity.setPos(pos.getX() - 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                entity.setOnGround(true);
            }
            entity.setDeltaMovement(Vec3.ZERO);
            Vec3 before = entity.position();
            CollisionFrame.begin(helper.getLevel());
            PistonMovingBlockEntity.tick(helper.getLevel(), pos, moving, piston);
            return new Outcome(before, InteractionScene.State.of(entity));
        }
    }

    private record Outcome(Vec3 before, InteractionScene.State after) {}
}

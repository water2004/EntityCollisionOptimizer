package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.gametest.mixin.PistonProgressAccessor;

import java.util.List;

final class MovingPistonParity {
    static int verify(GameTestHelper helper, Entity entity) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 1, 3));
        var original = level.getBlockState(pos);
        var originalBlockEntity = level.getBlockEntity(pos);
        int cases = 0;
        try {
            for (Direction direction : Direction.values()) {
                var moving = Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, direction);
                for (boolean extending : new boolean[] {false, true}) {
                    for (Block block : List.of(Blocks.STONE, Blocks.STONE_SLAB, Blocks.HONEY_BLOCK)) {
                        level.setBlock(pos, moving, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS);
                        var piston = new PistonMovingBlockEntity(pos, moving, block.defaultBlockState(), direction, extending, false);
                        level.setBlockEntity(piston);
                        for (float progress : new float[] {0.0F, 0.25F, 0.5F, 0.75F, 1.0F}) {
                            ((PistonProgressAccessor) piston).eco$progress(progress);
                            ((PistonProgressAccessor) piston).eco$previousProgress(progress);
                            BlockShapeParity.compare(helper, entity, new AABB(pos).inflate(1.2), "moving piston " + cases++);
                            // Put the block outside the query proper: vanilla's halo must still see its moving shape.
                            BlockShapeParity.compare(helper, entity, new AABB(pos).move(net.minecraft.world.phys.Vec3.atLowerCornerOf(direction.getNormal())).deflate(0.05),
                                    "moving piston halo " + cases++);
                        }
                        level.removeBlockEntity(pos);
                    }
                }
            }
        } finally {
            level.removeBlockEntity(pos);
            level.setBlock(pos, original, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS);
            if (originalBlockEntity != null) level.setBlockEntity(originalBlockEntity);
        }
        return cases;
    }
}

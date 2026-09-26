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
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.PistonProgressAccessor;

import java.util.List;

/** Actual ordered vanilla collider comparisons on every side of X/Y/Z section boundaries. */
final class BlockHaloParity {
    static void verify(GameTestHelper helper, Entity entity) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 4, 4));
        // Use the next Y boundary so both adjoining sections lie above the world's minimum height.
        BlockPos boundary = new BlockPos((origin.getX() >> 4) << 4, ((origin.getY() >> 4) + 1) << 4,
                (origin.getZ() >> 4) << 4);
        int cases = 0;
        for (int x = -1; x <= 0; x++) for (int y = -1; y <= 0; y++) for (int z = -1; z <= 0; z++) {
            BlockPos pos = boundary.offset(x, y, z);
            var original = level.getBlockState(pos);
            var originalBlockEntity = level.getBlockEntity(pos);
            try {
                for (Block block : List.of(Blocks.STONE, Blocks.OAK_FENCE, Blocks.MOVING_PISTON,
                        Blocks.HONEY_BLOCK, Blocks.AIR)) {
                    level.removeBlockEntity(pos);
                    var state = block.defaultBlockState();
                    if (block == Blocks.MOVING_PISTON) state = state.setValue(BlockStateProperties.FACING, Direction.WEST);
                    level.setBlock(pos, state, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS);
                    PistonMovingBlockEntity piston = null;
                    if (block == Blocks.MOVING_PISTON) {
                        piston = new PistonMovingBlockEntity(pos, state, Blocks.STONE.defaultBlockState(), Direction.WEST, true, false);
                        level.setBlockEntity(piston);
                    }
                    for (int phase = 0; phase < (piston == null ? 1 : 3); phase++) {
                        if (piston != null) {
                            ((PistonProgressAccessor) piston).eco$progress(phase * 0.5F);
                            ((PistonProgressAccessor) piston).eco$previousProgress(phase * 0.5F);
                        }
                        // Queries place this block in the interior, faces, edges and corners of the halo.
                        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                            AABB box = new AABB(pos).move(dx, dy, dz).deflate(0.05);
                            BlockShapeParity.compare(helper, entity, box, "section halo " + cases++);
                        }
                    }
                }
            } finally {
                level.removeBlockEntity(pos);
                level.setBlock(pos, original, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS);
                if (originalBlockEntity != null) level.setBlockEntity(originalBlockEntity);
            }
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_BLOCK_HALO_PARITY ordered_queries={} section_sides=8 "
                + "query_offsets=27 piston_progress_phases=3 result=passed", cases);
    }
}

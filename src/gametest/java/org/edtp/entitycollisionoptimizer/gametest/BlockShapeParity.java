package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.collision.blocks.OrderedBlockColliders;

import java.util.ArrayList;
import java.util.List;

final class BlockShapeParity {
    static void verify(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
        var level = helper.getLevel();
        var chunk = level.getChunkAt(pos);
        var section = chunk.getSections()[chunk.getSectionIndex(pos.getY())];
        BlockState original = level.getBlockState(pos);
        var originalBlockEntity = level.getBlockEntity(pos);
        Entity entity = CollisionTestSupport.spawnZombie(helper, new Vec3(3.5, 3, 3.5));
        int count = 0;
        try {
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                level.removeBlockEntity(pos);
                // Test stored states directly, including states that ordinary placement would reject.
                section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
                BlockMaskParity.checkRow(helper, section.getStates(), pos.getY() & 15, pos.getZ() & 15);
                compare(helper, entity, new AABB(pos).inflate(0.2), state.toString());
                count++;
            }
            BlockMaskParity.verify(helper, section.getStates());
            BlockHaloParity.verify(helper, entity);
        } finally {
            level.removeBlockEntity(pos);
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, original);
            if (originalBlockEntity != null) level.setBlockEntity(originalBlockEntity);
            section.recalcBlockCounts();
            entity.discard();
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_BLOCK_SHAPE_PARITY states={} ordered_shapes=exact result=passed", count);
    }

    static void compare(GameTestHelper helper, Entity entity, AABB box, String label) {
        CollisionContext context = CollisionContext.of(entity);
        List<VoxelShape> expected = new ArrayList<>();
        var cursor = new BlockCollisions<VoxelShape>(entity.level(), context, box, false, (pos, shape) -> shape);
        cursor.forEachRemaining(expected::add);
        List<VoxelShape> actual = new ArrayList<>();
        OrderedBlockColliders.append(entity.level(), context, box, actual);
        helper.assertValueEqual(actual.size(), expected.size(), label + " collider count");
        for (int index = 0; index < expected.size(); index++) {
            helper.assertTrue(actual.get(index).toAabbs().equals(expected.get(index).toAabbs()),
                    label + " ordered collider " + index);
        }
    }
}

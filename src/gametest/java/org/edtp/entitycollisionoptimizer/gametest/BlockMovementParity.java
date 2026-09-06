package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.mixin.EntityCollisionInvoker;

import java.util.ArrayList;
import java.util.List;

final class BlockMovementParity {
    private static final Vec3[] MOVEMENTS = {
            Vec3.ZERO, new Vec3(0, -0.3, 0), new Vec3(0, 1, 0),
            new Vec3(1, -0.3, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1),
            new Vec3(0.8, -0.3, 0.5), new Vec3(0.5, -0.3, 0.8),
            new Vec3(-0.8, -0.3, -0.5), new Vec3(0.8, 0.5, 0.8),
            new Vec3(1.0E-8, -1.0E-8, -1.0E-8), new Vec3(1.0E-7, 0, -1.0E-7),
            new Vec3(20, -0.3, 25)
    };

    static void verify(GameTestHelper helper) {
        boolean original = CollisionOptimizerConfig.enableEntityCollision;
        Entity entity = CollisionTestSupport.spawnZombie(helper, new Vec3(3.5, 1, 3.5));
        int comparisons = 0;
        int pistonCases = 0;
        try {
            buildFloor(helper);
            List<BlockState> obstacles = List.of(
                    Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                    Blocks.STONE_SLAB.defaultBlockState(),
                    Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
                    Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST),
                    Blocks.OAK_FENCE.defaultBlockState(), Blocks.COBBLESTONE_WALL.defaultBlockState(),
                    Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 4),
                    Blocks.MOSS_CARPET.defaultBlockState(), Blocks.SCAFFOLDING.defaultBlockState(),
                    Blocks.HONEY_BLOCK.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(),
                    Blocks.POWDER_SNOW.defaultBlockState(), Blocks.OAK_TRAPDOOR.defaultBlockState(),
                    Blocks.SOUL_SAND.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState());
            for (BlockState obstacle : obstacles) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(4, 1, 3)), obstacle, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                for (boolean ceiling : new boolean[] {false, true}) {
                    helper.setBlock(new BlockPos(3, 3, 3), ceiling ? Blocks.STONE : Blocks.AIR);
                    for (boolean grounded : new boolean[] {false, true}) {
                        entity.setOnGround(grounded);
                        for (Vec3 movement : MOVEMENTS) {
                            comparisons += compare(helper, entity, movement, obstacle + " ceiling=" + ceiling + " grounded=" + grounded);
                        }
                    }
                }
            }
            helper.setBlock(new BlockPos(3, 3, 3), Blocks.AIR);
            comparisons += contexts(helper);
            comparisons += medium(helper);
            comparisons += border(helper, entity);
            comparisons += BlockContextParity.verify(helper, entity);
            pistonCases = MovingPistonParity.verify(helper, entity);
            BlockMovementTrace.verify(helper);
            // A scan spanning negative coordinates and section boundaries, with a one-block halo.
            BlockShapeParity.compare(helper, entity, entity.getBoundingBox().inflate(17.1), "section boundaries");
        } finally {
            CollisionOptimizerConfig.enableEntityCollision = original;
            entity.discard();
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_BLOCK_MOVEMENT_PARITY movement_comparisons={} moving_piston_shape_queries={} "
                + "medium_entities=24 trace_steps=32 result=passed", comparisons, pistonCases);
    }

    private static int compare(GameTestHelper helper, Entity entity, Vec3 requested, String label) {
        CollisionOptimizerConfig.enableEntityCollision = false;
        Vec3 expected = ((EntityCollisionInvoker) entity).eco$collide(requested);
        CollisionOptimizerConfig.enableEntityCollision = true;
        MovementTakeoverCoverage.begin();
        Vec3 actual;
        try {
            actual = ((EntityCollisionInvoker) entity).eco$collide(requested);
        } finally {
            MovementTakeoverCoverage.end(helper);
        }
        CollisionTestSupport.assertVectorEqual(helper, actual, expected, label + " requested=" + requested);
        return 1;
    }

    private static void buildFloor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
    }

    private static int contexts(GameTestHelper helper) {
        var player = CollisionTestSupport.spawnPlayer(helper, new Vec3(3.5, 1, 3.5));
        Entity strider = CollisionTestSupport.spawnEntity(helper, EntityTypes.STRIDER, new Vec3(3.5, 1, 3.5));
        Entity boat = CollisionTestSupport.spawnEntity(helper, EntityTypes.OAK_BOAT, new Vec3(4.5, 1, 4.5));
        int count = 0;
        try {
            for (Block block : List.of(Blocks.POWDER_SNOW, Blocks.SCAFFOLDING, Blocks.LAVA, Blocks.WATER)) {
                helper.setBlock(new BlockPos(4, 1, 3), block);
                for (boolean equipped : new boolean[] {false, true}) {
                    player.setItemSlot(EquipmentSlot.FEET, equipped ? new ItemStack(Items.LEATHER_BOOTS) : ItemStack.EMPTY);
                    player.setShiftKeyDown(equipped);
                    for (Entity source : List.of(player, strider, boat)) {
                        source.setOnGround(true);
                        for (Vec3 movement : MOVEMENTS) count += compare(helper, source, movement, "context " + block + " " + equipped);
                        BlockShapeParity.compare(helper, source, source.getBoundingBox().inflate(2), "entity context " + block);
                    }
                }
            }
        } finally {
            player.discard();
            strider.discard();
            boat.discard();
        }
        return count;
    }

    private static int medium(GameTestHelper helper) {
        List<Entity> entities = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) entities.add(CollisionTestSupport.spawnEntity(helper,
                    i < 20 ? EntityTypes.ZOMBIE : EntityTypes.OAK_BOAT,
                    new Vec3(2.3 + (i % 6) * 0.65, 1, 2.3 + (i / 6) * 0.65)));
            for (Entity entity : entities) for (Vec3 movement : MOVEMENTS) compare(helper, entity, movement, "medium movement");
            return entities.size() * MOVEMENTS.length;
        } finally {
            entities.forEach(Entity::discard);
        }
    }

    private static int border(GameTestHelper helper, Entity entity) {
        var border = helper.getLevel().getWorldBorder();
        double size = border.getSize(), centerX = border.getCenterX(), centerZ = border.getCenterZ();
        try {
            border.setCenter(entity.getX(), entity.getZ());
            border.setSize(2);
            for (Vec3 movement : MOVEMENTS) compare(helper, entity, movement, "world border");
            return MOVEMENTS.length;
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
    }
}

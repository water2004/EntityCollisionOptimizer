package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.List;

/** Mixed obstacle layouts, real Entity.move, player crouching and context-sensitive shapes. */
final class IrregularMovementParity {
    static void verify(GameTestHelper helper) {
        List<BlockState> obstacles = new ArrayList<>(List.of(
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.COBBLESTONE_WALL.defaultBlockState(),
                Blocks.IRON_BARS.defaultBlockState(), Blocks.CHAIN.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.ANVIL.defaultBlockState(),
                Blocks.POINTED_DRIPSTONE.defaultBlockState(), Blocks.LILY_PAD.defaultBlockState(),
                Blocks.SCAFFOLDING.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true),
                Blocks.STONE_SLAB.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)));
        for (int layers : new int[]{1, 3, 7, 8}) obstacles.add(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, layers));
        for (var shape : StairsShape.values()) obstacles.add(Blocks.STONE_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.STAIRS_SHAPE, shape).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        int cases = 0;
        for (var type : List.of(EntityType.ZOMBIE, EntityType.PLAYER, EntityType.ITEM,
                EntityType.BOAT, EntityType.MINECART, EntityType.MAGMA_CUBE,
                EntityType.FALLING_BLOCK, EntityType.ARROW, EntityType.EXPERIENCE_ORB, EntityType.TNT)) {
            for (var obstacle : obstacles) {
                for (boolean crouching : new boolean[]{false, true}) {
                    var expected = run(helper, false, type, obstacle, crouching);
                    var actual = run(helper, true, type, obstacle, crouching);
                    for (int step = 0; step < expected.size(); step++) actual.get(step).compare(helper, expected.get(step),
                            type + " " + obstacle + " crouching=" + crouching + " step=" + step);
                    cases++;
                }
            }
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_IRREGULAR_INTERACTIONS layouts={} cases={} steps_per_case=20 result=passed", obstacles.size(), cases);
    }

    private static List<InteractionScene.State> run(GameTestHelper helper, boolean enabled,
                                                     EntityType<?> type, BlockState obstacle, boolean crouching) {
        try (var scene = new InteractionScene(helper)) {
            scene.floor(Blocks.STONE);
            scene.block(5, 1, 4, obstacle);
            scene.block(5, 1, 5, Blocks.STONE_SLAB);
            scene.block(5, 3, 4, Blocks.STONE);
            scene.block(6, 1, 5, Blocks.OAK_FENCE);
            var entity = scene.spawn(type, new Vec3(4.1, 1, 4.5));
            entity.setOnGround(true);
            entity.setShiftKeyDown(crouching);
            if (type == EntityType.PLAYER && crouching) ((LivingEntity) entity).setItemSlot(EquipmentSlot.FEET, Items.LEATHER_BOOTS.getDefaultInstance());
            List<InteractionScene.State> states = new ArrayList<>();
            for (int step = 0; step < 20; step++) {
                Vec3 before = entity.position();
                Vec3 request = new Vec3(step < 12 ? 0.18 : -0.18, -0.12, step % 2 == 0 ? 0.08 : -0.03);
                entity.setDeltaMovement(request);
                entity.move(type == EntityType.PLAYER ? MoverType.PLAYER : MoverType.SELF, request);
                // In 1.21.1 Entity.move already calls tryCheckInsideBlocks.
                states.add(InteractionScene.State.of(entity));
            }
            return states;
        }
    }
}

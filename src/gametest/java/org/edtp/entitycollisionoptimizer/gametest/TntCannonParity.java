package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * TNT-cannon / bomber ejection: explosion knockback then real PrimedTnt and minecart movement.
 * Geometry is one 29-block tri-directional bomber unit, one 17-wide trencher rail cell,
 * and one world-eater TNT cell.
 */
final class TntCannonParity {
    private static final long SEED = 0xEC0262L;
    private static final int STEPS = 40;

    static void verify(GameTestHelper helper) {
        compare(helper, TntCannonParity::openAirShot, "open-air TNT shot");
        compare(helper, TntCannonParity::triDirectionalBomber, "29-block tri-directional bomber unit");
        compare(helper, TntCannonParity::trencherRailCell, "trencher TNT rail cell");
        compare(helper, TntCannonParity::worldEaterCell, "world-eater TNT cell");
        EntityCollisionOptimizer.LOGGER.info("ECO_TNT_CANNON_INTERACTIONS cases=4 steps_per_case={} result=passed", STEPS);
    }

    private static void compare(GameTestHelper helper, BiFunction<GameTestHelper, Boolean, Shot> run, String label) {
        var expected = run.apply(helper, false);
        var actual = run.apply(helper, true);
        helper.assertValueEqual(actual.payload.size(), expected.payload.size(), label + " TNT trajectory length");
        helper.assertValueEqual(actual.rest.size(), expected.rest.size(), label + " companion observation count");
        for (int tick = 0; tick < expected.payload.size(); tick++) {
            actual.payload.get(tick).compare(helper, expected.payload.get(tick), label + " TNT trajectory tick=" + tick);
        }
        for (int i = 0; i < expected.rest.size(); i++) {
            actual.rest.get(i).compare(helper, expected.rest.get(i), label + " companion observation=" + i);
        }
        helper.assertTrue(expected.payload.stream().anyMatch(s -> s.velocity().lengthSqr() > 0.01),
                label + " must actually launch the payload TNT");
        var start = expected.payload.getFirst();
        helper.assertTrue(expected.payload.stream().anyMatch(s -> s.position().distanceToSqr(start.position()) > 0.25),
                label + " payload TNT must travel after the explosion");
    }

    private static Shot openAirShot(GameTestHelper helper, boolean enabled) {
        try (var scene = new InteractionScene(helper, 12, 8, 12)) {
            scene.floor(Blocks.OBSIDIAN);
            for (int y = 1; y <= 4; y++) for (int z = 4; z <= 6; z++) scene.block(3, y, z, Blocks.OBSIDIAN);
            var payload = primed(scene, new Vec3(6.5, 2.0, 5.5), 80);
            var booster = primed(scene, new Vec3(4.5, 2.0, 5.5), 1);
            scene.seed(SEED);
            return trace(scene, payload, List.of(booster));
        }
    }

    private static Shot triDirectionalBomber(GameTestHelper helper, boolean enabled) {
        try (var scene = new InteractionScene(helper, 10, 6, 16)) {
            scene.floor(Blocks.STONE);
            int ox = 2, oy = 1, oz = 1;
            scene.block(ox + 3, oy, oz + 9, wall(WallSide.NONE, WallSide.NONE, WallSide.TALL, WallSide.NONE));
            scene.block(ox + 3, oy, oz + 10, Blocks.SLIME_BLOCK);
            scene.block(ox + 4, oy, oz + 10, Blocks.DEAD_FIRE_CORAL_WALL_FAN.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
            scene.block(ox + 3, oy, oz + 11, Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.DOWN));
            scene.block(ox + 3, oy + 1, oz + 9, wall(WallSide.NONE, WallSide.NONE, WallSide.LOW, WallSide.NONE));
            scene.block(ox + 2, oy + 1, oz + 10, wall(WallSide.LOW, WallSide.NONE, WallSide.NONE, WallSide.NONE));
            scene.block(ox + 3, oy + 1, oz + 10, Blocks.SLIME_BLOCK);
            scene.block(ox + 3, oy + 1, oz + 11, Blocks.SLIME_BLOCK);
            scene.block(ox + 3, oy + 1, oz + 12, Blocks.STICKY_PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.SOUTH));
            scene.block(ox + 2, oy + 1, oz + 13, Blocks.REDSTONE_BLOCK);
            scene.block(ox + 3, oy + 1, oz + 13, Blocks.SLIME_BLOCK);
            scene.block(ox + 3, oy + 2, oz + 10, Blocks.DETECTOR_RAIL.defaultBlockState()
                    .setValue(BlockStateProperties.POWERED, true)
                    .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.NORTH_SOUTH));
            scene.block(ox, oy + 2, oz + 11, Blocks.GLASS);
            scene.block(ox + 1, oy + 2, oz + 11, Blocks.PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST));
            scene.block(ox + 3, oy + 2, oz + 11, Blocks.SLIME_BLOCK);
            scene.block(ox, oy + 2, oz + 12, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy + 2, oz + 12, Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.SOUTH));
            scene.block(ox + 3, oy + 2, oz + 12, Blocks.STICKY_PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.NORTH));
            scene.block(ox, oy + 2, oz + 13, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy + 2, oz + 13, Blocks.PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST));
            scene.block(ox + 3, oy + 2, oz + 13, Blocks.SLIME_BLOCK);
            scene.block(ox + 3, oy + 2, oz + 14, Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.SOUTH));
            scene.block(ox + 2, oy + 3, oz, Blocks.REDSTONE_BLOCK);
            scene.block(ox + 2, oy + 3, oz + 1, Blocks.SLIME_BLOCK);
            scene.block(ox + 2, oy + 3, oz + 2, Blocks.SLIME_BLOCK);
            scene.block(ox, oy + 3, oz + 13, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy + 3, oz + 13, Blocks.SLIME_BLOCK);
            scene.block(ox + 2, oy + 3, oz + 13, Blocks.PISTON_HEAD.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.WEST)
                    .setValue(BlockStateProperties.PISTON_TYPE, PistonType.STICKY));
            scene.block(ox + 3, oy + 3, oz + 13, Blocks.STICKY_PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.EXTENDED, true)
                    .setValue(BlockStateProperties.FACING, Direction.WEST));
            scene.block(ox + 4, oy + 3, oz + 13, Blocks.REDSTONE_BLOCK);
            var payload = primed(scene, new Vec3(ox + 5.5, oy + 1.0, oz + 10.5), 80);
            var booster = primed(scene, new Vec3(ox + 6.5, oy + 1.0, oz + 10.5), 1);
            var cart = scene.spawn(EntityType.MINECART, new Vec3(ox + 3.5, oy + 2.0625, oz + 10.51));
            cart.setDeltaMovement(Vec3.ZERO);
            scene.seed(SEED);
            return trace(scene, payload, List.of(booster, cart));
        }
    }

    private static Shot trencherRailCell(GameTestHelper helper, boolean enabled) {
        try (var scene = new InteractionScene(helper, 12, 6, 10)) {
            scene.floor(Blocks.STONE);
            int ox = 3, oy = 1, oz = 3;
            scene.block(ox + 1, oy, oz, Blocks.SANDSTONE);
            scene.block(ox, oy, oz + 1, Blocks.NOTE_BLOCK);
            scene.block(ox + 2, oy, oz + 1, Blocks.SANDSTONE);
            scene.block(ox, oy, oz + 2, Blocks.SANDSTONE);
            scene.block(ox + 1, oy, oz + 2, Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST));
            scene.block(ox, oy + 1, oz, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy + 1, oz, Blocks.SANDSTONE);
            scene.block(ox, oy + 1, oz + 1, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy + 1, oz + 1, Blocks.SANDSTONE);
            scene.block(ox + 2, oy + 1, oz + 1, Blocks.SANDSTONE);
            scene.block(ox, oy + 1, oz + 2, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy + 1, oz + 2, Blocks.SANDSTONE);
            scene.block(ox + 2, oy + 1, oz + 2, Blocks.SANDSTONE);
            scene.block(ox + 2, oy + 2, oz, Blocks.SANDSTONE);
            scene.block(ox + 2, oy + 2, oz + 1, Blocks.DETECTOR_RAIL.defaultBlockState()
                    .setValue(BlockStateProperties.POWERED, true)
                    .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.NORTH_SOUTH));
            scene.block(ox, oy + 2, oz + 2, Blocks.REDSTONE_BLOCK);
            scene.block(ox + 2, oy + 2, oz + 2, Blocks.SANDSTONE);
            var payload = primed(scene, new Vec3(ox + 1.5, oy, oz + 1.5), 80);
            var booster = primed(scene, new Vec3(ox + 1.5, oy, oz + 0.5), 1);
            var cart = scene.spawn(EntityType.MINECART, new Vec3(ox + 2.5, oy + 2.0625, oz + 1.5));
            cart.setDeltaMovement(Vec3.ZERO);
            scene.seed(SEED);
            return trace(scene, payload, List.of(booster, cart));
        }
    }

    private static Shot worldEaterCell(GameTestHelper helper, boolean enabled) {
        try (var scene = new InteractionScene(helper, 12, 6, 10)) {
            scene.floor(Blocks.STONE);
            int ox = 6, oy = 2, oz = 5;
            scene.block(ox - 1, oy - 1, oz - 1, Blocks.SMOOTH_STONE);
            scene.block(ox, oy - 1, oz - 1, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy - 1, oz - 1, Blocks.SLIME_BLOCK);
            scene.block(ox - 1, oy - 1, oz, Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.SOUTH));
            scene.block(ox, oy - 1, oz, Blocks.STICKY_PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.NORTH));
            scene.block(ox + 1, oy - 1, oz, Blocks.SLIME_BLOCK);
            scene.block(ox - 1, oy - 1, oz + 1, Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.DOWN));
            scene.block(ox, oy, oz - 1, Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.SOUTH));
            scene.block(ox + 1, oy, oz - 1, Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST));
            scene.block(ox - 1, oy, oz, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy, oz, Blocks.SLIME_BLOCK);
            scene.block(ox - 1, oy, oz + 1, Blocks.SLIME_BLOCK);
            scene.block(ox + 1, oy, oz + 1, Blocks.SLIME_BLOCK);
            var payload = primed(scene, new Vec3(ox + 0.5, oy, oz + 0.5), 80);
            var booster = primed(scene, new Vec3(ox + 0.5, oy, oz - 0.5), 1);
            scene.seed(SEED);
            return trace(scene, payload, List.of(booster));
        }
    }

    private static PrimedTnt primed(InteractionScene scene, Vec3 position, int fuse) {
        var tnt = (PrimedTnt) scene.spawn(EntityType.TNT, position);
        tnt.setFuse(fuse);
        tnt.setDeltaMovement(Vec3.ZERO);
        tnt.setOnGround(true);
        return tnt;
    }

    private static BlockState wall(WallSide east, WallSide north, WallSide south, WallSide west) {
        return Blocks.STONE_BRICK_WALL.defaultBlockState()
                .setValue(BlockStateProperties.EAST_WALL, east)
                .setValue(BlockStateProperties.NORTH_WALL, north)
                .setValue(BlockStateProperties.SOUTH_WALL, south)
                .setValue(BlockStateProperties.WEST_WALL, west)
                .setValue(BlockStateProperties.UP, true);
    }

    private static Shot trace(InteractionScene scene, PrimedTnt payload, List<Entity> others) {
        var tnt = new ArrayList<InteractionScene.State>();
        var rest = new ArrayList<InteractionScene.State>();
        var level = scene.helper.getLevel();
        for (int step = 0; step < STEPS; step++) {
            CollisionFrame.begin(level);
            level.getRandom().setSeed(SEED + step);
            scene.seed(SEED + (long) step * 17);
            for (Entity entity : others) {
                if (!entity.isRemoved()) {
                    InteractionScene.prepareTick(entity);
                    entity.tick();
                }
                rest.add(InteractionScene.State.of(entity));
            }
            if (!payload.isRemoved()) {
                InteractionScene.prepareTick(payload);
                payload.tick();
            }
            tnt.add(InteractionScene.State.of(payload));
            CollisionFrame.end(level);
        }
        return new Shot(tnt, rest);
    }

    private record Shot(List<InteractionScene.State> payload, List<InteractionScene.State> rest) {}
}

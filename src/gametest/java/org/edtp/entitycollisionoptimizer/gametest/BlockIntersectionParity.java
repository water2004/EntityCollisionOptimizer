package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.collision.blocks.BlockCollisionQuery;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;

/** Independent vanilla iterator oracle, live contexts and first-hit callback contract. */
final class BlockIntersectionParity {
    private BlockIntersectionParity() {}

    static void verify(GameTestHelper helper) {
        var level = helper.getLevel();
        var entity = CollisionTestSupport.spawnZombie(helper, new Vec3(3.5, 3, 3.5));
        var cart = CollisionTestSupport.spawnEntity(helper, EntityTypes.MINECART, new Vec3(3.5, 3, 3.5));
        BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
        int cases = 0;
        try (var blocks = new ChunkFixtureBlocks()) {
            Method lithium = lithiumQuery();
            for (Block block : List.of(Blocks.AIR, Blocks.STONE, Blocks.STONE_SLAB, Blocks.OAK_FENCE,
                    Blocks.SCAFFOLDING, Blocks.POWDER_SNOW, Blocks.WATER, Blocks.LAVA, Blocks.RAIL)) {
                blocks.set(level, pos, block.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                for (boolean descending : new boolean[]{false, true}) {
                    entity.setShiftKeyDown(descending);
                    for (AABB box : List.of(new AABB(pos).deflate(.05), new AABB(pos).move(0, 1, 0),
                            new AABB(pos).move(0, 1 - 1e-8, 0), new AABB(pos).move(.9, .9, .9))) {
                        for (CollisionContext context : List.of(CollisionContext.empty(),
                                CollisionContext.emptyWithFluidCollisions(), CollisionContext.of(entity),
                                CollisionContext.of(cart), CollisionContext.positionContext(pos.getY() + 1))) {
                            boolean expected = reference(level, context, box);
                            helper.assertValueEqual(BlockCollisionQuery.intersects(level, context, box), expected,
                                    "block intersection " + block + " descending=" + descending);
                            cases++;
                        }
                        if (lithium != null) for (Entity source : new Entity[]{null, entity, cart}) {
                            CollisionContext context = source == null ? CollisionContext.empty() : CollisionContext.of(source);
                            helper.assertValueEqual(invoke(lithium, level, source, box), reference(level, context, box),
                                    "Lithium block-only bridge " + block);
                        }
                    }
                }
            }
            blocks.set(level, pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            blocks.set(level, pos.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            var context = new FirstHitContext();
            helper.assertTrue(BlockCollisionQuery.intersects(level, context,
                    new AABB(pos).minmax(new AABB(pos.east())).deflate(.05)), "first shape must collide");
            helper.assertValueEqual(context.reads, 1, "stop shape callbacks immediately after the first collision");
            liveCallbacks(helper, blocks, pos);
            // Mutating an already-indexed palette must change the next answer, not reuse the old boolean.
            blocks.set(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            AABB empty = new AABB(pos).deflate(.05);
            helper.assertTrue(!BlockCollisionQuery.intersects(level, CollisionContext.empty(), empty),
                    "palette write invalidates collision candidates");
            // The bridge is block-only; liquids and hard entities remain the caller's responsibility.
            helper.assertTrue(!BlockCollisionQuery.intersects(level, CollisionContext.of(cart), cart.getBoundingBox()),
                    "block query must not include the source or other entities");
            blocks.set(level, pos.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            Entity boat = CollisionTestSupport.spawnEntity(helper, EntityTypes.OAK_BOAT, new Vec3(3.5, 2.1, 3.5));
            try {
                AABB boatBox = boat.getBoundingBox();
                helper.assertTrue(!BlockCollisionQuery.intersects(level, CollisionContext.of(entity), boatBox),
                        "boat overlaps no block");
                helper.assertTrue(!level.noCollision(entity, boatBox), "noCollision still checks hard entities");
                blocks.set(level, pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                helper.assertTrue(level.noCollision(boat, boatBox), "water is not a solid collision for this context");
                helper.assertTrue(!boat.isFree(0, 0, 0), "isFree still rejects liquid after the block query");
                boat.setPos(level.getWorldBorder().getMaxX() - .25, boat.getY(), boat.getZ());
                helper.assertTrue(!BlockCollisionQuery.intersects(level, CollisionContext.of(boat), boat.getBoundingBox()),
                        "border probe overlaps no block in the unloaded area");
                helper.assertTrue(!level.noCollision(boat, boat.getBoundingBox()), "noCollision still checks the world border");
            } finally {
                boat.discard();
            }
        } finally {
            entity.discard();
            cart.discard();
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_BLOCK_INTERSECTION_PARITY cases={} first_hit=true live_palette=true caller_checks=true lithium={} result=passed",
                cases, FabricLoader.getInstance().isModLoaded("lithium"));
    }

    private static boolean reference(Level level, CollisionContext context, AABB box) {
        return new BlockCollisions<>(level, context, box, false, (pos, shape) -> shape).hasNext();
    }

    private static void liveCallbacks(GameTestHelper helper, ChunkFixtureBlocks blocks, BlockPos origin) {
        ServerLevel level = helper.getLevel();
        AABB box = new AABB(origin).minmax(new AABB(origin.east(2))).deflate(.05);
        List<BlockPos> expected = null;
        for (boolean optimized : new boolean[]{false, true}) {
            blocks.set(level, origin, Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            blocks.set(level, origin.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            blocks.set(level, origin.east(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            MutationContext context = new MutationContext(level, blocks, origin);
            boolean collided = optimized ? BlockCollisionQuery.intersects(level, context, box) : reference(level, context, box);
            helper.assertTrue(collided, "callback-created block collides");
            if (!optimized) expected = context.positions;
            else helper.assertValueEqual(context.positions, expected, "live palette writes and nested queries preserve vanilla callback order");
        }
        blocks.set(level, origin, Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        blocks.set(level, origin.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        blocks.set(level, origin.east(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
    }

    private static Method lithiumQuery() {
        if (!FabricLoader.getInstance().isModLoaded("lithium")) return null;
        try {
            return Class.forName("net.caffeinemc.mods.lithium.common.entity.LithiumEntityCollisions")
                    .getMethod("doesBoxCollideWithBlocks", Level.class, Entity.class, AABB.class);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean invoke(Method query, Level level, Entity entity, AABB box) {
        try {
            return (boolean) query.invoke(null, level, entity, box);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static class FirstHitContext implements CollisionContext {
        private int reads;
        @Override public VoxelShape getCollisionShape(BlockState state, CollisionGetter level, BlockPos pos) {
            if (++reads != 1) throw new AssertionError("queried a second shape after a collision");
            return Shapes.block();
        }
        @Override public boolean isDescending() { return false; }
        @Override public boolean isAbove(VoxelShape shape, BlockPos pos, boolean fallback) { return fallback; }
        @Override public boolean isHoldingItem(Item item) { return false; }
        @Override public boolean alwaysCollideWithFluid() { return false; }
        @Override public boolean canStandOnFluid(FluidState below, FluidState above) { return false; }
    }

    private static final class MutationContext extends FirstHitContext {
        private final ServerLevel level;
        private final ChunkFixtureBlocks blocks;
        private final BlockPos origin;
        private final List<BlockPos> positions = new ArrayList<>();

        MutationContext(ServerLevel level, ChunkFixtureBlocks blocks, BlockPos origin) {
            this.level = level;
            this.blocks = blocks;
            this.origin = origin;
        }

        @Override public VoxelShape getCollisionShape(BlockState state, CollisionGetter getter, BlockPos pos) {
            positions.add(pos.immutable());
            if (pos.equals(origin)) {
                blocks.set(level, origin.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                blocks.set(level, origin.east(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                BlockCollisionQuery.intersects(level, CollisionContext.empty(), new AABB(origin.above(4)));
                return Shapes.empty();
            }
            return state.getCollisionShape(getter, pos, CollisionContext.empty());
        }
    }
}

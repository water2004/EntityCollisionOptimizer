package org.edtp.entitycollisionoptimizer.gametest;

import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.edtp.entitycollisionoptimizer.collision.blocks.CollisionBlockMask;

/** Covers palette writes below Level/LevelChunk as well as copy and deserialization. */
final class BlockMaskParity {
    static void verify(GameTestHelper helper, PalettedContainer<BlockState> liveStates) {
        PalettedContainer<BlockState> states = liveStates.copy();
        check(helper, states);
        var random = new java.util.Random(262);
        for (int i = 0; i < 96; i++) {
            int x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
            BlockState value = switch (i % 5) {
                case 0 -> Blocks.STONE.defaultBlockState();
                case 1 -> Blocks.OAK_FENCE.defaultBlockState();
                case 2 -> Blocks.MOVING_PISTON.defaultBlockState();
                case 3 -> Blocks.HONEY_BLOCK.defaultBlockState();
                default -> Blocks.AIR.defaultBlockState();
            };
            switch (i % 3) {
                case 0 -> states.set(x, y, z, value);
                case 1 -> states.getAndSet(x, y, z, value);
                case 2 -> states.getAndSetUnchecked(x, y, z, value);
                default -> throw new AssertionError();
            }
            checkRow(helper, states, y, z);
        }
        check(helper, states);
        var copy = states.copy();
        check(helper, copy);
        copy.set(0, 0, 0, Blocks.MOVING_PISTON.defaultBlockState());
        states.set(0, 0, 0, Blocks.AIR.defaultBlockState());
        checkRow(helper, copy, 0, 0);
        checkRow(helper, states, 0, 0);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            copy.write(buffer);
            states.read(buffer);
            check(helper, states);
        } finally {
            buffer.release();
        }
        // Writes into a still-cold row must be visible when it is decoded for the first time.
        var cold = states.copy();
        cold.getAndSetUnchecked(15, 15, 15, Blocks.OAK_FENCE.defaultBlockState());
        checkRow(helper, cold, 15, 15);
        BlockRowIndexCost.measure(states);
    }

    private static void check(GameTestHelper helper, PalettedContainer<BlockState> states) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                checkRow(helper, states, y, z);
            }
        }
    }

    static void checkRow(GameTestHelper helper, PalettedContainer<BlockState> states, int y, int z) {
        var mask = (CollisionBlockMask) states;
        long row = mask.entityCollisionOptimizer$collisionRow(y, z, 0, 0)
                | ((long) mask.entityCollisionOptimizer$collisionRow(y, z, 1, 0) << 16)
                | ((long) mask.entityCollisionOptimizer$collisionRow(y, z, 2, 0) << 32);
        long expected = 0;
        for (int x = 0; x < 16; x++) {
            BlockState state = states.get(x, y, z);
            if (state.isAir()) continue;
            expected |= 1L << x;
            if (state.hasLargeCollisionShape()) expected |= 1L << (x + 16);
            if (state.is(Blocks.MOVING_PISTON)) expected |= 1L << (x + 32);
        }
        helper.assertValueEqual(row, expected, "three halo planes " + y + "," + z);
        for (int edgesYZ = 0; edgesYZ <= 2; edgesYZ++) {
            // Exercise no X boundary, either end, both ends, and arbitrary clipped boundaries.
            for (int edgesX : new int[]{0, 1, 0x8000, 0x8001, 0x1248, 0xffff}) {
                int selected = 0;
                for (int x = 0; x < 16; x++) {
                    BlockState state = states.get(x, y, z);
                    int edges = edgesYZ + ((edgesX >>> x) & 1);
                    if (!state.isAir() && edges != 3 && (edges != 1 || state.hasLargeCollisionShape())
                            && (edges != 2 || state.is(Blocks.MOVING_PISTON))) selected |= 1 << x;
                }
                helper.assertValueEqual(mask.entityCollisionOptimizer$collisionRow(y, z, edgesYZ, edgesX), selected,
                        "halo selection " + edgesYZ + "/" + edgesX);
            }
        }
    }
}

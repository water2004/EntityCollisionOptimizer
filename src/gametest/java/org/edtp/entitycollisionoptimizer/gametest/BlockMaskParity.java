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
        var mask = (CollisionBlockMask) states;
        check(helper, states);
        var random = new java.util.Random(262);
        for (int i = 0; i < 96; i++) {
            int x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
            BlockState value = (i & 1) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            switch (i % 3) {
                case 0 -> states.set(x, y, z, value);
                case 1 -> states.getAndSet(x, y, z, value);
                case 2 -> states.getAndSetUnchecked(x, y, z, value);
                default -> throw new AssertionError();
            }
            helper.assertValueEqual((mask.entityCollisionOptimizer$nonAirRow(y, z) >>> x) & 1,
                    value.isAir() ? 0 : 1, "live mask update " + i);
        }
        check(helper, states);
        var copy = states.copy();
        check(helper, copy);
        copy.set(0, 0, 0, Blocks.GOLD_BLOCK.defaultBlockState());
        states.set(0, 0, 0, Blocks.AIR.defaultBlockState());
        helper.assertValueEqual(((CollisionBlockMask) copy).entityCollisionOptimizer$nonAirRow(0, 0) & 1,
                1, "copy mask must be independent");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            copy.write(buffer);
            states.read(buffer);
            check(helper, states);
        } finally {
            buffer.release();
        }
    }

    private static void check(GameTestHelper helper, PalettedContainer<BlockState> states) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                int expected = 0;
                for (int x = 0; x < 16; x++) if (!states.get(x, y, z).isAir()) expected |= 1 << x;
                helper.assertValueEqual(((CollisionBlockMask) states).entityCollisionOptimizer$nonAirRow(y, z),
                        expected, "section row " + y + "," + z);
            }
        }
    }
}

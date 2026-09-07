package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.edtp.entitycollisionoptimizer.collision.blocks.CollisionBlockMask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Attached to the palette itself, so direct palette writes also update the index. */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> implements CollisionBlockMask {
    @Shadow public abstract T get(int x, int y, int z);
    @Unique private short[] entityCollisionOptimizer$collisionRows;
    @Unique private long[] entityCollisionOptimizer$knownRows;

    @Override
    public int entityCollisionOptimizer$collisionRow(int y, int z, int edgesYZ, int edgesX) {
        int row = (y << 4) | z;
        short[] rows = entityCollisionOptimizer$collisionRows;
        if (rows == null || (entityCollisionOptimizer$knownRows[row >>> 6] & (1L << (row & 63))) == 0) {
            rows = entityCollisionOptimizer$decodeRow(y, z, row);
        }
        int offset = row * 3 + edgesYZ;
        int selected = rows[offset] & 0xffff;
        if (edgesX != 0) {
            int boundary = edgesYZ == 2 ? 0 : rows[offset + 1] & 0xffff;
            selected = (selected & ~edgesX) | (boundary & edgesX);
        }
        return selected;
    }

    @Unique
    private short[] entityCollisionOptimizer$decodeRow(int y, int z, int row) {
        short[] rows = entityCollisionOptimizer$collisionRows;
        if (rows == null) {
            rows = new short[256 * 3];
            entityCollisionOptimizer$collisionRows = rows;
            entityCollisionOptimizer$knownRows = new long[4];
        }
        int offset = row * 3;
        long bits = 0;
        // A cold query reads only the requested 16-block row, not all 4,096 section blocks.
        for (int x = 0; x < 16; x++) bits |= entityCollisionOptimizer$flags((BlockState) get(x, y, z)) << x;
        rows[offset] = (short) bits;
        rows[offset + 1] = (short) (bits >>> 16);
        rows[offset + 2] = (short) (bits >>> 32);
        entityCollisionOptimizer$knownRows[row >>> 6] |= 1L << (row & 63);
        return rows;
    }

    @Inject(method = "set(ILjava/lang/Object;)V", at = @At("RETURN"))
    private void entityCollisionOptimizer$afterSet(int index, T value, CallbackInfo ci) {
        entityCollisionOptimizer$updateBit(index, value);
    }

    @Inject(method = "getAndSet(ILjava/lang/Object;)Ljava/lang/Object;", at = @At("RETURN"))
    private void entityCollisionOptimizer$afterGetAndSet(int index, T value, CallbackInfoReturnable<T> cir) {
        entityCollisionOptimizer$updateBit(index, value);
    }

    @Unique
    private void entityCollisionOptimizer$updateBit(int index, T value) {
        short[] rows = entityCollisionOptimizer$collisionRows;
        if (rows == null) return;
        // Block-state palettes use x | z << 4 | y << 8. Biome palettes never build this index.
        int bit = 1 << (index & 15);
        int row = index >>> 4;
        if ((entityCollisionOptimizer$knownRows[row >>> 6] & (1L << (row & 63))) == 0) return;
        long flags = entityCollisionOptimizer$flags((BlockState) value);
        for (int plane = 0; plane < 3; plane++) {
            int offset = row * 3 + plane;
            rows[offset] = (short) ((flags & (1L << (plane * 16))) == 0 ? rows[offset] & ~bit : rows[offset] | bit);
        }
    }

    @Unique
    private static long entityCollisionOptimizer$flags(BlockState state) {
        if (state.isAir()) return 0;
        // These are state-only vanilla halo predicates, not context-dependent collision shapes.
        return 1L | (state.hasLargeCollisionShape() ? 1L << 16 : 0) | (state.is(Blocks.MOVING_PISTON) ? 1L << 32 : 0);
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void entityCollisionOptimizer$beforeRead(FriendlyByteBuf buffer, CallbackInfo ci) {
        entityCollisionOptimizer$collisionRows = null;
        entityCollisionOptimizer$knownRows = null;
    }
}

package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.network.FriendlyByteBuf;
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
    @Unique private short[] entityCollisionOptimizer$nonAirRows;
    @Unique private long[] entityCollisionOptimizer$knownRows;

    @Override
    public int entityCollisionOptimizer$nonAirRow(int y, int z) {
        short[] rows = entityCollisionOptimizer$nonAirRows;
        if (rows == null) {
            rows = new short[256];
            entityCollisionOptimizer$nonAirRows = rows;
            entityCollisionOptimizer$knownRows = new long[4];
        }
        int row = (y << 4) | z;
        long knownBit = 1L << (row & 63);
        if ((entityCollisionOptimizer$knownRows[row >>> 6] & knownBit) == 0) {
            int bits = 0;
            // A cold query reads only the requested 16-block row, not all 4,096 section blocks.
            for (int x = 0; x < 16; x++) if (!((BlockState) get(x, y, z)).isAir()) bits |= 1 << x;
            rows[row] = (short) bits;
            entityCollisionOptimizer$knownRows[row >>> 6] |= knownBit;
        }
        return rows[row] & 0xffff;
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
        short[] rows = entityCollisionOptimizer$nonAirRows;
        if (rows == null) return;
        // Block-state palettes use x | z << 4 | y << 8. Biome palettes never build this index.
        int bit = 1 << (index & 15);
        int row = index >>> 4;
        if ((entityCollisionOptimizer$knownRows[row >>> 6] & (1L << (row & 63))) == 0) return;
        rows[row] = (short) (((BlockState) value).isAir() ? rows[row] & ~bit : rows[row] | bit);
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void entityCollisionOptimizer$beforeRead(FriendlyByteBuf buffer, CallbackInfo ci) {
        entityCollisionOptimizer$nonAirRows = null;
        entityCollisionOptimizer$knownRows = null;
    }
}

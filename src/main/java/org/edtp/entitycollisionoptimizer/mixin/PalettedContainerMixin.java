package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.edtp.entitycollisionoptimizer.collision.blocks.CollisionBlockMask;
import org.edtp.entitycollisionoptimizer.collision.blocks.CollisionRows;
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
    @Unique private CollisionRows entityCollisionOptimizer$collisionRows;

    @Override
    public int entityCollisionOptimizer$collisionRow(int y, int z, int edgesYZ, int edgesX) {
        int row = (y << 4) | z;
        CollisionRows rows = entityCollisionOptimizer$collisionRows;
        if (rows == null || !rows.known(row)) {
            rows = entityCollisionOptimizer$decodeRow(y, z, row);
        }
        int offset = row * 3 + edgesYZ;
        int selected = rows.get(offset);
        if (edgesX != 0) {
            int boundary = edgesYZ == 2 ? 0 : rows.get(offset + 1);
            selected = (selected & ~edgesX) | (boundary & edgesX);
        }
        return selected;
    }

    @Unique
    private CollisionRows entityCollisionOptimizer$decodeRow(int y, int z, int row) {
        CollisionRows rows = entityCollisionOptimizer$collisionRows;
        if (rows == null) {
            rows = new CollisionRows();
            entityCollisionOptimizer$collisionRows = rows;
        }
        long bits = 0;
        // A cold query reads only the requested 16-block row, not all 4,096 section blocks.
        for (int x = 0; x < 16; x++) bits |= entityCollisionOptimizer$flags((BlockState) get(x, y, z)) << x;
        rows.initialize(row, bits);
        return rows;
    }

    @Override
    public java.lang.foreign.MemorySegment entityCollisionOptimizer$collisionRows(int minY, int maxY, int minZ, int maxZ) {
        if (entityCollisionOptimizer$collisionRows != null
                && entityCollisionOptimizer$collisionRows.knownRectangle(minY, maxY, minZ, maxZ)) {
            return entityCollisionOptimizer$collisionRows.memory();
        }
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            int row = (y << 4) | z;
            if (entityCollisionOptimizer$collisionRows == null || !entityCollisionOptimizer$collisionRows.known(row)) {
                entityCollisionOptimizer$decodeRow(y, z, row);
            }
        }
        return entityCollisionOptimizer$collisionRows.memory();
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
        CollisionRows rows = entityCollisionOptimizer$collisionRows;
        if (rows == null) return;
        // Block-state palettes use x | z << 4 | y << 8. Biome palettes never build this index.
        if (!rows.known(index >>> 4)) return;
        long flags = entityCollisionOptimizer$flags((BlockState) value);
        rows.update(index, flags);
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
    }
}

package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.NativeVoxelAccess;
import org.edtp.entitycollisionoptimizer.collision.blocks.NativeVoxelGeometry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.foreign.MemorySegment;

@Mixin(VoxelShape.class)
public abstract class VoxelGeometryMixin implements NativeVoxelAccess {
    @Shadow @Final protected DiscreteVoxelShape shape;
    @Unique private volatile MemorySegment eco$geometry;

    @Override public final MemorySegment eco$nativeGeometry() {
        MemorySegment cached = eco$geometry;
        if (cached != null) return cached;
        synchronized (this) {
            if (eco$geometry == null) eco$geometry = NativeVoxelGeometry.create((VoxelShape) (Object) this, shape);
            return eco$geometry;
        }
    }
}

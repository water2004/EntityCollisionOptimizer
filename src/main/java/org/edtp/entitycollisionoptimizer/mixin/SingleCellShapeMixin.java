package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.SingleCellShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(VoxelShape.class)
public abstract class SingleCellShapeMixin implements SingleCellShape {
    @Shadow @Final protected DiscreteVoxelShape shape;

    @Override public boolean eco$isSingleCell() {
        // Subclasses may override clipping or expose additional coordinate planes.
        Class<?> implementation = getClass();
        return (implementation == ArrayVoxelShape.class || implementation == CubeVoxelShape.class)
                && shape.getXSize() == 1 && shape.getYSize() == 1 && shape.getZSize() == 1
                && !shape.isEmpty();
    }
}

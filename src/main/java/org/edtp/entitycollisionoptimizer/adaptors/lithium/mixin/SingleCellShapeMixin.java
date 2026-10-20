package org.edtp.entitycollisionoptimizer.adaptors.lithium.mixin;

import org.edtp.entitycollisionoptimizer.collision.blocks.SingleCellShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.shapes.VoxelShapeSimpleCube", remap = false)
public abstract class SingleCellShapeMixin implements SingleCellShape {
    @Shadow public abstract boolean isEmpty();

    @Override public boolean eco$isSingleCell() {
        // In particular, do not treat aligned-cuboid subclasses' internal planes as one cell.
        return getClass().getName().equals("net.caffeinemc.mods.lithium.common.shapes.VoxelShapeSimpleCube") && !isEmpty();
    }
}

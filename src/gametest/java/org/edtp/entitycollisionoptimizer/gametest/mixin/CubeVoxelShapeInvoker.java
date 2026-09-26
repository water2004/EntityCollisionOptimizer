package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Constructs a subdivided vanilla cube for the internal-grid-plane parity fixture. */
@Mixin(CubeVoxelShape.class)
public interface CubeVoxelShapeInvoker {
    @Invoker("<init>")
    static CubeVoxelShape eco$create(DiscreteVoxelShape shape) {
        throw new AssertionError("Mixin constructor invoker was not applied");
    }
}

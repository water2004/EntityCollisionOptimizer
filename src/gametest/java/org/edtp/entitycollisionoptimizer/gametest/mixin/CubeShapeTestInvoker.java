package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CubeVoxelShape.class)
public interface CubeShapeTestInvoker {
    @Invoker("<init>")
    static CubeVoxelShape eco$create(DiscreteVoxelShape shape) {
        throw new AssertionError();
    }
}

package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.gametest.BlockShapeCapture;
import org.edtp.entitycollisionoptimizer.natives.NativeShapeBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NativeShapeBatch.class, remap = false)
public abstract class NativeShapeBatchCaptureMixin {
    @Inject(method = "addTranslated", at = @At("HEAD"))
    private void eco$capture(VoxelShape shape, double x, double y, double z, CallbackInfo ci) {
        BlockShapeCapture.record((NativeShapeBatch) (Object) this, shape, x, y, z);
    }
}

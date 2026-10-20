package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.MovementColliders;
import org.edtp.entitycollisionoptimizer.gametest.BlockShapeCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MovementColliders.class, remap = false)
public abstract class MovementColliderCaptureMixin {
    @Inject(method = "add", at = @At("RETURN"))
    private void eco$shape(VoxelShape shape, CallbackInfo ci) {
        BlockShapeCapture.record(shape);
    }

    @Inject(method = "addBlock", at = @At("RETURN"))
    private void eco$block(int x, int y, int z, CallbackInfo ci) {
        BlockShapeCapture.record(Shapes.block().move(x, y, z));
    }
}

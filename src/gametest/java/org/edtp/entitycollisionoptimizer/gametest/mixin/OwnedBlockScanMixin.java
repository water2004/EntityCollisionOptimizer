package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.gametest.MovementScanDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = "org.edtp.entitycollisionoptimizer.collision.blocks.OrderedBlockColliders$Scan", remap = false)
public abstract class OwnedBlockScanMixin {
    @Unique private MovementScanDiagnostics.Probe eco$probe;
    @Unique private boolean eco$step;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void eco$scan(Level level, CollisionContext context, AABB box, List<VoxelShape> result, CallbackInfo ci) {
        eco$probe = MovementScanDiagnostics.current();
        if (eco$probe != null) {
            eco$step = eco$probe.step;
            eco$probe.scan(box, eco$step, true);
        }
    }

    @Inject(method = "add", at = @At("HEAD"))
    private void eco$read(BlockState state, int x, int y, int z, int edges, CallbackInfo ci) {
        if (eco$probe != null) eco$probe.read(BlockPos.asLong(x, y, z), eco$step, state.isAir());
    }
}

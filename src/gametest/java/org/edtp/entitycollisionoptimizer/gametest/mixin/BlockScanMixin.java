package org.edtp.entitycollisionoptimizer.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.edtp.entitycollisionoptimizer.gametest.MovementScanDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiFunction;

@Mixin(BlockCollisions.class)
public abstract class BlockScanMixin {
    @Unique private MovementScanDiagnostics.Probe eco$probe;
    @Unique private boolean eco$step;

    @Inject(method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V", at = @At("RETURN"))
    private void eco$measureScan(CollisionGetter level, net.minecraft.world.entity.Entity entity, AABB box,
                                 boolean suffocating, BiFunction<?, ?, ?> provider, CallbackInfo ci) {
        eco$probe = MovementScanDiagnostics.current();
        if (eco$probe != null) {
            eco$step = eco$probe.step;
            eco$probe.scan(box, eco$step, false);
        }
    }

    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState eco$countRead(BlockGetter getter, BlockPos pos, Operation<BlockState> original) {
        BlockState state = original.call(getter, pos);
        if (eco$probe != null) eco$probe.read(pos, eco$step, state.isAir());
        return state;
    }
}

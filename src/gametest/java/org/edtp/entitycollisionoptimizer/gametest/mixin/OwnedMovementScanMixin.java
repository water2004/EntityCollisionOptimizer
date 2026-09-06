package org.edtp.entitycollisionoptimizer.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;
import org.edtp.entitycollisionoptimizer.gametest.MovementScanDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(value = EntityMovementCollision.class, remap = false)
public abstract class OwnedMovementScanMixin {
    @WrapOperation(method = "collide", at = @At(value = "INVOKE", ordinal = 1, target =
            "Lorg/edtp/entitycollisionoptimizer/collision/blocks/OrderedBlockColliders;collect(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/List;)Ljava/util/List;"))
    private static List<VoxelShape> eco$step(Entity entity, AABB box, List<VoxelShape> shapes,
                                            Operation<List<VoxelShape>> original) {
        var probe = MovementScanDiagnostics.current();
        boolean previousStep = probe != null && probe.step;
        if (probe != null) probe.enteringStep();
        try {
            return original.call(entity, box, shapes);
        } finally {
            if (probe != null) probe.step = previousStep;
        }
    }
}

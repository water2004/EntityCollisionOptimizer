package org.edtp.entitycollisionoptimizer.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;
import org.edtp.entitycollisionoptimizer.gametest.MovementScanDiagnostics;
import org.edtp.entitycollisionoptimizer.natives.NativeMovement;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(value = EntityMovementCollision.class, remap = false)
public abstract class OwnedMovementScanMixin {
    @WrapMethod(method = "solve")
    private static NativeMovement eco$measure(Entity entity, Vec3 requested, Operation<NativeMovement> original) {
        if (MovementScanDiagnostics.current() != null) return original.call(entity, requested);
        var probe = MovementScanDiagnostics.begin(entity, requested);
        NativeMovement result = null;
        try {
            result = original.call(entity, requested);
            return result;
        } finally {
            MovementScanDiagnostics.end(probe, result == null ? null : result.displacement());
        }
    }

    @WrapMethod(method = "collectStep")
    private static void eco$step(Entity entity, List<VoxelShape> shapes, NativeMovement movement,
                                 Operation<Void> original) {
        var probe = MovementScanDiagnostics.current();
        boolean previousStep = probe != null && probe.step;
        if (probe != null) probe.enteringStep();
        try {
            original.call(entity, shapes, movement);
        } finally {
            if (probe != null) probe.step = previousStep;
        }
    }
}

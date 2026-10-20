package org.edtp.entitycollisionoptimizer.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;
import org.edtp.entitycollisionoptimizer.collision.blocks.MovementColliders;
import org.edtp.entitycollisionoptimizer.gametest.MovementScanDiagnostics;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = EntityMovementCollision.class, remap = false)
public abstract class OwnedMovementScanMixin {
    @WrapMethod(method = "collide")
    private static Vec3 eco$measure(Entity entity, Vec3 requested, Operation<Vec3> original) {
        if (MovementScanDiagnostics.current() != null) return original.call(entity, requested);
        var probe = MovementScanDiagnostics.begin(entity, requested);
        Vec3 result = null;
        try {
            result = original.call(entity, requested);
            return result;
        } finally {
            MovementScanDiagnostics.end(probe, result);
        }
    }

    @WrapMethod(method = "step")
    private static Vec3 eco$step(Entity entity, Vec3 requested, AABB bounds, Vec3 clipped,
            int[] hardIds, float maxStep, boolean landed, MovementColliders shapes, Operation<Vec3> original) {
        var probe = MovementScanDiagnostics.current();
        boolean previousStep = probe != null && probe.step;
        if (probe != null) probe.enteringStep();
        try {
            return original.call(entity, requested, bounds, clipped, hardIds, maxStep, landed, shapes);
        } finally {
            if (probe != null) probe.step = previousStep;
        }
    }
}

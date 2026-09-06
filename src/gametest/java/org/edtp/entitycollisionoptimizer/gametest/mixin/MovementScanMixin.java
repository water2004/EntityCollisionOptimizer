package org.edtp.entitycollisionoptimizer.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.gametest.MovementScanDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(Entity.class)
public abstract class MovementScanMixin {
    @WrapMethod(method = "collide")
    private Vec3 eco$measureMove(Vec3 movement, Operation<Vec3> original) {
        var probe = MovementScanDiagnostics.begin((Entity) (Object) this, movement);
        Vec3 actual = null;
        try {
            actual = original.call(movement);
            return actual;
        } finally {
            MovementScanDiagnostics.end(probe, actual);
        }
    }

    @WrapOperation(method = "collide", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/Entity;collectCollidersIgnoringWorldBorder(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> eco$measureStep(Entity entity, Level level, List<VoxelShape> shapes,
                                            AABB box, Operation<List<VoxelShape>> original) {
        var probe = MovementScanDiagnostics.current();
        boolean previousStep = probe != null && probe.step;
        if (probe != null) probe.enteringStep();
        try {
            return original.call(entity, level, shapes, box);
        } finally {
            if (probe != null) probe.step = previousStep;
        }
    }
}

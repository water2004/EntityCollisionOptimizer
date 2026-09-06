package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(Entity.class)
public interface EntityCollisionInvoker {
    @Invoker("collideWithShapes")
    static Vec3 eco$collideWithShapes(Vec3 movement, AABB box, List<VoxelShape> shapes) {
        throw new AssertionError("Mixin invoker was not applied");
    }

    @Invoker("collectCandidateStepUpHeights")
    static float[] eco$stepHeights(AABB box, List<VoxelShape> shapes, float maxStep, float actualY) {
        throw new AssertionError("Mixin invoker was not applied");
    }

    @Invoker("collide")
    Vec3 eco$collide(Vec3 movement);
}

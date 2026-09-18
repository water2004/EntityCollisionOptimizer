package org.edtp.entitycollisionoptimizer.gametest.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Vanilla clipping primitive as a parity oracle: the public entry points are taken over, so tests reach the unreplaced routine here. */
@Mixin(Entity.class)
public interface EntityCollisionInvoker {
    @Invoker("collideWithShapes")
    static Vec3 eco$collideWithShapes(Vec3 movement, AABB box, List<VoxelShape> shapes) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}

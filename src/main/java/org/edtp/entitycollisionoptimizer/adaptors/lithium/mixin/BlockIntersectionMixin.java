package org.edtp.entitycollisionoptimizer.adaptors.lithium.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.edtp.entitycollisionoptimizer.collision.blocks.BlockCollisionQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.entity.LithiumEntityCollisions", remap = false)
public abstract class BlockIntersectionMixin {
    @Inject(method = "doesBoxCollideWithBlocks", at = @At("HEAD"), cancellable = true, remap = false)
    private static void eco$intersectsBlocks(Level level, Entity entity, AABB box,
            CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel) {
            // Boolean queries do not need Lithium's deferred last collider. Keep noCollision's
            // subsequent hard-entity/world-border checks and the client implementation intact.
            cir.setReturnValue(BlockCollisionQuery.intersects(level,
                    entity == null ? CollisionContext.empty() : CollisionContext.of(entity), box));
        }
    }
}

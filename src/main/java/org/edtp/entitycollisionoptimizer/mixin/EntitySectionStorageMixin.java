package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;
import org.edtp.entitycollisionoptimizer.collision.EntitySectionStorageLevelBinding;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Route whole-level box queries through the native index while collision optimization is enabled. */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin implements EntitySectionStorageLevelBinding {
    @Unique private ServerLevel eco$queryLevel;

    @Override public void eco$setQueryLevel(ServerLevel level) { eco$queryLevel = level; }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void eco$nativeGetEntities(EntityTypeTest<?, ?> type, AABB box, AbortableIterationConsumer<?> consumer, CallbackInfo ci) {
        ServerLevel level = eco$queryLevel;
        if (!CollisionOptimizerConfig.enableEntityCollision || level == null) return;
        CollisionFrame.getEntities(level, type, box, consumer);
        ci.cancel();
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void eco$nativeGetEntitiesAll(AABB box, AbortableIterationConsumer<?> consumer, CallbackInfo ci) {
        ServerLevel level = eco$queryLevel;
        if (!CollisionOptimizerConfig.enableEntityCollision || level == null) return;
        CollisionFrame.getEntities(level, box, consumer);
        ci.cancel();
    }
}

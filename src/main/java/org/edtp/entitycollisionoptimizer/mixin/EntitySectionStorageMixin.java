package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.collision.EntitySectionStorageLevelBinding;
import org.edtp.entitycollisionoptimizer.collision.TypedEntityQueries;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Typed queries select between existing class collections and one native batch; untyped queries use native. */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin implements EntitySectionStorageLevelBinding {
    @Unique private ServerLevel eco$queryLevel;

    @Override public void eco$setQueryLevel(ServerLevel level) { eco$queryLevel = level; }

    @SuppressWarnings("unchecked")
    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"), cancellable = true
    )
    private <T extends Entity> void eco$typedGetEntities(EntityTypeTest<Entity, T> type, AABB box,
            AbortableIterationConsumer<T> consumer, CallbackInfo ci) {
        ServerLevel level = eco$queryLevel;
        if (level != null && TypedEntityQueries.query(level, (EntitySectionStorage<Entity>) (Object) this,
                type, box, consumer)) ci.cancel();
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void eco$nativeGetEntitiesAll(AABB box, AbortableIterationConsumer<?> consumer, CallbackInfo ci) {
        ServerLevel level = eco$queryLevel;
        if (level == null) return;
        CollisionFrame.getEntities(level, box, consumer);
        ci.cancel();
    }
}

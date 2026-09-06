package org.edtp.entitycollisionoptimizer.mixin;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.edtp.entitycollisionoptimizer.collision.CollisionOrderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the authoritative section insertion, including transfers and reentry. */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin {
    @Unique private long eco$nextOrder;

    @Inject(method = "add", at = @At("RETURN"))
    private void eco$insert(EntityAccess entity, CallbackInfo ci) {
        ((CollisionOrderState) entity).eco$sectionOrder(++eco$nextOrder);
    }
}

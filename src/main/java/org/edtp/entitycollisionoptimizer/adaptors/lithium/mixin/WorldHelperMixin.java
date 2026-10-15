package org.edtp.entitycollisionoptimizer.adaptors.lithium.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.adaptors.lithium.PushableQuery;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.world.WorldHelper", remap = false)
public abstract class WorldHelperMixin {
    @Inject(method = "getPushableEntities", at = @At("HEAD"), cancellable = true, remap = false)
    private static void eco$queryNativeIndex(Level world, EntitySectionStorage<Entity> storage,
            Entity excluded, AABB box, @Coerce Predicate<? super Entity> predicate,
            CallbackInfoReturnable<List<Entity>> cir) {
        if (world instanceof ServerLevel level && predicate instanceof PushableQuery query
                && query.eco$sourceRule() != null) {
            List<Entity> entities = CollisionFrame.getPushableEntities(
                    level, excluded, box, query.eco$sourceTeam(), query.eco$sourceRule());
            // Lithium's helper never includes the dragon, even if a mod makes it pushable.
            entities.removeIf(EnderDragon.class::isInstance);
            cir.setReturnValue(entities);
        }
        // Unattributed/custom predicates and client worlds retain Lithium's implementation.
        // Its section mask is lazy, so ordinary server queries no longer create a second index.
    }
}

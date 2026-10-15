package org.edtp.entitycollisionoptimizer.adaptors.lithium.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.adaptors.lithium.PushableQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
    @Inject(method = "pushableBy", at = @At(value = "RETURN", ordinal = 1))
    private static void eco$captureQuery(Entity source, CallbackInfoReturnable<Predicate<Entity>> cir,
            @Local Team team, @Local Team.CollisionRule rule) {
        if (source.level() instanceof ServerLevel && cir.getReturnValue() instanceof PushableQuery query) {
            // Capture the factory's locals, not a second getTeam() call or the later query's
            // excluded entity. Both can differ by the time a boat/minecart uses the predicate.
            query.eco$capturePushableQuery((PlayerTeam) team, rule);
        }
    }
}

package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.ParallelAABB;
import com.wiyuka.acceleratedrecoiling.natives.TempID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Shadow
    @Final
    private EntityTickList entityTickList;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void acceleratedRecoiling$prepareCollisions(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (!FoldConfig.enableEntityCollision) {
            return;
        }

        TempID.tickStart();
        List<Entity> entities = new ArrayList<>();
        entityTickList.forEach(entity -> {
            if (!entity.isRemoved() && !(entity instanceof Player)) {
                entities.add(entity);
            }
            TempID.addEntity(entity);
        });
        ParallelAABB.handleEntityPush(entities, 1.0E-7);
    }
}

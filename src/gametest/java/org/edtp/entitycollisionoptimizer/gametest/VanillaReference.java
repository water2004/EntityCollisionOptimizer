package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;

import java.util.List;

/** Reference implementations of vanilla collision algorithms for parity tests. */
final class VanillaReference {
    private VanillaReference() {
    }

    /** Mirrors vanilla LivingEntity.pushEntities without going through the production mixin. */
    static void pushEntities(LivingEntity source) {
        List<Entity> list = source.level().getEntities(
                source,
                source.getBoundingBox().inflate(0.2, 0.0, 0.2),
                EntitySelector.pushableBy(source)
        );
        if (list.isEmpty()) {
            return;
        }

        ServerLevel level = (ServerLevel) source.level();
        int maxCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        if (maxCramming > 0
                && list.size() > maxCramming - 1
                && source.getRandom().nextInt(4) == 0) {
            int nonPassengers = 0;
            for (Entity entity : list) {
                if (!entity.isPassenger()) {
                    nonPassengers++;
                }
            }
            if (nonPassengers > maxCramming - 1) {
                source.hurtServer(level, source.damageSources().cramming(), 6.0F);
            }
        }

        for (Entity entity : list) {
            ((LivingEntityTestInvoker) source).entityCollisionOptimizer$invokeDoPush(entity);
        }
    }
}

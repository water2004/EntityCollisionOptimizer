package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Reference implementations of vanilla collision algorithms for parity tests. */
final class VanillaReference {
    private static final Method PUSH_ENTITIES = pushEntitiesMethod();

    private VanillaReference() {
    }

    /** Mirrors vanilla LivingEntity.pushEntities without going through the production mixin. */
    static void pushEntities(LivingEntity source) {
        List<Entity> list = VanillaEntityQueries.call(() -> source.level().getEntities(
                source,
                source.getBoundingBox(),
                EntitySelector.pushableBy(source)
        ));
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

    /**
     * Invokes pushEntities with ordinary virtual dispatch. This matters for
     * vanilla subclasses such as ArmorStand and Bat which replace the method;
     * a Mixin @Invoker declared on LivingEntity calls the LivingEntity body.
     */
    static void dispatchPushEntities(LivingEntity source) {
        try {
            PUSH_ENTITIES.invoke(source);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access LivingEntity.pushEntities", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("LivingEntity.pushEntities failed", cause);
        }
    }

    static boolean overridesPushEntities(LivingEntity source) {
        for (Class<?> type = source.getClass(); type != LivingEntity.class; type = type.getSuperclass()) {
            try {
                type.getDeclaredMethod("pushEntities");
                return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    private static Method pushEntitiesMethod() {
        try {
            Method method = LivingEntity.class.getDeclaredMethod("pushEntities");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}

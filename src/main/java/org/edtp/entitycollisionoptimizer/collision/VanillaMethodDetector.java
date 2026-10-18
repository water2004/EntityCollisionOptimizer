package org.edtp.entitycollisionoptimizer.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Class-level method ownership used to select native collision operations. */
public final class VanillaMethodDetector {

    private VanillaMethodDetector() {
    }

    private static final ClassValue<Boolean> USE_VANILLA_GET_TEAM = declaringClass("getTeam", Entity.class);
    private static final ClassValue<Boolean> USE_VANILLA_CAN_BE_COLLIDED_WITH = declaringClass(
            "canBeCollidedWith",
            Entity.class,
            Entity.class
    );
    private static final ClassValue<Boolean> USE_VANILLA_CAN_COLLIDE_WITH = declaringClass(
            "canCollideWith",
            Entity.class,
            Entity.class
    );

    private static final ClassValue<Boolean> USE_VANILLA_DO_PUSH = declaringClass(
            "doPush", LivingEntity.class, Entity.class);
    private static final ClassValue<PushCapabilities> PUSH_CAPABILITIES = new ClassValue<>() {
        @Override
        protected PushCapabilities computeValue(Class<?> type) {
            Class<?> pushOwner = declaringOwner(type, "push", Entity.class);
            // LivingEntity only adds a sleeping guard, which the native batch applies live.
            boolean vanillaEntityPush = pushOwner == Entity.class || pushOwner == LivingEntity.class;
            boolean deferredVelocityWrites = declaringOwner(type, "push",
                    double.class, double.class, double.class) == Entity.class
                    && declaringOwner(type, "getDeltaMovement") == Entity.class
                    && declaringOwner(type, "setDeltaMovement", Vec3.class) == Entity.class;
            return new PushCapabilities(vanillaEntityPush, deferredVelocityWrites);
        }
    };

    /** One immutable result per class; entity state changes never invalidate method ownership. */
    public record PushCapabilities(boolean vanillaEntityPush, boolean deferredVelocityWrites) {}

    /** Only Entity's scoreboard lookup is revision-cached; derived vanilla teams are read live. */
    public static boolean usesVanillaGetTeam(Entity entity) {
        return USE_VANILLA_GET_TEAM.get(entity.getClass());
    }

    /** Entity's default canBeCollidedWith is always false; boats/shulkers/etc. override it. */
    public static boolean usesVanillaCanBeCollidedWith(Entity entity) {
        return USE_VANILLA_CAN_BE_COLLIDED_WITH.get(entity.getClass());
    }

    /** Entity.canCollideWith only keeps hard targets; boats also keep pushable entities. */
    public static boolean usesVanillaCanCollideWith(Entity entity) {
        return USE_VANILLA_CAN_COLLIDE_WITH.get(entity.getClass());
    }

    public static boolean usesVanillaDoPush(LivingEntity source) {
        return USE_VANILLA_DO_PUSH.get(source.getClass());
    }

    public static PushCapabilities pushCapabilities(Entity entity) {
        return PUSH_CAPABILITIES.get(entity.getClass());
    }

    /* Returns a ClassValue that checks if <methodName> is declared in the <expectedOwner> class. */
    private static ClassValue<Boolean> declaringClass(
            String methodName,
            Class<?> expectedOwner,
            Class<?>... parameterTypes
    ) {
        return new ClassValue<>() {
            @Override
            protected Boolean computeValue(Class<?> type) {
                return declaringOwner(type, methodName, parameterTypes) == expectedOwner;
            }
        };
    }

    private static Class<?> declaringOwner(Class<?> type, String methodName, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(methodName, parameterTypes).getDeclaringClass();
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}

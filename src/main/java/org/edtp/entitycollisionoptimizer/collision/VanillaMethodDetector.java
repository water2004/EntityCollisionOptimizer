package org.edtp.entitycollisionoptimizer.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.service.MixinService;

/**
 * The entity-selector predicates, expressed directly so the FFM path owns collision
 * selection without invoking another mod's query or predicate replacement.
 *
 * <p>The reflective lookups below need the <em>runtime</em> member names. Those are not the names
 * written in this module on intermediary-based Minecraft versions (1.21.11 and earlier), where
 * hardcoded Mojang names simply do not exist. The names are therefore read back from this mod's
 * own {@code @Invoker} declarations in {@code EntityMemberNames}/{@code LivingEntityMemberNames},
 * which Loom rewrites to the runtime namespace. An unresolved member only degrades to the
 * conservative live-callback path.
 */
public final class VanillaMethodDetector {

    /* This class is a utility class, with no instances. */
    private VanillaMethodDetector() {
    }

    private static final String ENTITY_NAMES = "org/edtp/entitycollisionoptimizer/mixin/EntityMemberNames";
    private static final String LIVING_ENTITY_NAMES = "org/edtp/entitycollisionoptimizer/mixin/LivingEntityMemberNames";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    private static final String GET_TEAM = runtimeName(ENTITY_NAMES, "eco$invokeGetTeam");
    private static final String CAN_BE_COLLIDED_WITH = runtimeName(ENTITY_NAMES, "eco$invokeCanBeCollidedWith");
    private static final String CAN_COLLIDE_WITH = runtimeName(ENTITY_NAMES, "eco$invokeCanCollideWith");
    private static final String PUSH_ENTITY = runtimeName(ENTITY_NAMES, "eco$invokePushEntity");
    private static final String PUSH_VECTOR = runtimeName(ENTITY_NAMES, "eco$invokePushVector");
    private static final String GET_DELTA_MOVEMENT = runtimeName(ENTITY_NAMES, "eco$invokeGetDeltaMovement");
    private static final String SET_DELTA_MOVEMENT = runtimeName(ENTITY_NAMES, "eco$invokeSetDeltaMovement");
    private static final String DO_PUSH = runtimeName(LIVING_ENTITY_NAMES, "eco$invokeDoPush");

    /** Checks if the entity using the vanilla methods.
     * Will be used to determine wether to use the vanilla collision methods or the FFM ones.
    */
    private static final ClassValue<Boolean> USE_VANILLA_GET_TEAM = declaringClass(GET_TEAM, Entity.class);
    private static final ClassValue<Boolean> USE_VANILLA_CAN_BE_COLLIDED_WITH = declaringClass(
            CAN_BE_COLLIDED_WITH,
            Entity.class,
            Entity.class
    );
    private static final ClassValue<Boolean> USE_VANILLA_CAN_COLLIDE_WITH = declaringClass(
            CAN_COLLIDE_WITH,
            Entity.class,
            Entity.class
    );

    private static final ClassValue<Boolean> USE_VANILLA_DO_PUSH = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredMethod(DO_PUSH, Entity.class).getDeclaringClass()
                            == LivingEntity.class;
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }
            return false;
        }
    };
    private static final ClassValue<Boolean> USE_VANILLA_ENTITY_PUSH = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Class<?> owner = current.getDeclaredMethod(PUSH_ENTITY, Entity.class).getDeclaringClass();
                    // LivingEntity only adds a sleeping guard, which the native batch applies live.
                    return owner == Entity.class || owner == LivingEntity.class;
                } catch (NoSuchMethodException ignored) {
                }
            }
            return false;
        }
    };
    private static final ClassValue<Boolean> USE_VANILLA_VECTOR_PUSH = declaringClass(
            PUSH_VECTOR,
            Entity.class,
            double.class,
            double.class,
            double.class
    );
    private static final ClassValue<Boolean> USE_VANILLA_VELOCITY_GETTER = declaringClass(GET_DELTA_MOVEMENT, Entity.class);
    private static final ClassValue<Boolean> USE_VANILLA_VELOCITY_SETTER = declaringClass(SET_DELTA_MOVEMENT, Entity.class, Vec3.class);

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

    public static boolean usesVanillaEntityPush(Entity entity) {
        return USE_VANILLA_ENTITY_PUSH.get(entity.getClass());
    }

    public static boolean allowsDeferredVelocityWrites(Entity entity) {
        Class<?> type = entity.getClass();
        // A native run may defer writes only across ordinary, non-observing velocity accessors.
        return USE_VANILLA_VECTOR_PUSH.get(type) && USE_VANILLA_VELOCITY_GETTER.get(type)
                && USE_VANILLA_VELOCITY_SETTER.get(type);
    }

    /* Reads the runtime name of a member from this mod's own remapped @Invoker declaration. */
    static String runtimeName(String mixin, String invokerMethod) {
        try {
            ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(mixin, false);
            for (MethodNode method : node.methods) {
                if (!method.name.equals(invokerMethod)) continue;
                for (var annotations : new java.util.List[]{method.visibleAnnotations, method.invisibleAnnotations}) {
                    if (annotations == null) continue;
                    for (AnnotationNode annotation : (java.util.List<AnnotationNode>) annotations) {
                        if (!INVOKER.equals(annotation.desc) || annotation.values == null) continue;
                        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
                            if ("value".equals(annotation.values.get(index))
                                    && annotation.values.get(index + 1) instanceof String value
                                    && !value.isEmpty()) {
                                return value;
                            }
                        }
                    }
                }
            }
        } catch (java.io.IOException | ClassNotFoundException failure) {
            throw new IllegalStateException("Cannot read member names from " + mixin, failure);
        }
        throw new IllegalStateException("Missing @Invoker name source " + mixin + "." + invokerMethod);
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
                Class<?> current = type;
                while (current != null) {
                    try {
                        return current.getDeclaredMethod(methodName, parameterTypes).getDeclaringClass()
                                == expectedOwner;
                    } catch (NoSuchMethodException ignored) {
                        current = current.getSuperclass();
                    }
                }
                return false;
            }
        };
    }
}

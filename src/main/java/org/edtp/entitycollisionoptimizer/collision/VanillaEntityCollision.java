package org.edtp.entitycollisionoptimizer.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft 26.2's EntitySelector.pushableBy predicate, expressed directly so
 * the FFM path owns collision selection without invoking another mod's query
 * or predicate replacement.
 */
public final class VanillaEntityCollision {
    private static final ClassValue<Boolean> SCOREBOARD_TEAM = declaringClass("getTeam", Entity.class);

    /** Only Entity's scoreboard lookup is revision-cached; derived vanilla teams are read live. */
    public static boolean usesScoreboardTeam(Entity entity) {
        return SCOREBOARD_TEAM.get(entity.getClass());
    }

    private static final ClassValue<Boolean> USE_VANILLA_DO_PUSH = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredMethod("doPush", Entity.class).getDeclaringClass()
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
                    Class<?> owner = current.getDeclaredMethod("push", Entity.class).getDeclaringClass();
                    // LivingEntity only adds a sleeping guard, which the native batch applies live.
                    return owner == Entity.class || owner == LivingEntity.class;
                } catch (NoSuchMethodException ignored) {
                }
            }
            return false;
        }
    };
    private static final ClassValue<Boolean> USE_VANILLA_VECTOR_PUSH = declaringClass(
            "push",
            Entity.class,
            double.class,
            double.class,
            double.class
    );
    private static final ClassValue<Boolean> USE_VANILLA_VELOCITY_GETTER = declaringClass("getDeltaMovement", Entity.class);
    private static final ClassValue<Boolean> USE_VANILLA_VELOCITY_SETTER = declaringClass("setDeltaMovement", Entity.class, Vec3.class);

    private VanillaEntityCollision() {
    }

    public static Team.CollisionRule collisionRule(PlayerTeam team) {
        return team == null ? Team.CollisionRule.ALWAYS : team.getCollisionRule();
    }

    public static boolean isPushableBy(
            Entity source,
            PlayerTeam sourceTeam,
            Team.CollisionRule sourceRule,
            Entity target
    ) {
        if (sourceRule == Team.CollisionRule.NEVER
                || target.isSpectator()
                || !target.isPushable()) {
            return false;
        }
        if (source.level().isClientSide()
                && (!(target instanceof Player player) || !player.isLocalPlayer())) {
            return false;
        }
        return passesTeamRules(sourceTeam, sourceRule, target.getTeam());
    }

    private static boolean passesTeamRules(
            PlayerTeam sourceTeam,
            Team.CollisionRule sourceRule,
            PlayerTeam targetTeam
    ) {
        Team.CollisionRule targetRule = collisionRule(targetTeam);
        if (targetRule == Team.CollisionRule.NEVER) {
            return false;
        }

        boolean allied = sourceTeam != null && sourceTeam.isAlliedTo(targetTeam);
        if ((sourceRule == Team.CollisionRule.PUSH_OWN_TEAM
                || targetRule == Team.CollisionRule.PUSH_OWN_TEAM) && allied) {
            return false;
        }
        return (sourceRule != Team.CollisionRule.PUSH_OTHER_TEAMS
                && targetRule != Team.CollisionRule.PUSH_OTHER_TEAMS) || allied;
    }

    public static boolean usesVanillaDoPush(LivingEntity source) {
        return USE_VANILLA_DO_PUSH.get(source.getClass());
    }

    public static boolean usesVanillaEntityPush(Entity entity) {
        return USE_VANILLA_ENTITY_PUSH.get(entity.getClass());
    }

    public static boolean usesVanillaVectorPush(Entity entity) {
        Class<?> type = entity.getClass();
        // A native run may defer writes only across ordinary, non-observing velocity accessors.
        return USE_VANILLA_VECTOR_PUSH.get(type) && USE_VANILLA_VELOCITY_GETTER.get(type)
                && USE_VANILLA_VELOCITY_SETTER.get(type);
    }

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

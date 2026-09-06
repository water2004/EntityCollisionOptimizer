package org.edtp.entitycollisionoptimizer.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * Minecraft 26.2's EntitySelector.pushableBy predicate, expressed directly so
 * the FFM path owns collision selection without invoking another mod's query
 * or predicate replacement.
 */
public final class VanillaEntityCollision {
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

    public static boolean pushHasNoEffect(
            boolean sourceUsesVanillaDoPush,
            LivingEntity source,
            Entity target
    ) {
        if (!sourceUsesVanillaDoPush
                || !(target instanceof LivingEntity)
                || target instanceof Shulker
                || target instanceof AbstractCubeMob) {
            return false;
        }
        double deltaX = source.getX() - target.getX();
        double deltaZ = source.getZ() - target.getZ();
        return Math.max(Math.abs(deltaX), Math.abs(deltaZ)) < 0.009999999776482582;
    }
}

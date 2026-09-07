package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps one native spatial index per server level. Level-local state is
 * required because Worldthreader may tick dimensions in parallel.
 */
public final class CollisionFrame {
    private static final ConcurrentMap<ServerLevel, LevelCollisionFrame> LEVEL_FRAMES =
            new ConcurrentHashMap<>();

    private CollisionFrame() {
    }

    public static void begin(ServerLevel level) {
        LEVEL_FRAMES.computeIfAbsent(level, ignored -> new LevelCollisionFrame()).begin(level);
    }

    public static void end(ServerLevel level) {
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) {
            frame.end();
        }
    }

    /** Explicit vanilla/Carpet ownership: materialize outstanding velocities on this level's thread. */
    public static void suspend(ServerLevel level) {
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) frame.suspend();
    }

    public static void destroy() {
        for (LevelCollisionFrame frame : LEVEL_FRAMES.values()) {
            frame.close();
        }
        LEVEL_FRAMES.clear();
    }

    public static void addEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) {
            frame.addEntity(entity);
        }
    }

    public static void updateBoundingBox(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) {
            frame.updateBoundingBox(entity);
        }
    }

    public static void invalidateEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) {
            frame.invalidateEntity(entity);
        }
    }

    public static FFMBackend.QueryResult query(Entity source) {
        return frameFor(source).query(source);
    }

    public static FFMBackend.QueryResult queryPushable(
            LivingEntity source,
            PlayerTeam sourceTeam,
            Team.CollisionRule sourceRule,
            boolean sourceUsesVanillaPush
    ) {
        return frameFor(source).queryPushable(
                source,
                sourceTeam,
                sourceRule,
                sourceUsesVanillaPush
        );
    }

    public static Entity entity(Entity source, int nativeId) {
        if (!(source.level() instanceof ServerLevel level)) {
            return null;
        }
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        return frame == null ? null : frame.entity(nativeId);
    }

    public static PushBatch collectPushable(LivingEntity source, PlayerTeam team,
                                            Team.CollisionRule rule, boolean vanillaPush) {
        return frameFor(source).collectPushable(source, team, rule, vanillaPush);
    }

    public static boolean contains(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        return frame != null && frame.contains(entity);
    }

    public static boolean isActive(ServerLevel level) {
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        return frame != null && frame.isActive();
    }

    private static LevelCollisionFrame frameFor(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Native entity collision queries require a server level");
        }
        LevelCollisionFrame frame = LEVEL_FRAMES.computeIfAbsent(level, ignored -> new LevelCollisionFrame());
        if (!frame.isActive()) {
            frame.begin(level);
        }
        return frame;
    }
}

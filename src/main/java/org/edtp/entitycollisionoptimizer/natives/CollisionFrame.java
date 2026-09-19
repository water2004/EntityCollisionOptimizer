package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.collision.EntitySectionStorageLevelBinding;
import org.edtp.entitycollisionoptimizer.mixin.PersistentEntitySectionManagerAccessor;
import org.edtp.entitycollisionoptimizer.mixin.ServerLevelAccessor;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps one native spatial index per server level. Level-local state is
 * required because Worldthreader may tick dimensions in parallel.
 */
public final class CollisionFrame {
    // Use a concurrent map to allow for parallel ticking of multiple levels, as in Worldthreader.
    private static final ConcurrentMap<ServerLevel, LevelCollisionFrame> LEVEL_FRAMES =
            new ConcurrentHashMap<>();
    private CollisionFrame() {
    }

    public static void begin(ServerLevel level) {
        attach(level);
        LEVEL_FRAMES.computeIfAbsent(level, ignored -> new LevelCollisionFrame()).begin(level);
    }

    public static void attach(ServerLevel level) {
        /* Get the private entity manager for the server level, implemented in ServerLevelAccessor.java */
        ServerLevelAccessor levelAccess =
                (ServerLevelAccessor) (Object) level;
        var entityManager = levelAccess.eco$entityManager();

        PersistentEntitySectionManagerAccessor managerAccess =
                (PersistentEntitySectionManagerAccessor) (Object) entityManager;
        var sectionStorage = managerAccess.eco$sectionStorage();

        EntitySectionStorageLevelBinding storageBinding =
                (EntitySectionStorageLevelBinding) (Object) sectionStorage;
        storageBinding.eco$setQueryLevel(level);
    }

    public static void end(ServerLevel level) {
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) {
            frame.end();
        }
    }

    public static void destroy() {
        for (LevelCollisionFrame frame : LEVEL_FRAMES.values()) {
            frame.close();
        }
        LEVEL_FRAMES.clear();
    }

    /** EntitySectionStorage.getEntities through the native index. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void getEntities(ServerLevel level, EntityTypeTest type, AABB box,
            AbortableIterationConsumer consumer) {
        frameFor(level).getEntities(type, box, consumer);
    }

    /** Untyped EntitySectionStorage.getEntities through the native index. */
    @SuppressWarnings("rawtypes")
    public static void getEntities(ServerLevel level, AABB box,
            AbortableIterationConsumer consumer) {
        frameFor(level).getEntities(box, consumer);
    }

    public static void trackingStarted(ServerLevel level, Entity entity) {
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) frame.addEntity(entity);
    }

    public static void trackingEnded(ServerLevel level, Entity entity) {
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) frame.removeEntity(entity);
    }

    public static void sectionChanged(ServerLevel level, Entity entity) {
        LevelCollisionFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) frame.updateSection(entity);
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

    public static int[] hardCollisionIds(Entity source, AABB scan) {
        return frameFor(source).hardCollisionIds(source, scan);
    }

    public static void addHardCubes(Entity source, int[] hardIds, NativeShapeBatch shapes) {
        frameFor(source).addHardCubes(hardIds, shapes);
    }

    public static List<VoxelShape> getEntityCollisions(ServerLevel level, Entity entity, AABB box) {
        return frameFor(level).getEntityCollisions(entity, box);
    }

    public static PushBatch collectPushable(LivingEntity source, PlayerTeam sourceTeam,
                                            Team.CollisionRule sourceRule, boolean sourceUsesVanillaDoPush) {
        return frameFor(source).collectPushable(source, sourceTeam, sourceRule, sourceUsesVanillaDoPush);
    }

    private static LevelCollisionFrame frameFor(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Native entity collision queries require a server level");
        }
        return frameFor(level);
    }

    private static LevelCollisionFrame frameFor(ServerLevel level) {
        LevelCollisionFrame frame = LEVEL_FRAMES.computeIfAbsent(level, ignored -> new LevelCollisionFrame());
        if (!frame.isActive()) {
            frame.begin(level);
        }
        return frame;
    }
}

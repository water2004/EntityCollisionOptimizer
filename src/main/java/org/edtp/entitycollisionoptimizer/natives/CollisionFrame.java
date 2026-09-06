package org.edtp.entitycollisionoptimizer.natives;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.CollisionImpulseState;
import org.edtp.entitycollisionoptimizer.collision.VanillaEntityCollision;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps one native spatial index per server level. Level-local state is
 * required because Worldthreader may tick dimensions in parallel.
 */
public final class CollisionFrame {
    private static final ConcurrentMap<ServerLevel, LevelFrame> LEVEL_FRAMES =
            new ConcurrentHashMap<>();

    private CollisionFrame() {
    }

    public static void begin(ServerLevel level) {
        LEVEL_FRAMES.computeIfAbsent(level, ignored -> new LevelFrame()).begin(level);
    }

    public static void end(ServerLevel level) {
        LevelFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) {
            frame.end();
        }
    }

    public static void destroy() {
        for (LevelFrame frame : LEVEL_FRAMES.values()) {
            frame.close();
        }
        LEVEL_FRAMES.clear();
    }

    public static void addEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) {
            frame.addEntity(entity);
        }
    }

    public static void updateBoundingBox(Entity entity, AABB box) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelFrame frame = LEVEL_FRAMES.get(level);
        if (frame != null) {
            frame.updateBoundingBox(entity, box);
        }
    }

    public static void invalidateEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        LevelFrame frame = LEVEL_FRAMES.get(level);
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
        LevelFrame frame = LEVEL_FRAMES.get(level);
        return frame == null ? null : frame.entity(nativeId);
    }

    public static boolean contains(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        LevelFrame frame = LEVEL_FRAMES.get(level);
        return frame != null && frame.contains(entity);
    }

    public static boolean isActive(ServerLevel level) {
        LevelFrame frame = LEVEL_FRAMES.get(level);
        return frame != null && frame.isActive();
    }

    private static LevelFrame frameFor(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Native entity collision queries require a server level");
        }
        LevelFrame frame = LEVEL_FRAMES.computeIfAbsent(level, ignored -> new LevelFrame());
        if (!frame.isActive()) {
            frame.begin(level);
        }
        return frame;
    }

    private static final class LevelFrame {
        private static final long UNCACHED = Long.MIN_VALUE;
        private static final int INVALIDATE_SELECTABLE = 1;
        private static final int INVALIDATE_TEAM = 2;

        private final TempID ids = new TempID();
        private final FFMBackend.Context nativeContext = FFMBackend.createContext();
        private final IdentityHashMap<PlayerTeam, Integer> teamIds = new IdentityHashMap<>();
        private long[] selectableEntityRevisions = new long[0];
        private long[] selectableBlockRevisions = new long[0];
        private byte[] selectableValues = new byte[0];
        private long[] teamRevisions = new long[0];
        private PlayerTeam[] teams = new PlayerTeam[0];
        private long nativeBlockRevision;
        private long nativeTeamRevision;
        private boolean active;

        synchronized void begin(ServerLevel level) {
            ids.tickStart();
            teamIds.clear();

            List<Entity> entities = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (!entity.isRemoved()) {
                    ids.addEntity(entity);
                    entities.add(entity);
                }
            }

            double[] boxes = new double[entities.size() * 6];
            double[] positions = new double[entities.size() * 2];
            int[] sections = new int[entities.size() * 3];
            for (int index = 0; index < entities.size(); index++) {
                Entity entity = entities.get(index);
                writeBox(boxes, index * 6, entity.getBoundingBox());
                writeLocation(positions, sections, index, entity);
            }
            prepareSemanticCaches(entities.size());
            nativeBlockRevision = CollisionCacheEpochs.blockRevision();
            nativeTeamRevision = CollisionCacheEpochs.teamRevision();
            FFMBackend.beginFrame(
                    nativeContext,
                    boxes,
                    positions,
                    sections,
                    entities.size(),
                    CollisionOptimizerConfig.gridSize
            );
            active = true;
        }

        synchronized void end() {
            flushPendingImpulses();
            active = false;
        }

        synchronized void close() {
            active = false;
            nativeContext.close();
        }

        synchronized boolean isActive() {
            return active;
        }

        synchronized void addEntity(Entity entity) {
            if (!active || entity.isRemoved() || ids.contains(entity)) {
                return;
            }

            int expectedId = ids.addEntity(entity);
            BlockPos position = entity.blockPosition();
            int nativeId = FFMBackend.addEntity(
                    nativeContext,
                    entity.getBoundingBox(),
                    entity.getX(),
                    entity.getZ(),
                    SectionPos.blockToSectionCoord(position.getX()),
                    SectionPos.blockToSectionCoord(position.getY()),
                    SectionPos.blockToSectionCoord(position.getZ())
            );
            if (nativeId != expectedId) {
                throw new IllegalStateException(
                        "Native collision index assigned entity " + nativeId + "; expected " + expectedId
                );
            }
            ensureSemanticCapacity(nativeId + 1);
            selectableEntityRevisions[nativeId] = UNCACHED;
            teamRevisions[nativeId] = UNCACHED;
        }

        synchronized void updateBoundingBox(Entity entity, AABB box) {
            if (!active || !ids.contains(entity)) {
                return;
            }
            int nativeId = ids.getId(entity);
            BlockPos position = entity.blockPosition();
            FFMBackend.updateEntity(
                    nativeContext,
                    nativeId,
                    box,
                    entity.getX(),
                    entity.getZ(),
                    SectionPos.blockToSectionCoord(position.getX()),
                    SectionPos.blockToSectionCoord(position.getY()),
                    SectionPos.blockToSectionCoord(position.getZ())
            );
            refreshNativeMetadata(nativeId, entity);
        }

        synchronized void invalidateEntity(Entity entity) {
            if (active && ids.contains(entity)) {
                FFMBackend.invalidateEntityMetadata(nativeContext, ids.getId(entity));
            }
        }

        synchronized FFMBackend.QueryResult query(Entity source) {
            addEntity(source);
            return FFMBackend.query(nativeContext, ids.getId(source), ids.size());
        }

        synchronized FFMBackend.QueryResult queryPushable(
                LivingEntity source,
                PlayerTeam sourceTeam,
                Team.CollisionRule sourceRule,
                boolean sourceUsesVanillaPush
        ) {
            addEntity(source);
            synchronizeGlobalInvalidations();

            int sourceId = ids.getId(source);
            refreshNativeMetadata(sourceId, source);
            int sourceTeamId = teamId(sourceTeam);
            int sourceRuleId = collisionRuleId(sourceRule);
            FFMBackend.QueryResult result;
            int refreshPasses = 0;
            do {
                result = FFMBackend.queryPushable(
                        nativeContext,
                        sourceId,
                        sourceTeamId,
                        sourceRuleId,
                        sourceUsesVanillaPush,
                        ids.size()
                );
                if (!result.metadataRequired()) {
                    return result;
                }
                for (int index = 0; index < result.size(); index++) {
                    int targetId = result.get(index);
                    Entity target = ids.getEntity(targetId);
                    if (target == null) {
                        throw new IllegalStateException(
                                "Native collision metadata requested an unknown entity " + targetId
                        );
                    }
                    refreshNativeMetadata(targetId, target);
                }
                refreshPasses++;
            } while (refreshPasses <= 2);
            throw new IllegalStateException("Native collision metadata did not converge");
        }

        synchronized Entity entity(int nativeId) {
            return ids.getEntity(nativeId);
        }

        synchronized boolean contains(Entity entity) {
            return active && ids.contains(entity);
        }

        private boolean isSelectable(int nativeId, Entity entity) {
            ensureSemanticCapacity(nativeId + 1);
            long entityRevision = ((CollisionCacheState) entity)
                    .entityCollisionOptimizer$collisionRevision();
            long blockRevision = CollisionCacheEpochs.blockRevision();
            if (selectableEntityRevisions[nativeId] != entityRevision
                    || selectableBlockRevisions[nativeId] != blockRevision) {
                selectableValues[nativeId] = (byte) (
                        !entity.isRemoved() && !entity.isSpectator() && entity.isPushable() ? 1 : 0
                );
                selectableEntityRevisions[nativeId] = entityRevision;
                selectableBlockRevisions[nativeId] = blockRevision;
            }
            return selectableValues[nativeId] != 0;
        }

        private PlayerTeam team(int nativeId, Entity entity) {
            ensureSemanticCapacity(nativeId + 1);
            long teamRevision = CollisionCacheEpochs.teamRevision();
            if (teamRevisions[nativeId] != teamRevision) {
                teams[nativeId] = entity.getTeam();
                teamRevisions[nativeId] = teamRevision;
            }
            return teams[nativeId];
        }

        private void synchronizeGlobalInvalidations() {
            long blockRevision = CollisionCacheEpochs.blockRevision();
            long teamRevision = CollisionCacheEpochs.teamRevision();
            int mask = 0;
            if (nativeBlockRevision != blockRevision) {
                nativeBlockRevision = blockRevision;
                mask |= INVALIDATE_SELECTABLE;
            }
            if (nativeTeamRevision != teamRevision) {
                nativeTeamRevision = teamRevision;
                mask |= INVALIDATE_TEAM;
            }
            if (mask != 0) {
                FFMBackend.invalidateMetadata(nativeContext, mask);
            }
        }

        private void refreshNativeMetadata(int nativeId, Entity entity) {
            PlayerTeam targetTeam = team(nativeId, entity);
            FFMBackend.updateEntityMetadata(
                    nativeContext,
                    nativeId,
                    isSelectable(nativeId, entity),
                    entity.isPassenger(),
                    entity.isVehicle(),
                    entity.noPhysics,
                    VanillaEntityCollision.usesVanillaEntityPush(entity),
                    VanillaEntityCollision.usesVanillaVectorPush(entity),
                    teamId(targetTeam),
                    collisionRuleId(VanillaEntityCollision.collisionRule(targetTeam))
            );
        }

        private void flushPendingImpulses() {
            for (int nativeId = 0; nativeId < ids.size(); nativeId++) {
                Entity entity = ids.getEntity(nativeId);
                if (entity != null) {
                    ((CollisionImpulseState) entity).entityCollisionOptimizer$flushCollisionImpulse();
                }
            }
        }

        private int teamId(PlayerTeam team) {
            if (team == null) {
                return -1;
            }
            Integer existing = teamIds.get(team);
            if (existing != null) {
                return existing;
            }
            int id = teamIds.size();
            teamIds.put(team, id);
            return id;
        }

        private void prepareSemanticCaches(int entityCount) {
            ensureSemanticCapacity(entityCount);
            Arrays.fill(selectableEntityRevisions, 0, entityCount, UNCACHED);
            Arrays.fill(teamRevisions, 0, entityCount, UNCACHED);
            Arrays.fill(teams, 0, entityCount, null);
        }

        private void ensureSemanticCapacity(int requiredCapacity) {
            if (requiredCapacity <= selectableValues.length) {
                return;
            }
            int grownCapacity = selectableValues.length
                    + (selectableValues.length >> 1)
                    + 256;
            int newCapacity = Math.max(requiredCapacity, grownCapacity);
            int oldCapacity = selectableValues.length;
            selectableEntityRevisions = Arrays.copyOf(selectableEntityRevisions, newCapacity);
            selectableBlockRevisions = Arrays.copyOf(selectableBlockRevisions, newCapacity);
            selectableValues = Arrays.copyOf(selectableValues, newCapacity);
            teamRevisions = Arrays.copyOf(teamRevisions, newCapacity);
            teams = Arrays.copyOf(teams, newCapacity);
            Arrays.fill(selectableEntityRevisions, oldCapacity, newCapacity, UNCACHED);
            Arrays.fill(teamRevisions, oldCapacity, newCapacity, UNCACHED);
        }
    }

    private static void writeBox(double[] target, int offset, AABB box) {
        target[offset] = box.minX;
        target[offset + 1] = box.minY;
        target[offset + 2] = box.minZ;
        target[offset + 3] = box.maxX;
        target[offset + 4] = box.maxY;
        target[offset + 5] = box.maxZ;
    }

    private static void writeLocation(
            double[] positions,
            int[] sections,
            int index,
            Entity entity
    ) {
        int positionOffset = index * 2;
        positions[positionOffset] = entity.getX();
        positions[positionOffset + 1] = entity.getZ();
        BlockPos position = entity.blockPosition();
        int sectionOffset = index * 3;
        sections[sectionOffset] = SectionPos.blockToSectionCoord(position.getX());
        sections[sectionOffset + 1] = SectionPos.blockToSectionCoord(position.getY());
        sections[sectionOffset + 2] = SectionPos.blockToSectionCoord(position.getZ());
    }

    private static int collisionRuleId(Team.CollisionRule rule) {
        if (rule == Team.CollisionRule.ALWAYS) {
            return 0;
        }
        if (rule == Team.CollisionRule.NEVER) {
            return 1;
        }
        if (rule == Team.CollisionRule.PUSH_OWN_TEAM) {
            return 2;
        }
        if (rule == Team.CollisionRule.PUSH_OTHER_TEAMS) {
            return 3;
        }
        throw new IllegalArgumentException("Unsupported collision rule " + rule);
    }
}

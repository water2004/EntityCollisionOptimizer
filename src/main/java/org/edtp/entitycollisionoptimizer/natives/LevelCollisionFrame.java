package org.edtp.entitycollisionoptimizer.natives;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.CollisionOrderState;
import org.edtp.entitycollisionoptimizer.collision.VanillaMethodDetector;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/** One level's persistent spatial index and semantic metadata; no cross-level scratch state. */
final class LevelCollisionFrame {
    private static final long UNCACHED = Long.MIN_VALUE;
    private static final int INVALIDATE_SELECTABLE = 1;
    private static final int INVALIDATE_TEAM = 2;
    private static final int[] NO_HARD_COLLISIONS = new int[0];

    private final PersistentEntityIds ids = new PersistentEntityIds();
    private final FFMBackend.Context nativeContext = FFMBackend.createContext();
    private final CollisionStateTable bodies = new CollisionStateTable();
    private final IdentityHashMap<PlayerTeam, Integer> teamIds = new IdentityHashMap<>();
    private final ArrayDeque<PushBatch> batchPool = new ArrayDeque<>();
    private final ArrayDeque<int[]> entityQuerySnapshots = new ArrayDeque<>();
    private final List<Entity> derivedTeams = new ArrayList<>();
    private long[] selectableEntityRevisions = new long[0];
    private long[] selectableBlockRevisions = new long[0];
    private byte[] selectableValues = new byte[0];
    private long[] teamRevisions = new long[0];
    private PlayerTeam[] teams = new PlayerTeam[0];
    private long nativeBlockRevision;
    private long nativeTeamRevision;
    private boolean active;
    private boolean initialized;

    synchronized void begin(ServerLevel level) {
        if (!initialized) {
            initialized = true;
            // Bootstrap once. Tracking callbacks own membership after this point.
            for (Entity entity : level.getAllEntities()) {
                if (!entity.isRemoved()) addEntity(entity);
            }
        }
        synchronizePushEligibilityRevisions();
        active = true;
    }

    synchronized void end() {
        active = false;
    }

    synchronized void suspend() {
        active = false;
        bodies.clear();
        ids.clear();
        initialized = false;
        FFMBackend.beginFrame(nativeContext, new double[0], new int[0], 0, CollisionOptimizerConfig.STARTUP_GRID_SIZE);
        teamIds.clear();
        derivedTeams.clear();
        Arrays.fill(teams, null);
    }

    synchronized void close() {
        active = false;
        batchPool.clear();
        bodies.close();
        nativeContext.close();
    }

    synchronized boolean isActive() {
        return active;
    }

    synchronized void addEntity(Entity entity) {
        if (!initialized || entity.isRemoved() || ids.contains(entity)) {
            return;
        }

        int nativeId = ids.addEntity(entity);
        if (!VanillaMethodDetector.usesVanillaGetTeam(entity)) derivedTeams.add(entity);
        BlockPos position = entity.blockPosition();
        int sectionX = SectionPos.blockToSectionCoord(position.getX());
        int sectionY = SectionPos.blockToSectionCoord(position.getY());
        int sectionZ = SectionPos.blockToSectionCoord(position.getZ());
        if (CollisionOptimizerConfig.STARTUP_VANILLA_ORDER) {
            FFMBackend.putOrderedEntity(nativeContext, nativeId, entity.getBoundingBox(),
                    sectionX, sectionY, sectionZ, ((CollisionOrderState) entity).eco$sectionOrder());
        } else {
            FFMBackend.putEntity(nativeContext, nativeId, entity.getBoundingBox(),
                    sectionX, sectionY, sectionZ);
        }
        ensureSemanticCapacity(nativeId + 1);
        selectableEntityRevisions[nativeId] = UNCACHED;
        teamRevisions[nativeId] = UNCACHED;
        if (!VanillaMethodDetector.usesVanillaCanBeCollidedWith(entity)) {
            refreshNativeMetadata(nativeId, entity);
        }
    }

    synchronized void updateBoundingBox(Entity entity) {
        if (!initialized || !ids.contains(entity)) {
            return;
        }
        int nativeId = ids.getId(entity);
        int slot = bodies.slot(entity);
        refreshNativeMetadata(nativeId, entity, bodies.movementRow(slot));
    }

    synchronized void invalidateEntity(Entity entity) {
        if (!initialized) return;
        int id = ids.getId(entity);
        if (id >= 0) FFMBackend.invalidateEntityPushabilityCache(nativeContext, id);
    }

    synchronized void updateSection(Entity entity) {
        int id = ids.getId(entity);
        if (id < 0) return;
        BlockPos position = entity.blockPosition();
        int sectionX = SectionPos.blockToSectionCoord(position.getX());
        int sectionY = SectionPos.blockToSectionCoord(position.getY());
        int sectionZ = SectionPos.blockToSectionCoord(position.getZ());
        if (CollisionOptimizerConfig.STARTUP_VANILLA_ORDER) {
            FFMBackend.updateOrderedLocation(nativeContext, id, sectionX, sectionY, sectionZ,
                    ((CollisionOrderState) entity).eco$sectionOrder());
        } else {
            FFMBackend.updateLocation(nativeContext, id, sectionX, sectionY, sectionZ);
        }
    }

    synchronized void removeEntity(Entity entity) {
        int id = ids.getId(entity);
        if (id < 0) return;
        FFMBackend.removeEntity(nativeContext, id);
        ids.remove(entity);
        derivedTeams.remove(entity);
        teams[id] = null;
        bodies.retire(entity);
    }

    synchronized FFMBackend.QueryResult query(Entity source) {
        addEntity(source);
        return FFMBackend.query(nativeContext, ids.getId(source), ids.size());
    }

    synchronized int[] hardCollision(Entity source, AABB scan) {
        addEntity(source);
        if (scan.getSize() < 1.0E-7) {
            return NO_HARD_COLLISIONS;
        }
        FFMBackend.QueryResult result = FFMBackend.queryHard(
                nativeContext,
                scan.inflate(1.0E-7),
                ids.getId(source),
                VanillaMethodDetector.usesVanillaCanCollideWith(source),
                ids.size()
        );
        int[] matches = new int[result.size()];
        int count = 0;
        for (int index = 0; index < result.size(); index++) {
            int nativeId = result.get(index);
            Entity target = ids.getEntity(nativeId);
            if (target == null) {
                throw new IllegalStateException("Native hard collision query returned unknown entity " + nativeId);
            }
            if (target.isRemoved() || target.isSpectator() || !source.canCollideWith(target)) {
                continue;
            }
            matches[count++] = nativeId;
        }
        return count == matches.length ? matches : Arrays.copyOf(matches, count);
    }

    synchronized void addHardCubes(int[] hardIds, NativeShapeBatch shapes) {
        for (int nativeId : hardIds) {
            Entity target = ids.getEntity(nativeId);
            if (target == null) {
                throw new IllegalStateException("Native hard collision cube requested unknown entity " + nativeId);
            }
            shapes.addCube(bodies.movementRow(bodies.slot(target)));
        }
    }

    /** EntitySectionStorage.getEntities via the native index. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    synchronized void getEntities(EntityTypeTest type, AABB box, AbortableIterationConsumer consumer) {
        FFMBackend.QueryResult result = FFMBackend.queryEntities(nativeContext, box, ids.size());
        int[] snapshot = snapshot(result);
        try {
            for (int index = 0; index < result.size(); index++) {
                int nativeId = snapshot[index];
                Entity entity = requireEntity(nativeId);
                Object candidate = type.tryCast(entity);
                if (candidate != null && consumer.accept(candidate).shouldAbort()) break;
            }
        } finally {
            entityQuerySnapshots.addFirst(snapshot);
        }
    }

    /** Untyped EntitySectionStorage.getEntities via the native index. */
    @SuppressWarnings("rawtypes")
    synchronized void getEntities(AABB box, AbortableIterationConsumer consumer) {
        FFMBackend.QueryResult result = FFMBackend.queryEntities(nativeContext, box, ids.size());
        int[] snapshot = snapshot(result);
        try {
            for (int index = 0; index < result.size(); index++) {
                if (consumer.accept(requireEntity(snapshot[index])).shouldAbort()) break;
            }
        } finally {
            entityQuerySnapshots.addFirst(snapshot);
        }
    }

    private int[] snapshot(FFMBackend.QueryResult result) {
        int[] snapshot = entityQuerySnapshots.pollFirst();
        if (snapshot == null || snapshot.length < result.size()) {
            int capacity = 16;
            while (capacity < result.size()) capacity = Math.multiplyExact(capacity, 2);
            snapshot = new int[capacity];
        }
        for (int index = 0; index < result.size(); index++) snapshot[index] = result.get(index);
        return snapshot;
    }

    private Entity requireEntity(int nativeId) {
        Entity entity = ids.getEntity(nativeId);
        if (entity == null) throw new IllegalStateException("Native entity query returned unknown entity " + nativeId);
        return entity;
    }

    synchronized List<VoxelShape> entityCollisions(Entity source, AABB scan) {
        if (source != null) {
            addEntity(source);
        }
        if (scan.getSize() < 1.0E-7) {
            return List.of();
        }
        int excludeId = source == null ? -1 : ids.getId(source);
        FFMBackend.QueryResult result = FFMBackend.queryHard(
                nativeContext,
                scan.inflate(1.0E-7),
                excludeId,
                source == null || VanillaMethodDetector.usesVanillaCanCollideWith(source),
                ids.size()
        );
        if (result.size() == 0) {
            return List.of();
        }
        List<VoxelShape> shapes = new ArrayList<>(result.size());
        for (int index = 0; index < result.size(); index++) {
            int nativeId = result.get(index);
            Entity target = ids.getEntity(nativeId);
            if (target == null) {
                throw new IllegalStateException("Native hard collision query returned unknown entity " + nativeId);
            }
            if (target.isRemoved() || target.isSpectator()) {
                continue;
            }
            if (source == null ? !target.canBeCollidedWith(null) : !source.canCollideWith(target)) {
                continue;
            }
            shapes.add(Shapes.create(target.getBoundingBox()));
        }
        return shapes.isEmpty() ? List.of() : List.copyOf(shapes);
    }

    synchronized FFMBackend.QueryResult queryPushable(
            LivingEntity source,
            PlayerTeam sourceTeam,
            Team.CollisionRule sourceRule,
            boolean sourceUsesVanillaPush
    ) {
        addEntity(source);
        synchronizePushEligibilityRevisions();
        // Derived teams can change without any scoreboard mutation (taming, owner resolution).
        // This is a semantic dependency, not an entity/mod whitelist or a density-dependent path.
        for (Entity target : derivedTeams) refreshNativeMetadata(ids.getId(target), target);

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

    synchronized PushBatch collectPushable(LivingEntity source, PlayerTeam team,
                                            Team.CollisionRule rule, boolean vanillaPush) {
        FFMBackend.QueryResult result = queryPushable(source, team, rule, vanillaPush);
        PushBatch batch = batchPool.pollFirst();
        if (batch == null) {
            batch = new PushBatch(nativeContext, bodies, this::recycle);
        }
        try {
            batch.prepare(result);
            return batch;
        } catch (RuntimeException | Error failure) {
            batch.close();
            throw failure;
        }
    }

    private synchronized void recycle(PushBatch batch) {
        batchPool.addFirst(batch);
    }

    synchronized boolean contains(Entity entity) {
        return active && ids.contains(entity);
    }

    private boolean isSelectableCached(int nativeId, Entity entity) {
        ensureSemanticCapacity(nativeId + 1);
        long entityRevision = ((CollisionCacheState) entity)
                .entityCollisionOptimizer$collisionRevision();
        long blockRevision = CollisionCacheEpochs.blockRevision();
        if (selectableEntityRevisions[nativeId] != entityRevision
                || selectableBlockRevisions[nativeId] != blockRevision) {
            selectableValues[nativeId] = (byte) (
                    !entity.isRemoved() && !entity.isSpectator()
                            && ((CollisionCacheState) entity).entityCollisionOptimizer$isPushableCached() ? 1 : 0
            );
            selectableEntityRevisions[nativeId] = entityRevision;
            selectableBlockRevisions[nativeId] = blockRevision;
        }
        return selectableValues[nativeId] != 0;
    }

    private PlayerTeam team(int nativeId, Entity entity) {
        if (!VanillaMethodDetector.usesVanillaGetTeam(entity)) return entity.getTeam();
        ensureSemanticCapacity(nativeId + 1);
        long teamRevision = CollisionCacheEpochs.teamRevision();
        if (teamRevisions[nativeId] != teamRevision) {
            teams[nativeId] = entity.getTeam();
            teamRevisions[nativeId] = teamRevision;
        }
        return teams[nativeId];
    }

    /* setblock() will increment the block revision, causing the selectable values to be invalidated. Team values the same */
    private void synchronizePushEligibilityRevisions() {
        long blockRevision = CollisionCacheEpochs.blockRevision();
        long teamRevision = CollisionCacheEpochs.teamRevision();
        int fieldsToInvalidate = 0;
        if (nativeBlockRevision != blockRevision) {
            nativeBlockRevision = blockRevision;
            fieldsToInvalidate |= INVALIDATE_SELECTABLE;
        }
        if (nativeTeamRevision != teamRevision) {
            nativeTeamRevision = teamRevision;
            fieldsToInvalidate |= INVALIDATE_TEAM;
        }
        if (fieldsToInvalidate != 0) {
            FFMBackend.invalidatePushEligibilityFields(nativeContext, fieldsToInvalidate);
        }
    }

    private void refreshNativeMetadata(int nativeId, Entity entity) {
        refreshNativeMetadata(nativeId, entity, MemorySegment.NULL);
    }

    private void refreshNativeMetadata(int nativeId, Entity entity, MemorySegment bounds) {
        PlayerTeam targetTeam = team(nativeId, entity);
        FFMBackend.updateEntity(
                nativeContext,
                nativeId,
                bounds,
                isSelectableCached(nativeId, entity),
                entity.isPassenger(),
                entity.isVehicle(),
                entity.noPhysics,
                VanillaMethodDetector.usesVanillaEntityPush(entity),
                VanillaMethodDetector.usesVanillaVectorPush(entity),
                teamId(targetTeam),
                collisionRuleId(targetTeam == null
                        ? Team.CollisionRule.ALWAYS : targetTeam.getCollisionRule()),
                bodies.slot(entity),
                !entity.isRemoved() && !entity.isSpectator()
                        && !VanillaMethodDetector.usesVanillaCanBeCollidedWith(entity),
                CollisionOptimizerConfig.STARTUP_VANILLA_ORDER
                        ? ((CollisionOrderState) entity).eco$sectionOrder() : 0L
        );
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

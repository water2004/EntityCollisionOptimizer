package org.edtp.entitycollisionoptimizer.natives;

import org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.VanillaMethodDetector;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/** One level's persistent spatial index and semantic metadata; no cross-level scratch state. */
final class LevelCollisionFrame {
    // revisions are started at 0, using Long.MIN_VALUE as a sentinel to indicate that the value is uncached.
    private static final long UNCACHED = Long.MIN_VALUE;
    // Shared with native metadata requests and invalidation: state and team are independent.
    private static final int METADATA_STATE = 1;
    private static final int METADATA_TEAM = 2;
    // Empty list, used as preallocated objects, prevent unnecessary allocations. Shared across levels and threads: callers must never modify it
    private static final int[] EMPTY_IDS = new int[0];
    // Transformer for the native index and the Java entity objects. 
    private final PersistentEntityIds ids = new PersistentEntityIds();
    private final FFMBackend.Context nativeContext = FFMBackend.createContext();
    // 80*N bytes cache for the native index, storing the entity's bounding box, pushability, team, and other metadata. 
    private final CollisionStateTable bodies = new CollisionStateTable();
    // Transformer for the native index and the Java team objects. 
    private final IdentityHashMap<PlayerTeam, Integer> teamIds = new IdentityHashMap<>();
    // Preallocated objects for pushable entity queries, to avoid allocating a new PushBatch for every query. The PushBatch is recycled after use.
    private final ArrayDeque<PushBatch> batchPool = new ArrayDeque<>();
    // Reusable int[] copies of the native box-query result; the native output buffer is reused by the next query, so ids are copied out before iterating and the array is returned to the pool afterwards.
    private final ArrayDeque<int[]> entityQuerySnapshots = new ArrayDeque<>();
    // Metadata callbacks may load chunks and re-enter this frame. Only miss-path snapshots are leased.
    private final ArrayDeque<int[]> metadataQuerySnapshots = new ArrayDeque<>();
    private long[] selectableEntityRevisions = new long[0];
    private long[] selectableBlockRevisions = new long[0];
    private byte[] selectableValues = new byte[0];
    private long[] teamRevisions = new long[0];
    private PlayerTeam[] teams = new PlayerTeam[0];
    private long nativeBlockRevision;
    private long nativeTeamRevision;
    private long membershipRevision;
    private long pushQueryEpoch;
    private boolean active;
    private boolean initialized;

    synchronized void begin(ServerLevel level) {
        if (!initialized) {
            initialized = true;
            // Bootstrap once. Tracking callbacks own membership after this point.
            CollisionFrame.forEachSectionEntity(level, entity -> {
                if (!entity.isRemoved()) addEntity(entity);
            });
        }
        invalidateStalePushEligibility();
        active = true;
    }

    synchronized void end() {
        active = false;
    }

    synchronized void close() {
        active = false;
        for (PushBatch batch : batchPool) batch.destroy();
        batchPool.clear();
        bodies.close();
        ids.clear();
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
        membershipRevision++;
        BlockPos position = entity.blockPosition();
        int sectionX = SectionPos.blockToSectionCoord(position.getX());
        int sectionY = SectionPos.blockToSectionCoord(position.getY());
        int sectionZ = SectionPos.blockToSectionCoord(position.getZ());
        FFMBackend.insertEntity(nativeContext, nativeId, entity.getBoundingBox(),
                sectionX, sectionY, sectionZ,
                !entity.isSpectator() && !VanillaMethodDetector.usesVanillaCanBeCollidedWith(entity),
                !VanillaMethodDetector.usesVanillaGetTeam(entity));
        ensureSemanticCapacity(nativeId + 1);
        selectableEntityRevisions[nativeId] = UNCACHED;
        teamRevisions[nativeId] = UNCACHED;
    }

    synchronized void updateBoundingBox(Entity entity) {
        if (!initialized || !ids.contains(entity)) {
            return;
        }
        int nativeId = ids.getNativeId(entity);
        // Publishing bounds must not evaluate isPushable(): it may load a chunk while
        // teleporting. Refresh semantic metadata lazily at the next collision query.
        FFMBackend.updateEntityBounds(nativeContext, nativeId, entity.getBoundingBox());
    }

    synchronized void invalidateEntity(Entity entity) {
        if (!initialized) return;
        int nativeId = ids.getNativeId(entity);
        if (nativeId >= 0) FFMBackend.invalidateEntityPushEligibilityCache(nativeContext, nativeId);
    }

    synchronized void updateSection(Entity entity) {
        int nativeId = ids.getNativeId(entity);
        if (nativeId < 0) return;
        BlockPos position = entity.blockPosition();
        int sectionX = SectionPos.blockToSectionCoord(position.getX());
        int sectionY = SectionPos.blockToSectionCoord(position.getY());
        int sectionZ = SectionPos.blockToSectionCoord(position.getZ());
        FFMBackend.updateEntitySection(nativeContext, nativeId, sectionX, sectionY, sectionZ);
    }

    synchronized void removeEntity(Entity entity) {
        int nativeId = ids.getNativeId(entity);
        if (nativeId < 0) return;
        FFMBackend.removeEntity(nativeContext, nativeId);
        ids.removeEntity(entity);
        membershipRevision++;
        teams[nativeId] = null;
        bodies.retire(entity);
    }

    synchronized int[] hardCollisionIds(Entity source, AABB scan) {
        addEntity(source);
        if (scan.getSize() < 1.0E-7) {
            return EMPTY_IDS;
        }
        FFMBackend.QueryResult result = FFMBackend.queryHard(
                nativeContext,
                scan.inflate(1.0E-7),
                ids.getNativeId(source),
                VanillaMethodDetector.usesVanillaCanCollideWith(source),
                ids.nativeIdCapacity()
        );
        if (result.size() == 0) {
            return EMPTY_IDS;
        }
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
        return count == 0
                ? EMPTY_IDS
                : (count == matches.length ? matches : Arrays.copyOf(matches, count));
    }

    synchronized void addHardCubes(int[] hardIds, NativeShapeBatch shapes) {
        for (int nativeId : hardIds) {
            Entity target = ids.getEntity(nativeId);
            if (target == null) {
                throw new IllegalStateException("Native hard collision cube requested unknown entity " + nativeId);
            }
            shapes.addCube(bodies.movementRow(bodies.bindBody(target)));
        }
    }

    /** EntitySectionStorage.getEntities via the native index. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    synchronized void getEntities(EntityTypeTest type, AABB box, AbortableIterationConsumer consumer) {
        FFMBackend.QueryResult result = FFMBackend.queryEntities(nativeContext, box, ids.nativeIdCapacity());
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
        FFMBackend.QueryResult result = FFMBackend.queryEntities(nativeContext, box, ids.nativeIdCapacity());
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

    // EntityMovementMixin already owns vanilla's caller (Entity.collide), so nothing in this repository reaches this; it stays as the entry for code that still calls Level.getEntityCollisions directly.
    synchronized List<VoxelShape> getEntityCollisions(Entity entity, AABB box) {
        if (entity != null) {
            addEntity(entity);
        }
        if (box.getSize() < 1.0E-7) {
            return List.of();
        }
        int excludedNativeId = entity == null ? -1 : ids.getNativeId(entity);
        FFMBackend.QueryResult result = FFMBackend.queryHard(
                nativeContext,
                box.inflate(1.0E-7),
                excludedNativeId,
                entity == null || VanillaMethodDetector.usesVanillaCanCollideWith(entity),
                ids.nativeIdCapacity()
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
            if (entity == null ? !target.canBeCollidedWith(null) : !entity.canCollideWith(target)) {
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
            boolean sourceUsesVanillaDoPush
    ) {
        // Ordinary miss retries share an epoch; a changed world restarts with a fresh one.
        long queryEpoch = ++pushQueryEpoch;
        AABB sourceBounds = source.getBoundingBox();
        int sourceTeamId = assignTeamId(sourceTeam);
        int sourceRuleCode = collisionRuleCode(sourceRule);
        boolean sourceNativePushEligible = sourceUsesVanillaDoPush
                && VanillaMethodDetector.allowsDeferredVelocityWrites(source);
        while (true) {
            invalidateStalePushEligibility();
            int sourceNativeId = ids.getNativeId(source);
            if (sourceNativeId >= 0) bodies.bindBody(source);
            FFMBackend.QueryResult result = FFMBackend.queryPushable(
                    nativeContext,
                    sourceBounds,
                    sourceNativeId,
                    sourceTeamId,
                    sourceRuleCode,
                    sourceNativePushEligible,
                    queryEpoch,
                    ids.nativeIdCapacity()
            );
            if (!result.metadataRequired()) {
                return result;
            }
            int count = result.size();
            int[] requests = metadataSnapshot(result);
            try {
                for (int index = 0; index < count; index++) {
                    int nativeId = requests[index * 2];
                    Entity target = requireEntity(nativeId);
                    if (!refreshCandidateMetadata(nativeId, target, requests[index * 2 + 1], queryEpoch)) {
                        // A real tracking/state callback changed the query; discard its remaining old IDs.
                        queryEpoch = ++pushQueryEpoch;
                        break;
                    }
                }
            } finally {
                metadataQuerySnapshots.addFirst(requests);
            }
        }
    }

    private int[] metadataSnapshot(FFMBackend.QueryResult result) {
        int required = result.size() * 2;
        int[] requests = metadataQuerySnapshots.pollFirst();
        if (requests == null || requests.length < required) {
            int capacity = 16;
            while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
            requests = new int[capacity];
        }
        for (int index = 0; index < result.size(); index++) {
            requests[index * 2] = result.get(index);
            requests[index * 2 + 1] = result.requiredFields(index);
        }
        return requests;
    }

    synchronized PushBatch collectPushable(LivingEntity source, PlayerTeam sourceTeam,
                                            Team.CollisionRule sourceRule, boolean sourceUsesVanillaDoPush) {
        FFMBackend.QueryResult result = queryPushable(source, sourceTeam, sourceRule, sourceUsesVanillaDoPush);
        PushBatch batch = batchPool.pollFirst();
        if (batch == null) {
            batch = new PushBatch(nativeContext, bodies, this::recycle);
        }
        try {
            batch.prepare(result);
            return batch;
        } catch (RuntimeException failure) {
            batch.close();
            throw failure;
        }
    }

    private synchronized void recycle(PushBatch batch) {
        batchPool.addFirst(batch);
    }

    private boolean refreshCandidateMetadata(int nativeId, Entity entity, int requiredFields, long queryEpoch) {
        ensureSemanticCapacity(nativeId + 1);
        long membership = membershipRevision;
        if ((requiredFields & METADATA_STATE) != 0) {
            CollisionCacheState state = (CollisionCacheState) entity;
            long entityRevision = state.entityCollisionOptimizer$collisionRevision();
            long blockRevision = CollisionCacheEpochs.blockRevision();
            boolean selectable = selectableEntityRevisions[nativeId] == entityRevision
                    && selectableBlockRevisions[nativeId] == blockRevision
                    ? selectableValues[nativeId] != 0
                    : !entity.isRemoved() && !entity.isSpectator()
                            && state.entityCollisionOptimizer$isPushableCached();
            boolean passenger = entity.isPassenger();
            boolean hardCollidable = !entity.isRemoved() && !entity.isSpectator()
                    && !VanillaMethodDetector.usesVanillaCanBeCollidedWith(entity);
            // isPushable can pump chunk tasks. Never publish its old result into a retired/reused slot.
            if (membership != membershipRevision
                    || entityRevision != state.entityCollisionOptimizer$collisionRevision()
                    || blockRevision != CollisionCacheEpochs.blockRevision()) return false;
            selectableValues[nativeId] = (byte) (selectable ? 1 : 0);
            selectableEntityRevisions[nativeId] = entityRevision;
            selectableBlockRevisions[nativeId] = blockRevision;
            FFMBackend.updateEntityState(nativeContext, nativeId, selectable, passenger,
                    VanillaMethodDetector.usesVanillaEntityPush(entity),
                    VanillaMethodDetector.allowsDeferredVelocityWrites(entity),
                    bodies.bindBody(entity), hardCollidable);
        }
        if ((requiredFields & METADATA_TEAM) != 0 && selectableValues[nativeId] != 0) {
            long teamRevision = CollisionCacheEpochs.teamRevision();
            boolean vanillaTeam = VanillaMethodDetector.usesVanillaGetTeam(entity);
            PlayerTeam team = vanillaTeam && teamRevisions[nativeId] == teamRevision
                    ? teams[nativeId] : entity.getTeam();
            if (membership != membershipRevision || teamRevision != CollisionCacheEpochs.teamRevision()) return false;
            if (vanillaTeam) {
                teams[nativeId] = team;
                teamRevisions[nativeId] = teamRevision;
            }
            // Team-only refresh never evaluates pushability or reads the entity's block state.
            FFMBackend.updateEntityTeam(nativeContext, nativeId, assignTeamId(team),
                    collisionRuleCode(team == null ? Team.CollisionRule.ALWAYS : team.getCollisionRule()),
                    queryEpoch);
        }
        return true;
    }

    /* setblock() will increment the block revision, causing the selectable values to be invalidated. Team values the same */
    private void invalidateStalePushEligibility() {
        long blockRevision = CollisionCacheEpochs.blockRevision();
        long teamRevision = CollisionCacheEpochs.teamRevision();
        int fieldsToInvalidate = 0;
        if (nativeBlockRevision != blockRevision) {
            nativeBlockRevision = blockRevision;
            fieldsToInvalidate |= METADATA_STATE;
        }
        if (nativeTeamRevision != teamRevision) {
            nativeTeamRevision = teamRevision;
            fieldsToInvalidate |= METADATA_TEAM;
        }
        if (fieldsToInvalidate != 0) {
            FFMBackend.invalidatePushEligibilityCacheFields(nativeContext, fieldsToInvalidate);
        }
    }

    private int assignTeamId(PlayerTeam team) {
        if (team == null) {
            return -1;
        }
        Integer existing = teamIds.get(team);
        if (existing != null) {
            return existing;
        }
        int teamId = teamIds.size();
        teamIds.put(team, teamId);
        return teamId;
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

    private static int collisionRuleCode(Team.CollisionRule rule) {
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

package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.CollisionBodyAccess;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.function.Predicate;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Level-owned body slots with leases independent of persistent spatial membership. */
public final class CollisionStateTable implements AutoCloseable {
    public static final int STRIDE_BYTES = 80, VERSION_OFFSET = 40, STATE_OFFSET = 48,
            ROOT_OFFSET = 52, SYNC_OFFSET = 56, Y_OFFSET = 64, POSITION_VERSION_OFFSET = 72;
    private static final int VELOCITY_OFFSET = 2 * Double.BYTES;
    private final IdentityHashMap<Entity, Integer> slots = new IdentityHashMap<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private Entity[] entities = new Entity[0];
    private Vec3[] velocities = new Vec3[0];
    private long[] velocityVersions = new long[0];
    private Vec3[] positions = new Vec3[0];
    private long[] positionVersions = new long[0];
    private boolean[] bound = new boolean[0];
    private final CollisionPushStates pushStates = new CollisionPushStates(this);
    private final CollisionBounds bounds = new CollisionBounds();
    private Arena arena;
    private MemorySegment memory = MemorySegment.NULL;
    private int size, borrowers;
    private final IdentityHashMap<Entity, Boolean> retired = new IdentityHashMap<>();

    int slot(Entity entity) {
        retired.remove(entity);
        Integer existing = slots.get(entity);
        int slot;
        if (existing != null) slot = existing;
        else {
            slot = free.isEmpty() ? size++ : free.removeFirst();
            ensureCapacity(size);
            entities[slot] = entity;
            slots.put(entity, slot);
        }
        // Query metadata must never publish an unbound body slot. Rebinding also handles level transfer.
        ((CollisionBodyAccess) entity).eco$bindBody(this, slot);
        return slot;
    }

    Entity entity(int slot) {
        if (slot < 0 || slot >= size || entities[slot] == null) {
            throw new IllegalStateException("Unknown collision body slot " + slot);
        }
        return entities[slot];
    }

    void borrow() { borrowers++; }
    void release() {
        if (--borrowers < 0) throw new IllegalStateException("Unbalanced collision table lease");
        if (borrowers == 0 && !retired.isEmpty()) {
            var iterator = retired.keySet().iterator();
            while (iterator.hasNext()) {
                Entity entity = iterator.next();
                iterator.remove();
                retire(entity);
            }
        }
    }

    void retire(Entity entity) {
        Integer slot = slots.get(entity);
        if (slot == null) return;
        if (borrowers != 0) { retired.put(entity, true); return; }
        ((CollisionBodyAccess) entity).eco$detachBody(this, slot);
        slots.remove(entity);
        entities[slot] = null;
        velocities[slot] = null;
        positions[slot] = null;
        bounds.forget(slot);
        free.addLast(slot);
    }

    void prune(Predicate<Entity> live) {
        // A reentrant query/frame change must not recycle an outer batch's candidate IDs.
        if (borrowers != 0) return;
        for (int slot = 0; slot < size; slot++) {
            Entity entity = entities[slot];
            if (entity != null && !live.test(entity)) {
                ((CollisionBodyAccess) entity).eco$detachBody(this, slot);
                slots.remove(entity);
                entities[slot] = null;
                velocities[slot] = null;
                positions[slot] = null;
                bounds.forget(slot);
                free.addLast(slot);
            }
        }
    }

    /** Position publication boundary, also used by teleports and raw vanilla stores. */
    public void position(int slot, Vec3 position) {
        long offset = (long) slot * STRIDE_BYTES;
        memory.set(JAVA_DOUBLE, offset, position.x);
        memory.set(JAVA_DOUBLE, offset + Double.BYTES, position.z);
        memory.set(JAVA_DOUBLE, offset + Y_OFFSET, position.y);
        long version = memory.get(JAVA_LONG, offset + POSITION_VERSION_OFFSET) + 1;
        memory.set(JAVA_LONG, offset + POSITION_VERSION_OFFSET, version);
        positions[slot] = position;
        positionVersions[slot] = version;
    }

    public Vec3 position(int slot) {
        long offset = (long) slot * STRIDE_BYTES;
        long version = memory.get(JAVA_LONG, offset + POSITION_VERSION_OFFSET);
        if (positions[slot] != null && positionVersions[slot] == version) return positions[slot];
        Vec3 value = new Vec3(memory.get(JAVA_DOUBLE, offset), memory.get(JAVA_DOUBLE, offset + Y_OFFSET),
                memory.get(JAVA_DOUBLE, offset + Double.BYTES));
        positions[slot] = value;
        positionVersions[slot] = version;
        return value;
    }

    /** Borrow only for a synchronous native call; never retain across Java world callbacks or growth. */
    public MemorySegment row(int slot) { return memory.asSlice((long) slot * STRIDE_BYTES, STRIDE_BYTES); }

    public net.minecraft.world.phys.AABB bounds(int slot) { return bounds.get(slot); }
    public void bounds(int slot, net.minecraft.world.phys.AABB value) { bounds.set(slot, value); }
    public MemorySegment movementRow(int slot) { return bounds.row(slot); }

    /** All Java writes reach the same authoritative row; this is a raw field store, without extra guards. */
    public void velocity(int slot, Vec3 velocity) {
        long offset = (long) slot * STRIDE_BYTES;
        memory.set(JAVA_DOUBLE, offset + 2L * Double.BYTES, velocity.x);
        memory.set(JAVA_DOUBLE, offset + 3L * Double.BYTES, velocity.y);
        memory.set(JAVA_DOUBLE, offset + 4L * Double.BYTES, velocity.z);
        long version = memory.get(JAVA_LONG, offset + VERSION_OFFSET) + 1;
        memory.set(JAVA_LONG, offset + VERSION_OFFSET, version);
        velocityVersions[slot] = version;
        velocities[slot] = velocity;
    }

    public boolean needsSync(int slot) {
        return memory.get(JAVA_INT, (long) slot * STRIDE_BYTES + SYNC_OFFSET) != 0;
    }

    public void needsSync(int slot, boolean value) {
        memory.set(JAVA_INT, (long) slot * STRIDE_BYTES + SYNC_OFFSET, value ? 1 : 0);
    }

    public void bind(int slot) { bound[slot] = true; pushStates.invalidate(slot); }
    public void unbind(int slot) { bound[slot] = false; }
    boolean bound(int slot) { return bound[slot]; }
    int size() { return size; }
    public void invalidatePushState(int slot) { pushStates.invalidate(slot); }
    void refreshPushStates() { pushStates.refresh(); }

    /** Returns a persistent source row when its lifetime is covered by the current batch lease. */
    int sourceSlot(Entity entity) {
        refreshPushStates();
        Integer slot = slots.get(entity);
        return slot != null && bound[slot] ? slot : -1;
    }

    /** Materializes a source that was already removed before the batch began. */
    void snapshotDetachedSource(Entity entity, MemorySegment destination) {
        if (!destination.isNative() || destination.isReadOnly()
                || destination.byteSize() < STRIDE_BYTES) {
            throw new IllegalArgumentException("Invalid source collision row");
        }
        destination.fill((byte) 0);
        CollisionBodyAccess access = (CollisionBodyAccess) entity;
        Vec3 position = access.eco$readPosition();
        Vec3 velocity = access.eco$readVelocity();
        destination.set(JAVA_DOUBLE, 0, position.x);
        destination.set(JAVA_DOUBLE, Double.BYTES, position.z);
        destination.set(JAVA_DOUBLE, VELOCITY_OFFSET, velocity.x);
        destination.set(JAVA_DOUBLE, VELOCITY_OFFSET + Double.BYTES, velocity.y);
        destination.set(JAVA_DOUBLE, VELOCITY_OFFSET + 2L * Double.BYTES, velocity.z);
        int state = ((CollisionCacheState) entity).entityCollisionOptimizer$pushState()
                | (entity.noPhysics ? CollisionCacheState.NO_PHYSICS : 0);
        destination.set(JAVA_INT, STATE_OFFSET, state);
        Entity root = entity.getRootVehicle();
        Integer rootSlot = slots.get(root);
        destination.set(JAVA_INT, ROOT_OFFSET,
                rootSlot != null && bound[rootSlot] ? rootSlot : -1);
        destination.set(JAVA_INT, SYNC_OFFSET, access.eco$readNeedsSync() ? 1 : 0);
        destination.set(JAVA_DOUBLE, Y_OFFSET, position.y);
    }

    /** Publishes fields mutated on the detached source's batch-local row. */
    void publishDetachedSource(Entity entity, MemorySegment source) {
        CollisionBodyAccess access = (CollisionBodyAccess) entity;
        if (source.get(JAVA_LONG, VERSION_OFFSET) != 0) {
            access.eco$writeVelocity(new Vec3(
                    source.get(JAVA_DOUBLE, VELOCITY_OFFSET),
                    source.get(JAVA_DOUBLE, VELOCITY_OFFSET + Double.BYTES),
                    source.get(JAVA_DOUBLE, VELOCITY_OFFSET + 2L * Double.BYTES)
            ));
        }
        boolean needsSync = source.get(JAVA_INT, SYNC_OFFSET) != 0;
        if (access.eco$readNeedsSync() != needsSync) access.eco$writeNeedsSync(needsSync);
    }

    /** Materialize at most once between writes, on demand, never once per collision pair. */
    public Vec3 velocity(int slot) {
        Vec3 cached = velocities[slot];
        long offset = (long) slot * STRIDE_BYTES;
        long version = memory.get(JAVA_LONG, offset + VERSION_OFFSET);
        if (cached != null && velocityVersions[slot] == version) return cached;
        Vec3 value = new Vec3(memory.get(JAVA_DOUBLE, offset + 2L * Double.BYTES),
                memory.get(JAVA_DOUBLE, offset + 3L * Double.BYTES),
                memory.get(JAVA_DOUBLE, offset + 4L * Double.BYTES));
        velocities[slot] = value;
        velocityVersions[slot] = version;
        return value;
    }

    MemorySegment memory() { return memory; }
    int capacity() { return entities.length; }

    private void ensureCapacity(int required) {
        if (required <= entities.length) return;
        int capacity = Math.max(required, entities.length + (entities.length >> 1) + 256);
        bounds.capacity(capacity);
        Arena nextArena = Arena.ofShared();
        MemorySegment next;
        try {
            next = nextArena.allocate((long) capacity * STRIDE_BYTES, Double.BYTES);
            if (arena != null) MemorySegment.copy(memory, 0, next, 0, memory.byteSize());
        } catch (RuntimeException | Error failure) {
            nextArena.close();
            throw failure;
        }
        if (arena != null) arena.close();
        arena = nextArena;
        memory = next;
        entities = Arrays.copyOf(entities, capacity);
        velocities = Arrays.copyOf(velocities, capacity);
        velocityVersions = Arrays.copyOf(velocityVersions, capacity);
        positions = Arrays.copyOf(positions, capacity);
        positionVersions = Arrays.copyOf(positionVersions, capacity);
        bound = Arrays.copyOf(bound, capacity);
    }

    /** Release ownership while retaining allocation for an explicit disable/re-enable cycle. */
    void clear() {
        if (borrowers != 0) throw new IllegalStateException("Clearing a borrowed collision table");
        for (int slot = 0; slot < size; slot++) {
            if (entities[slot] != null) ((CollisionBodyAccess) entities[slot]).eco$detachBody(this, slot);
        }
        slots.clear();
        retired.clear();
        free.clear();
        Arrays.fill(entities, 0, size, null);
        Arrays.fill(velocities, 0, size, null);
        Arrays.fill(positions, 0, size, null);
        size = 0;
        pushStates.clear();
        bounds.clear();
    }

    @Override public void close() {
        clear();
        if (arena != null) arena.close();
        arena = null;
        memory = MemorySegment.NULL;
        entities = new Entity[0];
        velocities = new Vec3[0];
        velocityVersions = new long[0];
        positions = new Vec3[0];
        positionVersions = new long[0];
        bound = new boolean[0];
    }
}

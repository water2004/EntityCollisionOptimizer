package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** A level-local, reentrant-safe snapshot; native scratch buffers never escape into the push loop. */
public final class PushBatch implements AutoCloseable {
    private final LevelCollisionFrame owner;
    private final FFMBackend.Context context;
    private final CollisionStateTable bodies;
    private final Arena sourceArena = Arena.ofShared();
    private final MemorySegment detachedSourceBody = sourceArena.allocate(
            CollisionStateTable.STRIDE_BYTES,
            Double.BYTES
    );
    private int[] bodySlots = new int[0];
    private int[] nativePushFlags = new int[0];
    private int size;
    private int pushableCount;
    private int nonPassengerCount;
    private boolean borrowed;

    PushBatch(LevelCollisionFrame owner, FFMBackend.Context context, CollisionStateTable bodies) {
        this.owner = owner;
        this.context = context;
        this.bodies = bodies;
    }

    void prepare(FFMBackend.QueryResult result, PersistentEntityIds ids, LivingEntity source) {
        borrowed = true;
        bodies.borrow();
        size = result.size();
        pushableCount = result.pushableCount();
        nonPassengerCount = result.nonPassengerCount();
        if (size > bodySlots.length) {
            int capacity = Math.max(size, bodySlots.length + (bodySlots.length >> 1) + 16);
            bodySlots = new int[capacity];
            nativePushFlags = new int[capacity];
        }
        result.copyPushCandidatesTo(bodySlots, nativePushFlags);
        // Only final candidates need body slots; pin them before any push callback can retire IDs.
        if (size != 0 && ids.contains(source)) bodies.bindBody(source);
        // Resolve once per indexed entity, then snapshot slots before callbacks can remove/reuse IDs.
        int[] bodySlotsByNativeId = ids.bodySlotsByNativeId(bodies);
        for (int index = 0; index < size; index++) {
            int nativeId = bodySlots[index];
            int bodySlot = bodySlotsByNativeId[nativeId];
            if (bodySlot < 0) {
                bodySlot = bodies.bindBody(ids.getEntity(nativeId));
                bodySlotsByNativeId[nativeId] = bodySlot;
            }
            bodySlots[index] = bodySlot;
        }
    }

    public Entity target(int index) { return bodies.entity(bodySlots[index]); }
    public int size() { return size; }
    public int pushableCount() { return pushableCount; }
    public int nonPassengerCount() { return nonPassengerCount; }
    public boolean usesNativePush(int index) { return nativePushFlags[index] != 0; }

    /** Positions/velocities are live before entry; only changed semantic guards need refreshing. */
    public void applyNativeRun(LivingEntity source, int from, int to) {
        if (from < 0 || to < from || to > size) throw new IndexOutOfBoundsException("Invalid push run range");
        if (from == to) return;
        // Keep row resolution and the downcall mutually exclusive with table growth.
        // This protects batch storage, not arbitrary parallel entity-field accesses.
        synchronized (owner) {
            int sourceSlot = bodies.sourceSlot(source);
            MemorySegment sourceBody;
            if (sourceSlot >= 0) {
                sourceBody = bodies.row(sourceSlot);
            } else {
                bodies.snapshotDetachedSource(source, detachedSourceBody);
                sourceBody = detachedSourceBody;
            }
            FFMBackend.executePushRun(context, sourceBody, bodies.memory(), bodies.capacity(),
                    bodySlots, from, to - from);
            if (sourceSlot < 0) bodies.publishDetachedSource(source, sourceBody);
        }
    }

    @Override
    public void close() {
        // The existing pool lock must also cover lease release and deferred retirement.
        synchronized (owner) {
            if (borrowed) {
                bodies.release();
                size = 0;
                borrowed = false;
                owner.recycle(this);
            }
        }
    }

    void destroy() {
        if (borrowed) throw new IllegalStateException("Destroying a borrowed push batch");
        sourceArena.close();
    }
}

package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;


/** A level-local, reentrant-safe snapshot; native scratch buffers never escape into the push loop. */
public final class PushBatch implements AutoCloseable {
    private final FFMBackend.Context context;
    private final CollisionStateTable bodies;
    private final Consumer<PushBatch> recycler;
    private final Arena sourceArena = Arena.ofShared();
    private final MemorySegment detachedSourceBody = sourceArena.allocate(
            CollisionStateTable.STRIDE_BYTES,
            Double.BYTES
    );
    private int[] bodySlots = new int[0];
    private int[] nativeFlags = new int[0];
    private int size;
    private int pushableCount;
    private int nonPassengerCount;
    private boolean borrowed;

    PushBatch(FFMBackend.Context context, CollisionStateTable bodies, Consumer<PushBatch> recycler) {
        this.context = context;
        this.bodies = bodies;
        this.recycler = recycler;
    }

    void prepare(FFMBackend.QueryResult result) {
        borrowed = true;
        bodies.borrow();
        size = result.size();
        pushableCount = result.pushableCount();
        nonPassengerCount = result.nonPassengerCount();
        if (size > bodySlots.length) {
            int capacity = Math.max(size, bodySlots.length + (bodySlots.length >> 1) + 16);
            bodySlots = new int[capacity];
            nativeFlags = new int[capacity];
        }
        result.copyBodiesTo(bodySlots, nativeFlags);
    }

    public Entity target(int index) { return bodies.entity(bodySlots[index]); }
    public int size() { return size; }
    public int pushableCount() { return pushableCount; }
    public int nonPassengerCount() { return nonPassengerCount; }
    public boolean usesNativePush(int index) { return nativeFlags[index] != 0; }

    /** Positions/velocities are live before entry; only changed semantic guards need refreshing. */
    public void applyNativeRun(LivingEntity source, int from, int to) {
        if (from < 0 || to < from || to > size) throw new IndexOutOfBoundsException("Invalid push run range");
        if (from == to) return;
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

    @Override
    public void close() {
        if (borrowed) {
            bodies.release();
            size = 0;
            borrowed = false;
            recycler.accept(this);
        }
    }

    void destroy() {
        if (borrowed) throw new IllegalStateException("Destroying a borrowed push batch");
        sourceArena.close();
    }
}

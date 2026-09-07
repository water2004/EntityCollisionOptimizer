package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;

import java.util.Arrays;
import java.util.function.Consumer;

import static org.edtp.entitycollisionoptimizer.collision.CollisionCacheState.*;

/** A level-local, reentrant-safe snapshot; native scratch buffers never escape into the push loop. */
public final class PushBatch implements AutoCloseable {
    // Shared with push_run.cpp: [x, z, vx, vy, vz], slot zero is the source.
    private static final int STRIDE = 5;
    private static final int PUSH_TARGET = 1, PUSH_SOURCE = 2;
    private static final int SYNC = 1, WRITE_VELOCITY = 2;
    private final FFMBackend.Context context;
    private final Consumer<PushBatch> recycler;
    private Entity[] targets = new Entity[0];
    private int[] ids = new int[0];
    private int[] nativeFlags = new int[0];
    private double[] bodies = new double[0];
    private int[] actionsAndUpdates = new int[0];
    private int size;
    private int pushableCount;
    private int nonPassengerCount;
    private boolean borrowed;

    PushBatch(FFMBackend.Context context, Consumer<PushBatch> recycler) {
        this.context = context;
        this.recycler = recycler;
    }

    void prepare(FFMBackend.QueryResult result) {
        borrowed = true;
        size = result.size();
        pushableCount = result.pushableCount();
        nonPassengerCount = result.nonPassengerCount();
        if (size > targets.length) {
            int capacity = Math.max(size, targets.length + (targets.length >> 1) + 16);
            targets = new Entity[capacity];
            ids = new int[capacity];
            nativeFlags = new int[capacity];
            bodies = new double[(capacity + 1) * STRIDE];
            actionsAndUpdates = new int[capacity + 1];
        }
        result.copyTo(ids, nativeFlags);
    }

    int id(int index) { return ids[index]; }
    void target(int index, Entity target) { targets[index] = target; }
    public Entity target(int index) { return targets[index]; }
    public int size() { return size; }
    public int pushableCount() { return pushableCount; }
    public int nonPassengerCount() { return nonPassengerCount; }
    public boolean usesNativePush(int index) { return nativeFlags[index] != 0; }

    /** Gather after cramming and after any preceding special push callback, never at query time. */
    public void applyNativeRun(LivingEntity source, int from, int to) {
        if (from < 0 || to < from || to > size) throw new IndexOutOfBoundsException("Invalid push run range");
        if (from == to) return;
        capture(source, 0);
        actionsAndUpdates[0] = 0;
        // Ordinary vector pushes only mutate velocity/sync, so these source guards cannot change in the run.
        boolean sourceNoPhysics = source.noPhysics;
        int sourceState = ((CollisionCacheState) source).entityCollisionOptimizer$pushState();
        boolean sourcePushable = (sourceState & (PUSHABLE | VEHICLE)) == PUSHABLE;
        for (int i = from; i < to; i++) {
            Entity target = targets[i];
            int slot = i - from + 1;
            capture(target, slot);
            int targetState = ((CollisionCacheState) target).entityCollisionOptimizer$pushState();
            int actions = 0;
            if (!sourceNoPhysics && !target.noPhysics && (targetState & SLEEPING) == 0
                    && (((sourceState | targetState) & PASSENGER) == 0 || !target.isPassengerOfSameVehicle(source))) {
                if ((targetState & (PUSHABLE | VEHICLE)) == PUSHABLE) {
                    actions |= PUSH_TARGET;
                }
                if (sourcePushable) actions |= PUSH_SOURCE;
            }
            actionsAndUpdates[slot] = actions;
        }
        FFMBackend.executePushRun(context, bodies, actionsAndUpdates, to - from);
        // Finish every write before the next special push callback or returning to a caller.
        for (int i = from; i < to; i++) {
            commit(targets[i], i - from + 1);
        }
        commit(source, 0);
    }

    private void capture(Entity entity, int slot) {
        int offset = slot * STRIDE;
        Vec3 velocity = entity.getDeltaMovement();
        bodies[offset] = entity.getX();
        bodies[offset + 1] = entity.getZ();
        bodies[offset + 2] = velocity.x;
        bodies[offset + 3] = velocity.y;
        bodies[offset + 4] = velocity.z;
    }

    private void commit(Entity entity, int slot) {
        int updates = actionsAndUpdates[slot];
        if ((updates & WRITE_VELOCITY) != 0) {
            int offset = slot * STRIDE;
            entity.setDeltaMovement(new Vec3(bodies[offset + 2], bodies[offset + 3], bodies[offset + 4]));
        }
        if ((updates & SYNC) != 0) entity.needsSync = true;
    }

    @Override
    public void close() {
        if (borrowed) {
            Arrays.fill(targets, 0, size, null);
            size = 0;
            borrowed = false;
            recycler.accept(this);
        }
    }
}

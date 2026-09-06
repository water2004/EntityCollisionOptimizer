package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.collision.CollisionSleepingState;

import java.util.Arrays;
import java.util.function.Consumer;

/** A level-local, reentrant-safe snapshot; native scratch buffers never escape into the push loop. */
public final class PushBatch implements AutoCloseable {
    private final FFMBackend.Context context;
    private final Consumer<PushBatch> recycler;
    private Entity[] targets = new Entity[0];
    private int[] ids = new int[0];
    private int[] nativeFlags = new int[0];
    private double[] positions = new double[0];
    private double[] impulses = new double[0];
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
            positions = new double[capacity * 2];
            impulses = new double[capacity * 2];
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
        for (int i = from; i < to; i++) {
            int offset = (i - from) * 2;
            positions[offset] = targets[i].getX();
            positions[offset + 1] = targets[i].getZ();
        }
        FFMBackend.calculatePushImpulses(context, source.getX(), source.getZ(),
                positions, to - from, impulses);
        for (int i = from; i < to; i++) {
            int offset = (i - from) * 2;
            apply(source, targets[i], impulses[offset], impulses[offset + 1]);
        }
    }

    private static void apply(LivingEntity source, Entity target, double x, double z) {
        if (Double.isNaN(x) || source.noPhysics || target.noPhysics
                || target.isPassengerOfSameVehicle(source)
                || target instanceof LivingEntity && ((CollisionSleepingState) target).entityCollisionOptimizer$isSleepingCached()) {
            return;
        }
        // LivingEntity.doPush calls target.push(source): the target receives its impulse first.
        if (!target.isVehicle() && ((CollisionCacheState) target).entityCollisionOptimizer$isPushableCached()) {
            target.push(-x, 0.0, -z);
        }
        if (!source.isVehicle() && ((CollisionCacheState) source).entityCollisionOptimizer$isPushableCached()) {
            source.push(x, 0.0, z);
        }
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

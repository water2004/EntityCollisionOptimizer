package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.collision.CollisionBodyAccess;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

/** Numerical movement transaction. Proposed positions are invisible until vanilla publishes them. */
public final class NativeMovement implements AutoCloseable {
    private static final ThreadLocal<ArrayDeque<MemorySegment>> POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private final MemorySegment packet;
    private final Entity entity;
    private boolean closed;

    public NativeMovement(Entity entity, Vec3 requested, AABB box, boolean stepping) {
        this.entity = entity;
        MemorySegment available = POOL.get().pollFirst();
        packet = available == null ? Arena.ofAuto().allocate(34L * Double.BYTES, Double.BYTES) : available;
        MemorySegment bounds = stepping ? ((CollisionBodyAccess) entity).eco$movementBody() : MemorySegment.NULL;
        if (bounds.equals(MemorySegment.NULL)) box(0, box == null ? entity.getBoundingBox() : box);
        vector(6, requested);
        vector(9, Vec3.ZERO);
        set(30, 0); set(31, 0); set(32, 0); set(33, stepping ? 1 : 0);
        try {
            FFMBackend.prepareMovement(bounds, packet);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /** Sample step eligibility after the entity query, before block-shape callbacks. */
    public void steppingState() {
        set(30, entity.maxUpStep());
        set(31, entity.onGround() ? 1 : 0);
    }

    public void solve(NativeShapeBatch shapes, boolean step) {
        if (closed) throw new IllegalStateException("Closed movement transaction");
        // Obtain the row after world callbacks, which can grow or transfer a table.
        MemorySegment body = entity == null ? MemorySegment.NULL : ((CollisionBodyAccess) entity).eco$positionBody();
        if (body.equals(MemorySegment.NULL) && entity != null) vector(9, entity.position());
        try {
            FFMBackend.solveMovement(body, packet, shapes.memory(), shapes.size(), step ? 1 : 0);
            solvePositionWrites = POSITION_WRITES.get();
        } finally {
            java.lang.ref.Reference.reachabilityFence(shapes);
        }
    }

    public boolean needsStep() { return get(32) != 0; }
    public AABB stepScan() { return new AABB(get(24), get(25), get(26), get(27), get(28), get(29)); }
    public Vec3 displacement() { return vector(12); }

    /** Counts logical position writes, so a mismatch can tell "entity moved" from "stale row". */
    public static final java.util.concurrent.atomic.AtomicLong POSITION_WRITES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MISMATCH_REPORTS =
            new java.util.concurrent.atomic.AtomicLong();
    private long solvePositionWrites;

    /**
     * Read the proposed destination only at vanilla's publication boundary.
     *
     * Returns null when the entity's position no longer matches the position the solve
     * started from, which means the native destination cannot be published. Vanilla
     * computes the destination as currentPosition + collision displacement, and the
     * displacement was produced for the earlier position, so the caller falls back to
     * the untouched vanilla operation instead of aborting the server tick.
     */
    public Vec3 destination(Vec3 observedFrom) {
        if (closed) throw new IllegalStateException("Closed movement transaction");
        if (Double.doubleToRawLongBits(get(9)) != Double.doubleToRawLongBits(observedFrom.x)
                || Double.doubleToRawLongBits(get(10)) != Double.doubleToRawLongBits(observedFrom.y)
                || Double.doubleToRawLongBits(get(11)) != Double.doubleToRawLongBits(observedFrom.z)) {
            reportPositionMismatch(observedFrom);
            return null;
        }
        return vector(15);
    }

    private void reportPositionMismatch(Vec3 observedFrom) {
        long reports = MISMATCH_REPORTS.getAndIncrement();
        if (reports >= 20 && reports % 1000 != 0) return;
        long writes = POSITION_WRITES.get();
        org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer.LOGGER.warn(
                "ECO_MOVEMENT_POSITION_MISMATCH report={} entity={} id={} solved={},{},{} observed={},{},{}"
                        + " solvedRaw={},{},{} observedRaw={},{},{} displacement={},{},{} step={}"
                        + " positionRewrittenBeforePublication={} writes={} witness={}",
                reports,
                entity == null ? "null" : entity.getType(), entity == null ? -1 : entity.getId(),
                get(9), get(10), get(11), observedFrom.x, observedFrom.y, observedFrom.z,
                Double.doubleToRawLongBits(get(9)), Double.doubleToRawLongBits(get(10)),
                Double.doubleToRawLongBits(get(11)),
                Double.doubleToRawLongBits(observedFrom.x), Double.doubleToRawLongBits(observedFrom.y),
                Double.doubleToRawLongBits(observedFrom.z),
                get(12), get(13), get(14), get(33) != 0,
                writes != solvePositionWrites, writes,
                entity == null ? "null" : String.valueOf(((CollisionBodyAccess) entity).eco$positionBody().address()));
    }

    private void box(int index, AABB box) {
        set(index, box.minX); set(index + 1, box.minY); set(index + 2, box.minZ);
        set(index + 3, box.maxX); set(index + 4, box.maxY); set(index + 5, box.maxZ);
    }
    private Vec3 vector(int index) { return new Vec3(get(index), get(index + 1), get(index + 2)); }
    private void vector(int index, Vec3 value) { set(index, value.x); set(index + 1, value.y); set(index + 2, value.z); }
    private void set(int index, double value) { packet.set(JAVA_DOUBLE, (long) index * 8, value); }
    private double get(int index) { return packet.get(JAVA_DOUBLE, (long) index * 8); }
    @Override public void close() {
        if (closed) throw new IllegalStateException("Movement transaction closed twice");
        closed = true;
        POOL.get().addFirst(packet);
    }

}

package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

/** Numerical collision packet; vanilla retains position publication and integration. */
public final class NativeMovement implements AutoCloseable {
    private static final ThreadLocal<ArrayDeque<MemorySegment>> POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private final MemorySegment packet;
    private final Entity entity;
    private final AABB bounds;
    private boolean closed;

    public NativeMovement(Entity entity, Vec3 requested, AABB box, boolean stepping) {
        this.entity = entity;
        bounds = box == null ? entity.getBoundingBox() : box;
        MemorySegment available = POOL.get().pollFirst();
        packet = available == null ? Arena.ofAuto().allocate(28L * Double.BYTES, Double.BYTES) : available;
        box(0, bounds);
        vector(6, requested);
        set(24, 0); set(25, 0); set(26, 0); set(27, stepping ? 1 : 0);
        try {
            FFMBackend.prepareMovement(packet);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /** Sample step eligibility after the entity query, before block-shape callbacks. */
    public void steppingState() {
        set(24, entity.maxUpStep());
        set(25, entity.onGround() ? 1 : 0);
    }

    public void solve(NativeShapeBatch shapes, boolean step) {
        if (closed) throw new IllegalStateException("Closed movement transaction");
        try {
            FFMBackend.solveMovement(packet, shapes.memory(), shapes.size(), step ? 1 : 0);
        } finally {
            java.lang.ref.Reference.reachabilityFence(shapes);
        }
    }

    public boolean needsStep() { return get(26) != 0; }
    public AABB sourceBounds() { return bounds; }
    public AABB stepScan() { return new AABB(get(18), get(19), get(20), get(21), get(22), get(23)); }
    public Vec3 displacement() { return vector(9); }

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

package org.edtp.entitycollisionoptimizer.collision.blocks;

import it.unimi.dsi.fastutil.floats.FloatArraySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;

/** Ordered colliders: block coordinates, existing bounds, or live non-block shapes. */
public final class MovementColliders {
    // A null entry denotes the full block at the corresponding packed position.
    private static final Object[] EMPTY_COLLIDERS = new Object[0];
    private static final long[] EMPTY_POSITIONS = new long[0];
    private Object[] colliders = EMPTY_COLLIDERS;
    private long[] blockPositions = EMPTY_POSITIONS;
    private int size;

    public void add(VoxelShape shape) {
        reserve();
        colliders[size++] = shape;
    }

    public void addBlock(int x, int y, int z) {
        reserve();
        colliders[size] = null;
        blockPositions[size++] = BlockPos.asLong(x, y, z);
    }

    public void addBounds(AABB bounds) {
        // Within the unit cube Shapes.create can snap coordinates or retain internal planes.
        if (bounds.maxX - bounds.minX < 1e-7 || bounds.maxY - bounds.minY < 1e-7
                || bounds.maxZ - bounds.minZ < 1e-7
                || (bounds.minX >= -1e-7 && bounds.maxX <= 1.0000001
                && bounds.minY >= -1e-7 && bounds.maxY <= 1.0000001
                && bounds.minZ >= -1e-7 && bounds.maxZ <= 1.0000001)) {
            add(Shapes.create(bounds));
        } else {
            reserve();
            colliders[size++] = bounds;
        }
    }

    private void reserve() {
        if (size == colliders.length) {
            int capacity = Math.max(8, size * 2);
            colliders = Arrays.copyOf(colliders, capacity);
            blockPositions = Arrays.copyOf(blockPositions, capacity);
        }
    }

    public void clear() {
        // Appends overwrite their slots; this query-local storage is not retained in a pool.
        size = 0;
    }

    public Vec3 clip(Vec3 requested, AABB bounds) {
        if (size == 0) return requested;
        double x = 0.0, z = 0.0;
        double y = requested.y == 0.0 ? 0.0 : clipAxis(Direction.Axis.Y, bounds, 0.0, 0.0, 0.0, requested.y);
        // Vanilla's Y-first order, with constant axes at each hot-loop call site.
        if (Math.abs(requested.x) < Math.abs(requested.z)) {
            if (requested.z != 0.0) z = clipAxis(Direction.Axis.Z, bounds, 0.0, y, 0.0, requested.z);
            if (requested.x != 0.0) x = clipAxis(Direction.Axis.X, bounds, 0.0, y, z, requested.x);
        } else {
            if (requested.x != 0.0) x = clipAxis(Direction.Axis.X, bounds, 0.0, y, 0.0, requested.x);
            if (requested.z != 0.0) z = clipAxis(Direction.Axis.Z, bounds, x, y, 0.0, requested.z);
        }
        return new Vec3(x, y, z);
    }

    private double clipAxis(Direction.Axis axis, AABB bounds, double x, double y, double z, double distance) {
        // Add even positive zero, as vanilla's bounds.move(result) does on the first axis.
        double minX = bounds.minX + x, minY = bounds.minY + y, minZ = bounds.minZ + z;
        double maxX = bounds.maxX + x, maxY = bounds.maxY + y, maxZ = bounds.maxZ + z;
        AABB moved = null;
        for (int index = 0; index < size; index++) {
            if (Math.abs(distance) < 1e-7) return 0.0;
            Object collider = colliders[index];
            if (collider == null) {
                long pos = blockPositions[index];
                double blockX = BlockPos.getX(pos), blockY = BlockPos.getY(pos), blockZ = BlockPos.getZ(pos);
                distance = clipCube(axis, distance, minX, minY, minZ, maxX, maxY, maxZ,
                        blockX, blockY, blockZ, blockX + 1.0, blockY + 1.0, blockZ + 1.0);
            } else if (collider instanceof AABB cube) {
                distance = clipCube(axis, distance, minX, minY, minZ, maxX, maxY, maxZ,
                        cube.minX, cube.minY, cube.minZ, cube.maxX, cube.maxY, cube.maxZ);
            } else {
                VoxelShape shape = (VoxelShape) collider;
                if (moved == null) moved = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
                distance = shape.collide(axis, moved, distance);
            }
        }
        return distance;
    }

    private static double clipCube(Direction.Axis axis, double distance,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double cubeMinX, double cubeMinY, double cubeMinZ, double cubeMaxX, double cubeMaxY, double cubeMaxZ) {
        return switch (axis) {
            case X -> clipCube(distance, minX, maxX, minY, maxY, minZ, maxZ,
                    cubeMinX, cubeMaxX, cubeMinY, cubeMaxY, cubeMinZ, cubeMaxZ);
            case Y -> clipCube(distance, minY, maxY, minZ, maxZ, minX, maxX,
                    cubeMinY, cubeMaxY, cubeMinZ, cubeMaxZ, cubeMinX, cubeMaxX);
            case Z -> clipCube(distance, minZ, maxZ, minX, maxX, minY, maxY,
                    cubeMinZ, cubeMaxZ, cubeMinX, cubeMaxX, cubeMinY, cubeMaxY);
        };
    }

    private static double clipCube(double distance, double minA, double maxA,
            double minB, double maxB, double minC, double maxC,
            double cubeMinA, double cubeMaxA, double cubeMinB, double cubeMaxB, double cubeMinC, double cubeMaxC) {
        if (maxB - 1e-7 < cubeMinB || minB + 1e-7 >= cubeMaxB
                || maxC - 1e-7 < cubeMinC || minC + 1e-7 >= cubeMaxC) return distance;
        if (distance > 0.0) {
            double gap = cubeMinA - maxA;
            if (maxA - 1e-7 < cubeMinA && gap >= -1e-7) return Math.min(distance, gap);
        } else if (distance < 0.0) {
            double gap = cubeMaxA - minA;
            if (minA + 1e-7 >= cubeMaxA && gap <= 1e-7) return Math.max(distance, gap);
        }
        return distance;
    }

    public float[] stepHeights(AABB bounds, float maxStep, float clippedY) {
        var heights = new FloatArraySet(4);
        for (int index = 0; index < size; index++) {
            Object collider = colliders[index];
            if (collider == null) {
                double y = BlockPos.getY(blockPositions[index]);
                addHeight(heights, y, bounds.minY, maxStep, clippedY);
                addHeight(heights, y + 1.0, bounds.minY, maxStep, clippedY);
            } else if (collider instanceof AABB cube) {
                addHeight(heights, cube.minY, bounds.minY, maxStep, clippedY);
                addHeight(heights, cube.maxY, bounds.minY, maxStep, clippedY);
            } else {
                VoxelShape shape = (VoxelShape) collider;
                if (((SingleCellShape) shape).eco$isSingleCell()) {
                    addHeight(heights, shape.min(Direction.Axis.Y), bounds.minY, maxStep, clippedY);
                    addHeight(heights, shape.max(Direction.Axis.Y), bounds.minY, maxStep, clippedY);
                } else {
                    var coordinates = shape.getCoords(Direction.Axis.Y);
                    for (int coordinate = 0; coordinate < coordinates.size(); coordinate++) {
                        float height = (float) (coordinates.getDouble(coordinate) - bounds.minY);
                        if (height < 0.0F || height == clippedY) continue;
                        if (height > maxStep) break;
                        heights.add(height);
                    }
                }
            }
        }
        float[] result = heights.toFloatArray();
        Arrays.sort(result);
        return result;
    }

    private static void addHeight(FloatArraySet heights, double coordinate, double baseY, float maxStep, float clippedY) {
        float height = (float) (coordinate - baseY);
        if (height < 0.0F || height == clippedY || height > maxStep) return;
        heights.add(height);
    }
}

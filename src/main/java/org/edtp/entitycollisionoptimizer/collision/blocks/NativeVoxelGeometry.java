package org.edtp.entitycollisionoptimizer.collision.blocks;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

/** Immutable local geometry; offsets belong to queries, not to this cache. */
public final class NativeVoxelGeometry {
    public static MemorySegment create(VoxelShape shape, DiscreteVoxelShape grid) {
        int nx = grid.getXSize(), ny = grid.getYSize(), nz = grid.getZSize();
        long cells = Math.multiplyExact(Math.multiplyExact((long) nx, ny), nz);
        long bitsOffset = 16L + ((long) nx + ny + nz + 3) * Double.BYTES;
        MemorySegment memory = Arena.ofAuto().allocate(bitsOffset + ((cells + 63) / 64) * Long.BYTES, 8);
        memory.set(JAVA_INT, 0, nx);
        memory.set(JAVA_INT, 4, ny);
        memory.set(JAVA_INT, 8, nz);
        boolean empty = shape.isEmpty();
        memory.set(JAVA_INT, 12, (empty ? 1 : 0) | (shape instanceof CubeVoxelShape ? 2 : 0)
                | (!empty && nx == 1 && ny == 1 && nz == 1 ? 4 : 0));
        long offset = 16;
        for (Direction.Axis axis : Direction.Axis.values()) {
            var coordinates = shape.getCoords(axis);
            if (coordinates.size() != grid.getSize(axis) + 1) throw new IllegalStateException("Invalid voxel coordinate count");
            for (int i = 0; i < coordinates.size(); i++, offset += Double.BYTES) {
                memory.set(JAVA_DOUBLE, offset, coordinates.getDouble(i));
            }
        }
        long index = 0;
        for (int x = 0; x < nx; x++) for (int y = 0; y < ny; y++) for (int z = 0; z < nz; z++, index++) {
            if (!grid.isFull(x, y, z)) continue;
            long address = bitsOffset + (index >>> 6) * Long.BYTES;
            memory.set(JAVA_LONG, address, memory.get(JAVA_LONG, address) | (1L << (index & 63)));
        }
        return memory.asReadOnly();
    }

    private NativeVoxelGeometry() {}
}

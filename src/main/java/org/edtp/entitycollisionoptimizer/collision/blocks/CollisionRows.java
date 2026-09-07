package org.edtp.entitycollisionoptimizer.collision.blocks;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Palette-owned shared halo masks. Geometry is deliberately not cached here. */
public final class CollisionRows {
    private final MemorySegment memory = Arena.ofAuto().allocate(256L * 3 * 2, 8);
    private final long[] known = new long[4];

    public boolean known(int row) { return (known[row >>> 6] & (1L << (row & 63))) != 0; }
    public boolean knownRectangle(int minY, int maxY, int minZ, int maxZ) {
        long zMask = ((1L << (maxZ - minZ + 1)) - 1) << minZ;
        for (int y = minY; y <= maxY; y++) {
            long mask = zMask << ((y & 3) * 16);
            if ((known[y >>> 2] & mask) != mask) return false;
        }
        return true;
    }
    public void initialize(int row, long bits) {
        for (int plane = 0; plane < 3; plane++) set(row * 3 + plane, (int) (bits >>> (plane * 16)));
        known[row >>> 6] |= 1L << (row & 63);
    }
    public int get(int offset) { return memory.get(JAVA_SHORT, offset * 2L) & 0xffff; }
    private void set(int offset, int value) { memory.set(JAVA_SHORT, offset * 2L, (short) value); }
    public void update(int index, long flags) {
        int row = index >>> 4;
        if (!known(row)) return;
        int bit = 1 << (index & 15);
        for (int plane = 0; plane < 3; plane++) {
            int offset = row * 3 + plane, value = get(offset);
            set(offset, (flags & (1L << (plane * 16))) == 0 ? value & ~bit : value | bit);
        }
    }
    public MemorySegment memory() { return memory; }
}

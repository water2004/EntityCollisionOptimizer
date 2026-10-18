package org.edtp.entitycollisionoptimizer.collision.blocks;

/** Palette-owned shared halo masks. Geometry is deliberately not cached here. */
public final class CollisionRows {
    // Each row packs the interior, face and edge planes into consecutive 16-bit fields.
    private final long[] masks = new long[256];
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
        masks[row] = bits;
        known[row >>> 6] |= 1L << (row & 63);
    }
    public long masks(int row) { return masks[row]; }
    public void update(int index, long flags) {
        int row = index >>> 4;
        if (!known(row)) return;
        int bit = index & 15;
        long positions = 0x0001_0001_0001L << bit;
        masks[row] = (masks[row] & ~positions) | (flags << bit);
    }
}

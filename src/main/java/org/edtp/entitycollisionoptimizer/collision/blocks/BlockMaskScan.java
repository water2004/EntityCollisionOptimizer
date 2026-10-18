package org.edtp.entitycollisionoptimizer.collision.blocks;

import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/** Ordered palette-mask cursor. Shapes are resolved live by the caller, never cached here. */
public final class BlockMaskScan implements AutoCloseable {
    private static final ThreadLocal<ArrayDeque<Storage>> POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private final Storage storage;
    private boolean closed;

    public BlockMaskScan(Level level, AABB box) {
        Storage available = POOL.get().pollFirst();
        storage = available == null ? new Storage() : available;
        try {
            storage.prepare(level, box);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public boolean next() {
        if (closed) throw new IllegalStateException("Closed block mask scan");
        return storage.next();
    }

    public int x() { return storage.x; }
    public int y() { return storage.y; }
    public int z() { return storage.z; }
    public LevelChunkSection section() { return storage.section; }

    @Override public void close() {
        if (closed) throw new IllegalStateException("Block mask scan closed twice");
        closed = true;
        storage.clear();
        POOL.get().addFirst(storage);
    }

    // Progress belongs to the reentrant scratch lease too, keeping each query wrapper small.
    private static final class Storage {
        CollisionRows[] rows = new CollisionRows[0];
        LevelChunkSection[] sections = new LevelChunkSection[0];
        int minX, minY, minZ, maxX, maxY, maxZ, width, height;
        int sectionCount;
        int cursorX, cursorY, cursorZ;
        int baseX, rowIndex, edgesYZ, boundaryMask, remainingMask;
        CollisionRows sectionRows;
        LevelChunkSection section;
        int x, y, z;

        void capacity(int count) {
            if (count <= sections.length) return;
            int capacity = Math.max(count, sections.length + (sections.length >> 1) + 8);
            rows = new CollisionRows[capacity];
            sections = new LevelChunkSection[capacity];
        }

        void prepare(Level level, AABB box) {
            minX = Mth.floor(box.minX - 1e-7) - 1;
            minY = Mth.floor(box.minY - 1e-7) - 1;
            minZ = Mth.floor(box.minZ - 1e-7) - 1;
            maxX = Mth.floor(box.maxX + 1e-7) + 1;
            maxY = Mth.floor(box.maxY + 1e-7) + 1;
            maxZ = Mth.floor(box.maxZ + 1e-7) + 1;
            width = (maxX >> 4) - (minX >> 4) + 1;
            height = (maxY >> 4) - (minY >> 4) + 1;
            cursorX = minX;
            cursorY = minY;
            cursorZ = minZ;
            remainingMask = 0;
            int count = Math.multiplyExact(Math.multiplyExact(width, height), (maxZ >> 4) - (minZ >> 4) + 1);
            capacity(count);
            sectionCount = count;
            // Keep chunk acquisition and palette preparation before shape callbacks, as before.
            for (int sectionZ = minZ >> 4; sectionZ <= maxZ >> 4; sectionZ++) {
                for (int sectionX = minX >> 4; sectionX <= maxX >> 4; sectionX++) {
                    ChunkAccess chunk = (ChunkAccess) level.getChunkForCollisions(sectionX, sectionZ);
                    for (int sectionY = minY >> 4; sectionY <= maxY >> 4; sectionY++) {
                        int index = ((sectionZ - (minZ >> 4)) * height + sectionY - (minY >> 4)) * width
                                + sectionX - (minX >> 4);
                        LevelChunkSection section = null;
                        if (chunk != null) {
                            int sectionIndex = chunk.getSectionIndex(sectionY << 4);
                            if (sectionIndex >= 0 && sectionIndex < chunk.getSections().length) {
                                section = chunk.getSections()[sectionIndex];
                            }
                        }
                        sections[index] = section;
                        rows[index] = section == null ? null
                                : ((CollisionBlockMask) section.getStates()).entityCollisionOptimizer$collisionRows(
                                        Math.max(minY, sectionY << 4) & 15, Math.min(maxY, (sectionY << 4) + 15) & 15,
                                        Math.max(minZ, sectionZ << 4) & 15, Math.min(maxZ, (sectionZ << 4) + 15) & 15);
                    }
                }
            }
        }

        boolean next() {
            while (true) {
                if (remainingMask != 0) {
                    // Re-read live masks after each caller callback, including changes in this row.
                    long masks = sectionRows.masks(rowIndex);
                    int selected = (int) (masks >>> (edgesYZ * 16)) & 0xffff;
                    if (boundaryMask != 0) {
                        int outer = edgesYZ == 2 ? 0 : (int) (masks >>> ((edgesYZ + 1) * 16)) & 0xffff;
                        selected = (selected & ~boundaryMask) | (outer & boundaryMask);
                    }
                    selected &= remainingMask;
                    if (selected != 0) {
                        int bit = Integer.numberOfTrailingZeros(selected);
                        remainingMask &= -1 << (bit + 1);
                        x = baseX + bit;
                        y = cursorY;
                        z = cursorZ;
                        return true;
                    }
                    remainingMask = 0;
                }
                if (!nextRow()) return false;
            }
        }

        private boolean nextRow() {
            while (cursorZ <= maxZ) {
                if (cursorX > maxX) {
                    cursorX = minX;
                    if (++cursorY > maxY) {
                        cursorY = minY;
                        cursorZ++;
                    }
                    continue;
                }
                baseX = cursorX & ~15;
                int index = (((cursorZ >> 4) - (minZ >> 4)) * height
                        + (cursorY >> 4) - (minY >> 4)) * width + (cursorX >> 4) - (minX >> 4);
                int end = Math.min(maxX, baseX + 15);
                sectionRows = rows[index];
                section = sections[index];
                cursorX = end + 1;
                if (sectionRows == null) continue;
                edgesYZ = (cursorY == minY || cursorY == maxY ? 1 : 0)
                        + (cursorZ == minZ || cursorZ == maxZ ? 1 : 0);
                rowIndex = (cursorY & 15) << 4 | (cursorZ & 15);
                boundaryMask = (baseX == (minX & ~15) ? 1 << (minX & 15) : 0)
                        | (baseX == (maxX & ~15) ? 1 << (maxX & 15) : 0);
                remainingMask = (0xffff << (Math.max(minX, baseX) & 15)) & (0xffff >>> (15 - (end & 15)));
                return true;
            }
            return false;
        }

        void clear() {
            Arrays.fill(sections, 0, sectionCount, null);
            Arrays.fill(rows, 0, sectionCount, null);
            sectionCount = 0;
            sectionRows = null;
            section = null;
        }
    }
}

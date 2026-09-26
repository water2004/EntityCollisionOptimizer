package org.edtp.entitycollisionoptimizer.natives;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.collision.blocks.CollisionBlockMask;
import static java.lang.foreign.ValueLayout.*;

/** Leases palette masks and batches ordered candidates, without caching context-dependent shapes. */
public final class NativeBlockScan implements AutoCloseable {
    private static final int BATCH = 256;
    private static final ThreadLocal<ArrayDeque<Storage>> POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private final Storage storage;
    private int sectionCount;
    private boolean closed;

    private static final class Storage {
        final MemorySegment query = Arena.ofAuto().allocate(40, 8);
        final MemorySegment output = Arena.ofAuto().allocate(BATCH * 16, 8);
        MemorySegment pointers = MemorySegment.NULL;
        MemorySegment[] retained = new MemorySegment[0];
        LevelChunkSection[] sections = new LevelChunkSection[0];
        void capacity(int count) {
            if (count <= sections.length) return;
            int capacity = Math.max(count, sections.length + (sections.length >> 1) + 8);
            pointers = Arena.ofAuto().allocate(capacity * 8L, 8);
            retained = new MemorySegment[capacity];
            sections = new LevelChunkSection[capacity];
        }
    }

    public NativeBlockScan(Level level, AABB box) {
        Storage available = POOL.get().pollFirst();
        storage = available == null ? new Storage() : available;
        try {
            int minX = Mth.floor(box.minX - 1e-7) - 1, minY = Mth.floor(box.minY - 1e-7) - 1,
                    minZ = Mth.floor(box.minZ - 1e-7) - 1;
            int maxX = Mth.floor(box.maxX + 1e-7) + 1, maxY = Mth.floor(box.maxY + 1e-7) + 1,
                    maxZ = Mth.floor(box.maxZ + 1e-7) + 1;
            int width = (maxX >> 4) - (minX >> 4) + 1, height = (maxY >> 4) - (minY >> 4) + 1;
            int count = Math.multiplyExact(Math.multiplyExact(width, height), (maxZ >> 4) - (minZ >> 4) + 1);
            storage.capacity(count);
            sectionCount = count;
            storage.query.set(JAVA_INT, 0, minX); storage.query.set(JAVA_INT, 4, minY); storage.query.set(JAVA_INT, 8, minZ);
            storage.query.set(JAVA_INT, 12, maxX); storage.query.set(JAVA_INT, 16, maxY); storage.query.set(JAVA_INT, 20, maxZ);
            storage.query.set(JAVA_INT, 24, minX); storage.query.set(JAVA_INT, 28, minY); storage.query.set(JAVA_INT, 32, minZ);
            storage.query.set(JAVA_INT, 36, 0);
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) for (int x = minX >> 4; x <= maxX >> 4; x++) {
                ChunkAccess chunk = (ChunkAccess) level.getChunkForCollisions(x, z);
                for (int y = minY >> 4; y <= maxY >> 4; y++) {
                    int index = ((z - (minZ >> 4)) * height + y - (minY >> 4)) * width + x - (minX >> 4);
                    LevelChunkSection section = null;
                    if (chunk != null) {
                        int sectionIndex = chunk.getSectionIndex(y << 4);
                        if (sectionIndex >= 0 && sectionIndex < chunk.getSections().length) section = chunk.getSections()[sectionIndex];
                    }
                    storage.sections[index] = section;
                    MemorySegment rows = section == null ? MemorySegment.NULL
                            : ((CollisionBlockMask) section.getStates()).entityCollisionOptimizer$collisionRows(
                                    Math.max(minY, y << 4) & 15, Math.min(maxY, (y << 4) + 15) & 15,
                                    Math.max(minZ, z << 4) & 15, Math.min(maxZ, (z << 4) + 15) & 15);
                    storage.retained[index] = rows;
                    storage.pointers.set(ADDRESS, index * 8L, rows);
                }
            }
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public int next() {
        if (closed) throw new IllegalStateException("Closed block scan");
        try { return FFMBackend.scanBlocks(storage.pointers, storage.query, storage.output, BATCH); }
        finally { java.lang.ref.Reference.reachabilityFence(storage); }
    }
    public int coordinate(int record, int axis) { return storage.output.get(JAVA_INT, record * 16L + axis * 4L); }
    public LevelChunkSection section(int record) { return storage.sections[coordinate(record, 3)]; }
    @Override public void close() {
        if (closed) throw new IllegalStateException("Block scan closed twice");
        closed = true;
        Arrays.fill(storage.sections, 0, sectionCount, null);
        Arrays.fill(storage.retained, 0, sectionCount, null);
        POOL.get().addFirst(storage);
    }
}

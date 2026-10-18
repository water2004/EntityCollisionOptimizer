package org.edtp.entitycollisionoptimizer.collision.blocks;

import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

/** Per-block oracle; synthetic palette rows are injected only in the test source set. */
public final class BlockMaskScanChecks {
    private BlockMaskScanChecks() {}

    public static void verify(GameTestHelper helper) {
        int comparisons = 0;
        for (AABB box : new AABB[]{new AABB(-15.5, -.5, -15.5, 16.5, 1.5, 16.5),
                new AABB(.2, .2, .2, .8, .8, .8), new AABB(14.2, 14.2, 14.2, 16.8, 16.8, 16.8),
                new AABB(-31.5, -15.5, -.5, -14.5, .5, .5), new AABB(-63.5, -.5, -.5, 64.5, .5, .5),
                new AABB(1.2, 1.2, 1.2, 13.8, 8.8, 1.8)}) {
            for (int phase = 0; phase < 4; phase++) comparisons += compare(helper, box, phase);
        }
        packedRowUpdates(helper);
        liveRowAndNestedCursors(helper);
        EntityCollisionOptimizer.LOGGER.info("ECO_BLOCK_MASK_SCAN comparisons={} phases=4 ordered=true negative_coordinates=true missing_sections=true packed_updates=true live_row_mutation=true nested=true result=passed", comparisons);
    }

    private static void packedRowUpdates(GameTestHelper helper) {
        CollisionRows rows = new CollisionRows();
        rows.update(0, planes(7));
        helper.assertTrue(!rows.known(0), "cold updates do not initialize a row");
        for (int row : new int[]{0, 63, 64, 127, 128, 255}) {
            for (int changedX = 0; changedX < 16; changedX++) for (int flags = 0; flags < 8; flags++) {
                long initial = 0;
                for (int x = 0; x < 16; x++) initial |= planes((x + row) & 7) << x;
                rows.initialize(row, initial);
                rows.update(row << 4 | changedX, planes(flags));
                long actual = rows.masks(row);
                for (int x = 0; x < 16; x++) {
                    int observed = (int) ((actual >>> x) & 1)
                            | (int) ((actual >>> (x + 16)) & 1) << 1
                            | (int) ((actual >>> (x + 32)) & 1) << 2;
                    helper.assertValueEqual(observed, x == changedX ? flags : (x + row) & 7,
                            "packed update preserves other blocks and planes");
                }
            }
        }
    }

    private static int compare(GameTestHelper helper, AABB box, int phase) {
        int[] flags = new int[4096];
        CollisionRows rows = new CollisionRows();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            long bits = 0;
            for (int x = 0; x < 16; x++) {
                int kind = (x + y * 3 + z * 5) & 7;
                int value = phase == 1 ? 0 : phase == 2 ? 7 : kind == 0 ? 0 : 1 | ((kind & 1) << 1) | ((kind & 2) << 1);
                flags[x | z << 4 | y << 8] = value;
                bits |= planes(value) << x;
            }
            rows.initialize(y << 4 | z, bits);
        }
        int minX = Mth.floor(box.minX - 1e-7) - 1, minY = Mth.floor(box.minY - 1e-7) - 1, minZ = Mth.floor(box.minZ - 1e-7) - 1;
        int maxX = Mth.floor(box.maxX + 1e-7) + 1, maxY = Mth.floor(box.maxY + 1e-7) + 1, maxZ = Mth.floor(box.maxZ + 1e-7) + 1;
        int width = maxX - minX + 1, height = maxY - minY + 1;
        int sectionWidth = (maxX >> 4) - (minX >> 4) + 1, sectionHeight = (maxY >> 4) - (minY >> 4) + 1;
        int volume = width * height * (maxZ - minZ + 1), cursor = 0, comparisons = 0;
        try (BlockMaskScan scan = injected(helper, box, rows, true)) {
            LevelChunkSection[] sections = (LevelChunkSection[]) field(field(scan, "storage"), "sections");
            for (int calls = 0;; calls++) {
                if (phase == 3) {
                    int changed = calls * 37 & 4095;
                    flags[changed] ^= 7;
                    rows.update(changed, planes(flags[changed]));
                }
                int[] expected = null;
                while (cursor < volume) {
                    int index = cursor++;
                    int x = minX + index % width, y = minY + index / width % height, z = minZ + index / (width * height);
                    int section = (((z >> 4) - (minZ >> 4)) * sectionHeight + (y >> 4) - (minY >> 4)) * sectionWidth + (x >> 4) - (minX >> 4);
                    int edges = (x == minX || x == maxX ? 1 : 0) + (y == minY || y == maxY ? 1 : 0) + (z == minZ || z == maxZ ? 1 : 0);
                    int value = flags[(x & 15) | (z & 15) << 4 | (y & 15) << 8];
                    if (section % 5 != 3 && edges < 3 && (value & 1 << edges) != 0) {
                        expected = new int[]{x, y, z, section};
                        break;
                    }
                }
                helper.assertTrue(scan.next() == (expected != null), "per-block oracle candidate availability");
                if (expected == null) {
                    helper.assertTrue(!scan.next(), "completed cursor remains exhausted");
                    return comparisons;
                }
                helper.assertTrue(scan.x() == expected[0] && scan.y() == expected[1] && scan.z() == expected[2]
                        && scan.section() == sections[expected[3]], "ordered coordinate and section identity");
                comparisons++;
                helper.assertTrue(calls < volume, "cursor makes progress");
            }
        }
    }

    private static void liveRowAndNestedCursors(GameTestHelper helper) {
        CollisionRows rows = new CollisionRows();
        for (int row = 0; row < 256; row++) rows.initialize(row, 0);
        rows.update(1 | 1 << 4 | 1 << 8, 1);
        rows.update(3 | 1 << 4 | 1 << 8, 1);
        AABB box = new AABB(1.2, 1.2, 1.2, 3.8, 1.8, 1.8);
        try (BlockMaskScan outer = injected(helper, box, rows, false);
             BlockMaskScan inner = injected(helper, box, rows, false)) {
            helper.assertTrue(outer.next() && outer.x() == 1, "outer first candidate");
            rows.update(3 | 1 << 4 | 1 << 8, 0);
            rows.update(2 | 1 << 4 | 1 << 8, 1);
            helper.assertTrue(outer.next() && outer.x() == 2, "same-row callback insertion is visible");
            helper.assertTrue(inner.next() && inner.x() == 1 && inner.next() && inner.x() == 2, "nested cursor owns independent progress");
            helper.assertTrue(!outer.next() && !inner.next(), "same-row callback removal is visible");
        }
        try (BlockMaskScan abandoned = injected(helper, box, rows, false)) {
            helper.assertTrue(abandoned.next() && abandoned.x() == 1, "early-return cursor first candidate");
        }
        try (BlockMaskScan reused = injected(helper, box, rows, false)) {
            helper.assertTrue(reused.next() && reused.x() == 1 && reused.next() && reused.x() == 2
                    && !reused.next(), "pooled progress restarts after an early return");
        }
    }

    private static BlockMaskScan injected(GameTestHelper helper, AABB box, CollisionRows rows, boolean missing) {
        BlockMaskScan scan = new BlockMaskScan(helper.getLevel(), box);
        Object storage = field(scan, "storage");
        CollisionRows[] masks = (CollisionRows[]) field(storage, "rows");
        Arrays.fill(masks, null);
        int count = (Integer) field(storage, "sectionCount");
        for (int i = 0; i < count; i++) if (!missing || i % 5 != 3) masks[i] = rows;
        return scan;
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inject block mask fixture", failure);
        }
    }

    private static long planes(int flags) {
        return (flags & 1) | ((flags & 2) != 0 ? 1L << 16 : 0) | ((flags & 4) != 0 ? 1L << 32 : 0);
    }
}

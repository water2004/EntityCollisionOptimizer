package org.edtp.entitycollisionoptimizer.natives;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import net.minecraft.gametest.framework.GameTestHelper;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.collision.blocks.CollisionRows;
import static java.lang.foreign.ValueLayout.*;

/** Direct ordered-coordinate oracle, including pagination and mutations of leased shared rows. */
public final class NativeRowsChecks {
    public static void verify(GameTestHelper helper) {
        var rows = new CollisionRows();
        int[] flags = new int[4096];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            long bits = 0;
            for (int x = 0; x < 16; x++) {
                int kind = (x + y * 3 + z * 5) & 7;
                int value = kind == 0 ? 0 : 1 | ((kind & 1) << 1) | ((kind & 2) << 1);
                flags[x | z << 4 | y << 8] = value;
                bits |= planes(value) << x;
            }
            rows.initialize(y << 4 | z, bits);
        }
        int comparisons = 0;
        try (var arena = Arena.ofConfined()) {
            var pointers = arena.allocate(32 * 8, 8);
            for (int i = 0; i < 32; i++) pointers.set(ADDRESS, i * 8L, i == 9 ? MemorySegment.NULL : rows.memory());
            for (int phase = 0; phase < 3; phase++) {
                if (phase != 0) for (int i = 0; i < 4096; i += 7) {
                    flags[i] = phase == 1 ? 0 : 7;
                    rows.update(i, planes(flags[i]));
                }
                var expected = new ArrayList<int[]>();
                for (int z = -17; z <= 17; z++) for (int y = -2; y <= 2; y++) for (int x = -17; x <= 17; x++) {
                    int section = (((z >> 4) + 2) * 2 + (y >> 4) + 1) * 4 + (x >> 4) + 2;
                    int edges = (x == -17 || x == 17 ? 1 : 0) + (y == -2 || y == 2 ? 1 : 0) + (z == -17 || z == 17 ? 1 : 0);
                    int value = flags[(x & 15) | (z & 15) << 4 | (y & 15) << 8];
                    if (section != 9 && edges < 3 && (value & (1 << edges)) != 0) expected.add(new int[]{x, y, z, section});
                }
                for (int capacity : new int[]{1, 7, 256}) {
                    var query = arena.allocate(40, 8);
                    int[] initial = {-17, -2, -17, 17, 2, 17, -17, -2, -17, 0};
                    MemorySegment.copy(initial, 0, query, JAVA_INT, 0, 10);
                    var output = arena.allocate(capacity * 16L, 8);
                    int offset = 0;
                    for (int count; (count = FFMBackend.scanBlocks(pointers, query, output, capacity)) != 0;) {
                        helper.assertTrue(count <= capacity && offset + count <= expected.size(), "candidate count bounded by remaining oracle");
                        for (int i = 0; i < count; i++, offset++) for (int axis = 0; axis < 4; axis++) {
                            helper.assertValueEqual(output.get(JAVA_INT, i * 16L + axis * 4L), expected.get(offset)[axis], "native ordered row candidate");
                            comparisons++;
                        }
                    }
                    helper.assertValueEqual(offset, expected.size(), "pagination includes every candidate");
                    helper.assertValueEqual(query.get(JAVA_INT, 36), 1, "scan completed");
                }
            }
            java.lang.ref.Reference.reachabilityFence(rows);
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_NATIVE_ROWS comparisons={} pages=1,7,256 mutation=true missing_section=true result=passed", comparisons);
    }
    private static long planes(int flags) {
        return (flags & 1) | ((flags & 2) != 0 ? 1L << 16 : 0) | ((flags & 4) != 0 ? 1L << 32 : 0);
    }
}

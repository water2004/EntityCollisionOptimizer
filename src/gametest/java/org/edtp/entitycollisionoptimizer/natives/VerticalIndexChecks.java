package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Vertical cell crossings, half-open faces and multi-cell deduplication against Java AABB math. */
public final class VerticalIndexChecks {
    public static void verify(GameTestHelper helper) {
        int queries = 0;
        for (int count : new int[]{2, 8, 20}) {
            try (var context = FFMBackend.createContext()) {
                IndexUpdateFixture.initialize(context, count);
                for (int anchor : new int[]{-17, -4, -1, 0, 1, 15, 16}) {
                    for (int phase = 0; phase < 9; phase++) {
                        AABB[] boxes = new AABB[count];
                        for (int id = 0; id < count; id++) {
                            double y = anchor + ((id + phase) % 3) * 3;
                            if (id % 4 == 0) {
                                y = phase % 3 == 0 ? Math.nextDown(y)
                                        : phase % 3 == 1 ? y : Math.nextUp(y);
                            }
                            // Tall targets span multiple floors; small targets touch exact integer faces.
                            double height = id % 5 == 0 ? 5.0 : id % 2 == 0 ? 1.0 : 1.95;
                            // Stagger X/Z too, so ownership and heap repair merge different cell streams.
                            double x = -0.3 + (id % 3) * 0.2;
                            double z = -0.3 + (id / 3 % 3) * 0.2;
                            boxes[id] = new AABB(x, y, z, x + 0.6, y + height, z + 0.6);
                            IndexUpdateFixture.update(context, id, boxes[id], 0, section(y), 0,
                                    true, false, true, true, -1, 0,
                                    id, id % 2 == 0, count - id);
                        }
                        for (int source = 0; source < count; source++) {
                            AABB box = boxes[source];
                            List<Integer> overlaps = new ArrayList<>();
                            List<Integer> pushable = new ArrayList<>();
                            for (int id = 0; id < count; id++) {
                                if (!box.intersects(boxes[id])) continue;
                                overlaps.add(id);
                                if (id == source) continue;
                                int y = section(boxes[id].minY);
                                if (y >= section(box.minY - 4) && y <= section(box.maxY)) pushable.add(id);
                            }
                            pushable.sort(Comparator.<Integer>comparingLong(id ->
                                            SectionPos.asLong(0, section(boxes[id].minY), 0))
                                    .thenComparingInt(id -> count - id));
                            String label = "anchor=" + anchor
                                    + " phase=" + phase + " source=" + source;
                            equalSet(helper, FFMBackend.queryEntities(context, box, count), overlaps, label);
                            helper.assertValueEqual(ids(FFMBackend.queryPushable(
                                            context, box, source, -1, 0, true, count)),
                                    pushable, "ordered vertical push " + label);
                            for (boolean hardOnly : new boolean[]{false, true}) {
                                AABB scan = box.expandTowards(0, phase % 2 == 0 ? 4 : -4, 0);
                                List<Integer> expected = new ArrayList<>();
                                for (int id = 0; id < count; id++) {
                                    if (id != source && (!hardOnly || id % 2 == 0)
                                            && scan.intersects(boxes[id])) expected.add(id);
                                }
                                equalSet(helper, FFMBackend.queryHard(context, scan, source, hardOnly, count),
                                        expected, "vertical hard sweep " + label);
                            }
                            queries += 4;
                        }
                    }
                }
            }
        }
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_VERTICAL_INDEX_CHECKS entities=2,8,20 queries={} ordered=true result=passed",
                queries);
    }

    private static int section(double y) { return SectionPos.posToSectionCoord(y); }

    private static List<Integer> ids(FFMBackend.QueryResult result) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) ids.add(result.get(i));
        return ids;
    }

    private static void equalSet(GameTestHelper helper, FFMBackend.QueryResult result,
                                 List<Integer> expected, String label) {
        List<Integer> actual = ids(result);
        helper.assertTrue(actual.size() == expected.size() && new HashSet<>(actual).equals(new HashSet<>(expected)),
                label + " expected=" + expected + " actual=" + actual);
    }
}

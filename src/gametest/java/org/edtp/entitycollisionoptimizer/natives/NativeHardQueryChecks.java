package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Scan-AABB hard query contract, independent of the native cell walk. */
public final class NativeHardQueryChecks {
    public static void emptyWorld(GameTestHelper helper) {
        AABB player = new AABB(-0.3, 64, -0.3, 0.3, 65.8, 0.3);
        try (var context = FFMBackend.createContext()) {
            for (boolean hardOnly : new boolean[]{true, false}) {
                helper.assertValueEqual(FFMBackend.queryHard(context, player, -1, hardOnly, 0).size(),
                        0, "spawn query in empty world");
            }
        }
    }

    public static void verify(GameTestHelper helper) {
        int queries = 0;
        for (int count : new int[]{2, 8, 20}) {
            try (var context = FFMBackend.createContext()) {
                IndexUpdateFixture.initialize(context, count);
                for (int phase = 0; phase < 36; phase++) {
                    Body[] bodies = bodies(count, phase);
                    for (int id = 0; id < count; id++) {
                        Body b = bodies[id];
                        IndexUpdateFixture.update(context, id, b.box, b.x, b.y, b.z,
                                true, false, true, true, -1, 0, id, b.hard, id);
                    }
                    AABB scan = scan(bodies[0].box, phase);
                    compare(helper, context, bodies, scan, true, count, phase);
                    compare(helper, context, bodies, scan, false, count, phase);
                    queries += 2;
                }
            }
        }
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_NATIVE_HARD_QUERY_CHECKS entities=2,8,20 queries={} scan_sweeps=true hard_only=true result=passed",
                queries);
    }

    private static Body[] bodies(int count, int phase) {
        double anchor = new double[]{-32, 0, 16}[phase / 12];
        AABB source = new AABB(anchor, anchor + 1, anchor, anchor + 12, anchor + 3, anchor + 12);
        Body[] bodies = new Body[count];
        for (int id = 0; id < count; id++) {
            AABB box = source.move((id % 3) * 4, (phase % 3) * 0.5, (id / 3) * 4);
            if (id != 0 && id % 4 == 0) {
                double x = switch (phase % 3) {
                    case 0 -> Math.nextDown(source.maxX);
                    case 1 -> source.maxX;
                    default -> Math.nextUp(source.maxX);
                };
                box = new AABB(x, source.minY, source.minZ, x + 1, source.maxY, source.maxZ);
            }
            bodies[id] = new Body(
                    box,
                    SectionPos.posToSectionCoord((box.minX + box.maxX) * 0.5),
                    SectionPos.posToSectionCoord(box.minY),
                    SectionPos.posToSectionCoord((box.minZ + box.maxZ) * 0.5),
                    id % 3 != 0
            );
        }
        return bodies;
    }

    private static AABB scan(AABB source, int phase) {
        return switch (phase % 4) {
            case 0 -> source;
            case 1 -> source.expandTowards(2, 0, 0);
            case 2 -> source.expandTowards(-1, 1, 2);
            default -> source.inflate(1.0E-7);
        };
    }

    private static void compare(GameTestHelper helper, FFMBackend.Context context, Body[] bodies,
                                AABB scan, boolean hardOnly, int capacity, int phase) {
        var result = FFMBackend.queryHard(context, scan, 0, hardOnly, capacity);
        Set<Integer> expected = new HashSet<>();
        for (int id = 1; id < bodies.length; id++) {
            Body body = bodies[id];
            if ((!hardOnly || body.hard) && scan.intersects(body.box)
                    && body.x >= SectionPos.posToSectionCoord(scan.minX - 2.0)
                    && body.x <= SectionPos.posToSectionCoord(scan.maxX + 2.0)
                    && body.y >= SectionPos.posToSectionCoord(scan.minY - 4.0)
                    && body.y <= SectionPos.posToSectionCoord(scan.maxY)
                    && body.z >= SectionPos.posToSectionCoord(scan.minZ - 2.0)
                    && body.z <= SectionPos.posToSectionCoord(scan.maxZ + 2.0)) expected.add(id);
        }
        List<Integer> actual = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) actual.add(result.get(i));
        helper.assertTrue(new HashSet<>(actual).equals(expected) && actual.size() == expected.size(),
                "hard query phase=" + phase + " hardOnly=" + hardOnly
                        + " expected=" + expected + " actual=" + actual);
    }

    private record Body(AABB box, int x, int y, int z, boolean hard) {}
}

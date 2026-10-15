package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Source-boundary and output-layout contract, independent of the native query implementation. */
public final class NativeQueryChecks {
    public static void verify(GameTestHelper helper) {
        maskedSections(helper);
        int queries = 0;
        for (int count : new int[]{2, 8, 20}) {
            try (var context = FFMBackend.createContext()) {
                IndexUpdateFixture.initialize(context, count);
                for (int phase = 0; phase < 90; phase++) {
                    Body[] bodies = bodies(count, phase);
                    rebuild(context, bodies);
                    // Grow reusable output storage without changing the low/medium entity count.
                    int capacityHint = phase >= 30 ? 513 : count;
                    for (int rule = 0; rule < 4; rule++) for (boolean sourceNative : new boolean[]{false, true}) {
                        if ((phase & 1) == 0) FFMBackend.invalidatePushEligibilityCacheFields(context, 3);
                        metadata(context, 0, bodies[0]);
                        compare(helper, context, bodies, rule, sourceNative, capacityHint, phase);
                        queries++;
                    }
                }
                // Empty geometry after a populated query must not expose stale counts or slots.
                Body source = bodies(count, 89)[0];
                IndexUpdateFixture.update(context, 0, new AABB(0, 0, 0, 0, 0, 0), 0, 0, 0,
                        source.selectable, source.passenger, source.vanillaEntityPush, source.allowsDeferredVelocityWrites,
                        source.team, source.rule, false);
                var empty = FFMBackend.queryPushable(
                        context, new AABB(0, 0, 0, 0, 0, 0), 0, -1, 0, true, 0, count);
                helper.assertTrue(empty.size() == 0 && empty.pushableCount() == 0
                        && empty.nonPassengerCount() == 0 && !empty.metadataRequired(), "empty source result");
                empty.copyPushCandidatesTo(new int[0], new int[0]);
            }
        }
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_NATIVE_QUERY_CHECKS entities=2,8,20 queries={} section_faces=6 ulp_offsets=3 team_rules=16 output_growth=true result=passed",
                queries);
    }

    private static void maskedSections(GameTestHelper helper) {
        AABB box = new AABB(0, 0, 0, 1, 1, 1);
        try (var context = FFMBackend.createContext()) {
            for (int id = 0; id < 29; id++) {
                IndexUpdateFixture.insert(context, id, box, 0, 0, 0);
                IndexUpdateFixture.metadata(context, id, false, false, true, true, -1, 0, false);
            }
            helper.assertValueEqual(FFMBackend.queryPushable(context, box, -1, -1, 0, true, 0, 29).size(),
                    0, "fully masked section");
            // Keep the section queryable while its preceding 8-lane and 4-lane batches are empty.
            for (int selected : new int[]{28, 24, 9, 0}) {
                FFMBackend.invalidateEntityPushEligibilityCache(context, selected);
                var miss = FFMBackend.queryPushable(context, box, -1, -1, 0, true, 0, 29);
                helper.assertTrue(miss.metadataRequired() && miss.size() == 1 && miss.get(0) == selected
                        && miss.requiredFields(0) == 1, "masked lane reactivated by invalidation " + selected);
                IndexUpdateFixture.metadata(context, selected, true, false, true, true, -1, 0, false);
                var ready = FFMBackend.queryPushable(context, box, -1, -1, 0, true, 0, 29);
                helper.assertTrue(!ready.metadataRequired() && ready.size() == 1 && ready.get(0) == selected,
                        "single visible lane " + selected);
                IndexUpdateFixture.metadata(context, selected, false, false, true, true, -1, 0, false);
            }
            helper.assertValueEqual(FFMBackend.queryHard(context, box, -1, false, 29).size(),
                    29, "push filtering must not hide entities from geometry queries");
        }
    }

    private static Body[] bodies(int count, int phase) {
        double anchor = new double[]{-32, -16, 0, 16, 29_999_968}[phase / 18];
        int face = (phase / 3) % 6;
        int offset = phase % 3 - 1;
        double[] edges = {anchor + 2, anchor + 4, anchor + 2, anchor + 14, anchor + 16, anchor + 14};
        edges[face] = offset < 0 ? Math.nextDown(edges[face]) : offset > 0 ? Math.nextUp(edges[face]) : edges[face];
        AABB source = new AABB(edges[0], edges[1], edges[2], edges[3], edges[4], edges[5]);
        int[] lower = {section(source.minX - 2), section(source.minY - 4), section(source.minZ - 2)};
        int[] upper = {section(source.maxX + 2), section(source.maxY), section(source.maxZ + 2)};
        Body[] bodies = new Body[count];
        for (int id = 0; id < count; id++) {
            int[] location = lower.clone();
            int axis = face % 3;
            // Both sides of every lookup face, while target boxes still overlap the source.
            location[axis] = (face < 3 ? lower[axis] : upper[axis]) + (id + phase) % 3 - 1;
            AABB box = source;
            if (id != 0 && id % 5 == 0) {
                double x = switch (phase % 3) {
                    case 0 -> Math.nextDown(source.maxX);
                    case 1 -> source.maxX;
                    default -> Math.nextUp(source.maxX);
                };
                box = new AABB(x, source.minY, source.minZ, x + 1, source.maxY, source.maxZ);
            }
            // Cycle no team / same team / different team as well as all target collision rules.
            bodies[id] = new Body(box, location[0], location[1], location[2], (id + phase) % 3 - 1,
                    (id + phase) % 4, (id + phase) % 7 != 0, (id + phase) % 3 == 0,
                    (id + phase) % 4 != 0, (id + phase) % 5 != 0, count - id);
        }
        return bodies;
    }

    private static void compare(GameTestHelper helper, FFMBackend.Context context, Body[] bodies,
                                int rule, boolean sourceNative, int capacityHint, int phase) {
        boolean sourceNativePushEligible = sourceNative && bodies[0].allowsDeferredVelocityWrites;
        var result = FFMBackend.queryPushable(
                context, bodies[0].box, 0, bodies[0].team, rule, sourceNativePushEligible, 0, capacityHint);
        if (result.metadataRequired()) {
            for (int i = 0; i < result.size(); i++) metadata(context, result.get(i), bodies[result.get(i)]);
            result = FFMBackend.queryPushable(
                    context, bodies[0].box, 0, bodies[0].team, rule, sourceNativePushEligible, 0, capacityHint);
        }
        helper.assertTrue(!result.metadataRequired(), "query metadata converged");
        Body source = bodies[0];
        List<Integer> expected = new ArrayList<>();
        for (int id = 1; id < bodies.length; id++) {
            Body b = bodies[id];
            boolean allied = source.team >= 0 && source.team == b.team;
            // Truth table for the four vanilla collision rules; independent of native control flow.
            int allowedRules = (allied ? new int[]{9, 0, 0, 9} : new int[]{5, 0, 5, 0})[rule];
            if (b.selectable && (allowedRules & (1 << b.rule)) != 0 && source.box.intersects(b.box)
                    && b.x >= section(source.box.minX - 2) && b.x <= section(source.box.maxX + 2)
                    && b.y >= section(source.box.minY - 4) && b.y <= section(source.box.maxY)
                    && b.z >= section(source.box.minZ - 2) && b.z <= section(source.box.maxZ + 2)) expected.add(id);
        }
        expected.sort(Comparator.<Integer>comparingLong(id -> SectionPos.asLong(bodies[id].x, bodies[id].y, bodies[id].z))
                .thenComparingLong(id -> bodies[id].order));
        List<Integer> actual = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) actual.add(result.get(i));
        String label = "phase=" + phase + " rule=" + rule + " native=" + sourceNative;
        helper.assertValueEqual(actual, expected, "query IDs " + label);
        helper.assertValueEqual(result.pushableCount(), expected.size(), "pushable count " + label);
        helper.assertValueEqual(result.nonPassengerCount(), (int) expected.stream().filter(id -> !bodies[id].passenger).count(),
                "non-passenger count " + label);
        int[] nativeIds = new int[result.size()], flags = new int[result.size()];
        result.copyPushCandidatesTo(nativeIds, flags);
        for (int i = 0; i < nativeIds.length; i++) {
            int id = expected.get(i);
            helper.assertValueEqual(nativeIds[i], id, "copied native ID " + label);
            boolean nativePush = sourceNative && source.allowsDeferredVelocityWrites
                    && bodies[id].vanillaEntityPush && bodies[id].allowsDeferredVelocityWrites;
            helper.assertValueEqual(flags[i], nativePush ? 1 : 0, "dispatch flag " + label);
        }
    }

    private static int section(double coordinate) { return SectionPos.posToSectionCoord(coordinate); }

    private static void rebuild(FFMBackend.Context context, Body[] bodies) {
        for (int id = 0; id < bodies.length; id++) FFMBackend.removeEntity(context, id);
        List<Integer> insertionOrder = new ArrayList<>();
        for (int id = 0; id < bodies.length; id++) insertionOrder.add(id);
        insertionOrder.sort(Comparator.comparingLong(id -> bodies[id].order));
        for (int id : insertionOrder) {
            Body body = bodies[id];
            IndexUpdateFixture.insert(context, id, body.box, body.x, body.y, body.z);
            IndexUpdateFixture.metadata(context, id,
                    body.selectable, body.passenger, body.vanillaEntityPush, body.allowsDeferredVelocityWrites,
                    body.team, body.rule, false);
        }
    }

    private static void metadata(FFMBackend.Context context, int id, Body b) {
        IndexUpdateFixture.metadata(context, id, b.selectable, b.passenger,
                b.vanillaEntityPush, b.allowsDeferredVelocityWrites,
                b.team, b.rule, false);
    }

    private record Body(AABB box, int x, int y, int z, int team, int rule, boolean selectable,
                        boolean passenger, boolean vanillaEntityPush, boolean allowsDeferredVelocityWrites, long order) {}
}

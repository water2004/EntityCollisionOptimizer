package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Differential API probe: vanilla packed section keys, not a copy of the native comparator. */
public final class NativeOrderChecks {
    public static void verify(GameTestHelper helper) {
        for (int count : new int[]{2, 8, 20}) verify(helper, count);
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_NATIVE_ORDER_CHECKS entities=2,8,20 queries=8640 signed_sections=true metadata_retries=true result=passed");
    }

    private static void verify(GameTestHelper helper, int count) {
        Body[] bodies = new Body[count];
        for (int i = 0; i < count; i++) bodies[i] = new Body(i, count);
        try (var context = FFMBackend.createContext()) {
            insertAll(context, bodies);
            for (int step = 0; step < 96; step++) {
                int id = (step * 7 + 1) % count;
                Body body = bodies[id];
                switch (step % 8) {
                    case 0 -> body.box = body.home.move(8, 0, 8);
                    case 1 -> body.box = body.home;
                    case 2 -> { body.x ^= -1; body.y ^= -1; body.z ^= -1; }
                    case 3 -> body.order = 1000L + step; // Key change without changing cell membership.
                    case 4 -> { body.selectable = !body.selectable; body.passenger = !body.passenger; }
                    case 5 -> FFMBackend.invalidatePushEligibilityFields(context, 3);
                    case 6, 7 -> resetAll(context, bodies);
                }
                IndexUpdateFixture.update(context, id, body.box, body.x, body.y, body.z,
                        body.selectable, body.passenger, true, true,
                        -1, 0, 100 + id, false, body.order);
                // Repeated queries with different sources share the same ordered cell state.
                for (int repeat = 0; repeat < 3; repeat++) for (int source = 0; source < count; source++) {
                    compare(helper, context, bodies, source, "step=" + step + " repeat=" + repeat);
                }
            }
        }
    }

    private static void compare(GameTestHelper helper, FFMBackend.Context context, Body[] bodies, int source, String label) {
        metadata(context, source, bodies[source]);
        FFMBackend.QueryResult result = FFMBackend.queryPushable(
                context, bodies[source].box, source, -1, 0, true, bodies.length);
        if (result.metadataRequired()) {
            var unique = new HashSet<Integer>();
            for (int i = 0; i < result.size(); i++) {
                int id = result.get(i);
                helper.assertTrue(unique.add(id), "duplicate metadata miss " + label);
                metadata(context, id, bodies[id]);
            }
            result = FFMBackend.queryPushable(
                    context, bodies[source].box, source, -1, 0, true, bodies.length);
        }
        helper.assertTrue(!result.metadataRequired(), "metadata converged " + label);
        List<Integer> expected = new ArrayList<>();
        AABB query = bodies[source].box;
        for (int id = 0; id < bodies.length; id++) {
            Body body = bodies[id];
            if (id != source && body.selectable && query.intersects(body.box)
                    && body.x >= Math.floor((query.minX - 2) / 16) && body.x <= Math.floor((query.maxX + 2) / 16)
                    && body.y >= Math.floor((query.minY - 4) / 16) && body.y <= Math.floor(query.maxY / 16)
                    && body.z >= Math.floor((query.minZ - 2) / 16) && body.z <= Math.floor((query.maxZ + 2) / 16)) expected.add(id);
        }
        expected.sort(Comparator.<Integer>comparingLong(id -> SectionPos.asLong(bodies[id].x, bodies[id].y, bodies[id].z))
                .thenComparingLong(id -> bodies[id].order).thenComparingInt(id -> id));
        List<Integer> actual = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) actual.add(result.get(i));
        helper.assertValueEqual(actual, expected, "ordered union " + label);
        helper.assertValueEqual(result.pushableCount(), expected.size(), "full candidate count " + label);
        helper.assertValueEqual(result.nonPassengerCount(), (int) expected.stream().filter(id -> !bodies[id].passenger).count(),
                "passenger count " + label);
        int[] slots = new int[result.size()], flags = new int[result.size()];
        result.copyBodiesTo(slots, flags);
        for (int i = 0; i < slots.length; i++) {
            helper.assertValueEqual(slots[i], 100 + expected.get(i), "body slot order " + label);
            helper.assertValueEqual(flags[i], 1, "native dispatch order " + label);
        }
    }

    private static void insertAll(FFMBackend.Context context, Body[] bodies) {
        for (int id = 0; id < bodies.length; id++) {
            Body body = bodies[id];
            IndexUpdateFixture.put(context, id, body.box, body.x, body.y, body.z, body.order);
        }
    }

    private static void resetAll(FFMBackend.Context context, Body[] bodies) {
        for (int id = 0; id < bodies.length; id++) FFMBackend.removeEntity(context, id);
        insertAll(context, bodies);
    }

    private static void metadata(FFMBackend.Context context, int id, Body body) {
        IndexUpdateFixture.metadata(context, id, body.selectable, body.passenger,
                true, true, -1, 0, 100 + id, false, body.order);
    }

    private static final class Body {
        final AABB home;
        AABB box;
        int x, y, z;
        long order;
        boolean selectable = true, passenger;
        Body(int id, int count) {
            home = box = new AABB(-.4 + id % 5 * .03, -.4, -.4 + id / 5 * .03, .4, .4, .4);
            x = -(id & 1); y = -((id >> 1) & 1); z = -((id >> 2) & 1);
            order = count - id;
        }
    }
}

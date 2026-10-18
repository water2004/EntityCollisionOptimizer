package org.edtp.entitycollisionoptimizer.natives;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;

/** Shared publication contract; expectations are explicit index operations, not another ECO query. */
public final class IndexUpdateChecks {
    private static final AABB BOX = new AABB(0, 0, 0, 1, 1, 1);
    private static final AABB SCAN = new AABB(-1, -1, -1, 40, 2, 2);

    public static void verify(GameTestHelper helper) {
        try (var context = FFMBackend.createContext(); var other = FFMBackend.createContext()) {
            FFMBackend.insertEntity(context, 0, BOX, 0, 0, 0, false, false);
            FFMBackend.insertEntity(context, 1, BOX, 0, 0, 0, false, false);
            FFMBackend.insertEntity(other, 0, BOX, 0, 0, 0, false, false);
            metadata(context, 0, true, false);
            metadata(context, 1, true, true);
            helper.assertValueEqual(pending(context), 4, "metadata publication has not downcalled");
            expect(helper, FFMBackend.queryHard(context, SCAN, -1, true, 2), 1);
            helper.assertValueEqual(pending(context), 0, "hard query consumes hard-slot and population updates");

            // Bounds and metadata must not be reordered: moving an excluded entity
            // makes it queryable again, but a subsequent resolved state can exclude it.
            metadata(context, 1, false, true);
            FFMBackend.updateEntityBounds(context, 1, BOX.move(2, 0, 0));
            helper.assertTrue(push(context).metadataRequired(), "bounds after state must invalidate state");
            metadata(context, 1, true, true);
            expect(helper, push(context), 0, 1);
            FFMBackend.updateEntityBounds(context, 1, BOX);
            metadata(context, 1, false, false);
            expect(helper, push(context), 0);
            expect(helper, FFMBackend.queryHard(context, SCAN, -1, true, 2));

            // Section moves preserve their order even if no query separates them.
            FFMBackend.updateEntitySection(context, 0, 1, 0, 0);
            FFMBackend.updateEntitySection(context, 0, 0, 0, 0);
            expect(helper, FFMBackend.queryEntities(context, SCAN, 2), 1, 0);
            FFMBackend.invalidatePushEligibilityCacheFields(context, 3);
            helper.assertTrue(push(context).metadataRequired(), "global invalidation is visible to the next query");
            metadata(context, 0, true, false);
            metadata(context, 1, true, false);
            FFMBackend.invalidateEntityPushEligibilityCache(context, 1);
            helper.assertTrue(push(context).metadataRequired(), "entity invalidation follows resolved state");
            metadata(context, 1, true, false);
            expect(helper, push(context), 1, 0);

            // A lifecycle boundary consumes the old incarnation before reusing its ID.
            metadata(context, 1, false, true);
            FFMBackend.updateEntityBounds(context, 1, BOX.move(100, 0, 0));
            FFMBackend.removeEntity(context, 1);
            helper.assertValueEqual(pending(context), 0, "removal consumes old publications");
            FFMBackend.insertEntity(context, 1, BOX, 0, 0, 0, false, false);
            helper.assertTrue(push(context).metadataRequired(), "reused ID starts with unresolved metadata");
            metadata(context, 1, true, false);
            expect(helper, push(context), 0, 1);

            // A bounded queue must also progress when no spatial query is issued.
            for (int index = 0; index < 513; index++) {
                FFMBackend.updateEntityBounds(context, 0, BOX.move(2 + index * .001, 0, 0));
            }
            helper.assertValueEqual(pending(context), 1, "two full batches consumed without a query");
            FFMBackend.updateEntityBounds(other, 0, BOX.move(8, 0, 0));
            expect(helper, FFMBackend.queryEntities(context, BOX.move(2.512, 0, 0), 2), 0);
            helper.assertValueEqual(pending(other), 1, "queries do not consume another context's publications");
            expect(helper, FFMBackend.queryEntities(other, BOX.move(8, 0, 0), 1), 0);

            FFMBackend.updateEntityBounds(context, 0, BOX);
            FFMBackend.insertEntity(context, 2, BOX, 0, 0, 0, false, false);
            helper.assertValueEqual(pending(context), 0, "insertion consumes pending publications");
            expect(helper, FFMBackend.queryEntities(context, SCAN, 3), 0, 1, 2);
        }
    }

    private static void metadata(FFMBackend.Context context, int id, boolean selectable, boolean hard) {
        IndexUpdateFixture.metadata(context, id, selectable, false, true, true, -1, 0, hard);
    }

    private static FFMBackend.QueryResult push(FFMBackend.Context context) {
        return FFMBackend.queryPushable(context, SCAN, -1, -1, 0, true, 0, 2);
    }

    private static void expect(GameTestHelper helper, FFMBackend.QueryResult result, Integer... ids) {
        List<Integer> actual = new ArrayList<>();
        for (int index = 0; index < result.size(); index++) actual.add(result.get(index));
        helper.assertTrue(!result.metadataRequired(), "resolved publication result");
        helper.assertValueEqual(actual, List.of(ids), "ordered candidates after shared publications");
    }

    private static int pending(FFMBackend.Context context) {
        try {
            Field field = FFMBackend.Context.class.getDeclaredField("updates");
            field.setAccessible(true);
            return ((IndexUpdates) field.get(context)).header().get(java.lang.foreign.ValueLayout.JAVA_INT, 8);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}

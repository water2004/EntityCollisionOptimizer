package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.NativeEntityQueryFaults;

import java.util.ArrayList;
import java.util.List;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.identitySet;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnEntity;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;

/** Direct oracle coverage for the EntitySectionStorage methods owned by the native index. */
final class EntityQueryParity {
    private static final EntityTypeTest<Entity, Zombie> ZOMBIES = EntityTypeTest.forClass(Zombie.class);

    private EntityQueryParity() {
    }

    static void verify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Entity> fixtures = List.of(
                spawnZombie(helper, new Vec3(34.25, 2.0, 3.25)),
                spawnZombie(helper, new Vec3(2.25, 2.0, 3.25)),
                spawnEntity(helper, EntityType.ARMOR_STAND, new Vec3(18.25, 2.0, 3.25)),
                spawnZombie(helper, new Vec3(18.75, 2.0, 3.25)),
                spawnZombie(helper, new Vec3(2.75, 2.0, 3.25)),
                spawnZombie(helper, new Vec3(4.25, 2.0, 3.25)),
                spawnEntity(helper, EntityType.ARMOR_STAND, new Vec3(5.25, 2.0, 3.25)),
                spawnZombie(helper, new Vec3(6.25, 2.0, 3.25)),
                spawnZombie(helper, new Vec3(7.25, 2.0, 3.25))
        );
        AABB query = fixtures.stream().map(Entity::getBoundingBox)
                .reduce(AABB::minmax).orElseThrow().inflate(0.25);

        try {
            verifyUnboundStorage(helper, fixtures.getFirst());
            // end() is a frame boundary, not a switch back to vanilla queries.
            QueryTrace expected = VanillaEntityQueries.call(() -> trace(level, query, fixtures.getFirst()));
            helper.assertTrue(expected.all.size() == fixtures.size()
                            && identitySet(expected.all).equals(identitySet(fixtures)),
                    "reference query must find every fixture exactly once");
            List<Entity> zombies = fixtures.stream().filter(Zombie.class::isInstance).toList();
            helper.assertTrue(expected.typed.size() == zombies.size()
                            && identitySet(expected.typed).equals(identitySet(zombies)),
                    "reference typed query must find all seven zombies and no armor stands");
            helper.assertValueEqual(expected.limited.size(), 2, "reference limited query must not be empty");
            helper.assertValueEqual(expected.nested.size(), fixtures.size() - 1,
                    "reference nested query must run and exclude its target");

            CollisionFrame.begin(level);
            QueryTrace actual = trace(level, query, fixtures.getFirst());

            assertMatches(helper, actual, expected);
            verifyFaultDetection(helper, level, query, fixtures.getFirst(), expected);
            // The injected handle and oracle scope must both be restored before other tests run.
            helper.assertTrue(!VanillaEntityQueries.active(), "reference query scope must not leak");
            assertMatches(helper, trace(level, query, fixtures.getFirst()), expected);
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_ENTITY_QUERY_PARITY entities={} typed={} limited={} reentrant=true mutations=3 result=passed",
                    actual.all.size(), actual.typed.size(), actual.limited.size()
            );
        } finally {
            for (Entity fixture : fixtures) fixture.discard();
            CollisionFrame.end(level);
        }
    }

    /** Client and other non-server stores share this class but must retain their own query path. */
    private static void verifyUnboundStorage(GameTestHelper helper, Entity fixture) {
        EntitySectionStorage<Entity> storage = new EntitySectionStorage<>(Entity.class, ignored -> Visibility.TRACKED);
        storage.getOrCreateSection(SectionPos.of(fixture).asLong()).add(fixture);

        List<Entity> all = new ArrayList<>();
        storage.getEntities(fixture.getBoundingBox().inflate(0.25),
                AbortableIterationConsumer.forConsumer(all::add));
        helper.assertTrue(all.equals(List.of(fixture)), "unbound untyped storage must use its local query");

        List<Zombie> typed = new ArrayList<>();
        storage.getEntities(ZOMBIES, fixture.getBoundingBox().inflate(0.25),
                AbortableIterationConsumer.forConsumer(typed::add));
        helper.assertTrue(typed.equals(List.of(fixture)), "unbound typed storage must use its local query");
    }

    private static QueryTrace trace(ServerLevel level, AABB query, Entity nestedExclusion) {
        List<Entity> all = level.getEntities((Entity) null, query, entity -> true);
        List<Zombie> typed = level.getEntities(ZOMBIES, query, entity -> true);
        List<Zombie> limited = new ArrayList<>();
        int[] limitedVisits = {0};
        level.getEntities(ZOMBIES, query, entity -> {
            limitedVisits[0]++;
            return true;
        }, limited, 2);

        List<Entity> nested = new ArrayList<>();
        boolean[] queried = {false};
        List<Entity> outer = level.getEntities((Entity) null, query, entity -> {
            if (!queried[0]) {
                queried[0] = true;
                nested.addAll(level.getEntities((Entity) null, query, candidate -> candidate != nestedExclusion));
            }
            return true;
        });
        return new QueryTrace(all, typed, limited, limitedVisits[0], outer, nested);
    }

    private static void assertMatches(GameTestHelper helper, QueryTrace actual, QueryTrace expected) {
        assertTraversal(helper, actual.all, expected.all, "untyped traversal");
        assertTraversal(helper, actual.typed, expected.typed, "typed traversal");
        assertTraversal(helper, actual.limited, expected.limited, "limited traversal");
        assertLimited(helper, actual.limited, expected.typed);
        helper.assertValueEqual(actual.limitedVisits, expected.limitedVisits, "abort callback count");
        helper.assertValueEqual(actual.limitedVisits, 2, "abort must stop before the third callback");
        assertTraversal(helper, actual.outer, expected.outer, "reentrant outer traversal");
        assertTraversal(helper, actual.nested, expected.nested, "reentrant nested traversal");
    }

    private static void verifyFaultDetection(GameTestHelper helper, ServerLevel level, AABB query,
                                             Entity excluded, QueryTrace expected) {
        for (var fault : NativeEntityQueryFaults.Fault.values()) {
            try (var injection = new NativeEntityQueryFaults(fault)) {
                QueryTrace reference = VanillaEntityQueries.call(() -> trace(level, query, excluded));
                helper.assertValueEqual(injection.calls(), 0, "reference must never invoke native entity query");
                assertMatches(helper, reference, expected);

                QueryTrace corrupted = trace(level, query, excluded);
                helper.assertTrue(injection.calls() > 0, "actual query must reach injected native handle");
                boolean rejected = false;
                try {
                    assertMatches(helper, corrupted, reference);
                } catch (GameTestAssertException mismatch) {
                    rejected = true;
                }
                helper.assertTrue(rejected, "parity assertion must reject native query fault " + fault);
                EntityCollisionOptimizer.LOGGER.info("ECO_ENTITY_QUERY_MUTATION fault={} detected=true", fault);
            }
        }
    }

    private static void assertTraversal(
            GameTestHelper helper, List<? extends Entity> actual, List<? extends Entity> expected, String label
    ) {
        boolean matches = actual.equals(expected);
        helper.assertTrue(matches, label + ": expected=" + ids(expected) + ", actual=" + ids(actual));
    }

    private static void assertLimited(
            GameTestHelper helper, List<? extends Entity> actual, List<? extends Entity> complete
    ) {
        int expectedSize = Math.min(2, complete.size());
        boolean matches = actual.size() == expectedSize && identitySet(complete).containsAll(actual);
        matches &= actual.equals(complete.subList(0, expectedSize));
        helper.assertTrue(matches, "abort traversal: complete=" + ids(complete) + ", actual=" + ids(actual));
    }

    private static List<Integer> ids(List<? extends Entity> entities) {
        return entities.stream().map(Entity::getId).toList();
    }

    private record QueryTrace(
            List<Entity> all,
            List<Zombie> typed,
            List<Zombie> limited,
            int limitedVisits,
            List<Entity> outer,
            List<Entity> nested
    ) {
    }
}

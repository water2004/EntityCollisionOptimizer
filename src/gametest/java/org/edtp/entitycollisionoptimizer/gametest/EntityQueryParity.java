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
import org.edtp.entitycollisionoptimizer.natives.NativeEntityQueryProbe;

import java.util.ArrayList;
import java.util.List;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.identitySet;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnEntity;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;

/** Independent untyped-query oracle and contracts for the Java/native query boundary. */
final class EntityQueryParity {
    private static final EntityTypeTest<Entity, Zombie> ZOMBIES = EntityTypeTest.forClass(Zombie.class);

    private EntityQueryParity() {
    }

    static void verify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Entity> fixtures = List.of(
                spawnEntity(helper, EntityType.HUSK, new Vec3(34.25, 2.0, 3.25)),
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
            helper.assertValueEqual(expected.nested, List.of(fixtures.getFirst()),
                    "reference nested query must have fewer spatial hits than the outer query");

            CollisionFrame.begin(level);
            TypedEntityQueryChecks.verify(helper, query, expected.all);
            QueryTrace actual = trace(level, query, fixtures.getFirst());

            assertMatches(helper, actual, expected);
            verifyFaultDetection(helper, level, query, fixtures.getFirst(), expected);
            // The injected handle and oracle scope must both be restored before other tests run.
            helper.assertTrue(!VanillaEntityQueries.active(), "reference query scope must not leak");
            assertMatches(helper, trace(level, query, fixtures.getFirst()), expected);
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_ENTITY_QUERY_PARITY entities={} typed_hybrid=true reentrant=true mutations=6 result=passed",
                    actual.all.size()
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

    private static QueryTrace trace(ServerLevel level, AABB query, Entity nestedAnchor) {
        List<Entity> all = level.getEntities((Entity) null, query, entity -> true);
        List<Entity> nested = new ArrayList<>();
        boolean[] queried = {false};
        List<Entity> outer = level.getEntities((Entity) null, query, entity -> {
            if (!queried[0]) {
                queried[0] = true;
                nested.addAll(level.getEntities((Entity) null, nestedAnchor.getBoundingBox(), candidate -> true));
            }
            return true;
        });
        return new QueryTrace(all, outer, nested);
    }

    private static void assertMatches(GameTestHelper helper, QueryTrace actual, QueryTrace expected) {
        assertTraversal(helper, actual.all, expected.all, "untyped traversal");
        assertTraversal(helper, actual.outer, expected.outer, "reentrant outer traversal");
        assertTraversal(helper, actual.nested, expected.nested, "reentrant nested traversal");
    }

    private static void verifyFaultDetection(GameTestHelper helper, ServerLevel level, AABB query,
                                             Entity nestedAnchor, QueryTrace expected) {
        for (var fault : NativeEntityQueryProbe.Fault.values()) {
            try (var injection = new NativeEntityQueryProbe(fault)) {
                QueryTrace reference = VanillaEntityQueries.call(() -> trace(level, query, nestedAnchor));
                helper.assertValueEqual(injection.calls(), 0, "reference must never invoke native entity query");
                assertMatches(helper, reference, expected);

                QueryTrace corrupted = trace(level, query, nestedAnchor);
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

    private static List<Integer> ids(List<? extends Entity> entities) {
        return entities.stream().map(Entity::getId).toList();
    }

    private record QueryTrace(
            List<Entity> all,
            List<Entity> outer,
            List<Entity> nested
    ) {
    }
}

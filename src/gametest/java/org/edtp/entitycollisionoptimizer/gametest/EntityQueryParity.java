package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

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
                spawnEntity(helper, EntityTypes.ARMOR_STAND, new Vec3(18.25, 2.0, 3.25)),
                spawnZombie(helper, new Vec3(18.75, 2.0, 3.25)),
                spawnZombie(helper, new Vec3(2.75, 2.0, 3.25))
        );
        AABB query = fixtures.stream().map(Entity::getBoundingBox)
                .reduce(AABB::minmax).orElseThrow().inflate(0.25);

        try {
            CollisionFrame.end(level);
            CollisionOptimizerConfig.enableEntityCollision = false;
            QueryTrace expected = trace(level, query, fixtures.getFirst());

            CollisionOptimizerConfig.enableEntityCollision = true;
            CollisionFrame.begin(level);
            QueryTrace actual = trace(level, query, fixtures.getFirst());

            assertTraversal(helper, actual.all, expected.all, "untyped traversal");
            assertTraversal(helper, actual.typed, expected.typed, "typed traversal");
            assertLimited(helper, actual.limited, expected.typed);
            assertTraversal(helper, actual.outer, expected.outer, "reentrant outer traversal");
            assertTraversal(helper, actual.nested, expected.nested, "reentrant nested traversal");
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_ENTITY_QUERY_PARITY entities={} typed={} limited={} reentrant=true result=passed",
                    actual.all.size(), actual.typed.size(), actual.limited.size()
            );
        } finally {
            CollisionOptimizerConfig.enableEntityCollision = true;
            for (Entity fixture : fixtures) fixture.discard();
            CollisionFrame.end(level);
        }
    }

    private static QueryTrace trace(ServerLevel level, AABB query, Entity nestedExclusion) {
        List<Entity> all = level.getEntities((Entity) null, query, entity -> true);
        List<Zombie> typed = level.getEntities(ZOMBIES, query, entity -> true);
        List<Zombie> limited = new ArrayList<>();
        level.getEntities(ZOMBIES, query, entity -> true, limited, 2);

        List<Entity> nested = new ArrayList<>();
        boolean[] queried = {false};
        List<Entity> outer = level.getEntities((Entity) null, query, entity -> {
            if (!queried[0]) {
                queried[0] = true;
                nested.addAll(level.getEntities((Entity) null, query, candidate -> candidate != nestedExclusion));
            }
            return true;
        });
        return new QueryTrace(all, typed, limited, outer, nested);
    }

    private static void assertTraversal(
            GameTestHelper helper, List<? extends Entity> actual, List<? extends Entity> expected, String label
    ) {
        boolean matches = CollisionOptimizerConfig.STARTUP_VANILLA_ORDER
                ? actual.equals(expected)
                : actual.size() == expected.size() && identitySet(actual).equals(identitySet(expected));
        helper.assertTrue(matches, label + ": expected=" + ids(expected) + ", actual=" + ids(actual));
    }

    private static void assertLimited(
            GameTestHelper helper, List<? extends Entity> actual, List<? extends Entity> complete
    ) {
        int expectedSize = Math.min(2, complete.size());
        boolean matches = actual.size() == expectedSize && identitySet(complete).containsAll(actual);
        if (CollisionOptimizerConfig.STARTUP_VANILLA_ORDER) {
            matches &= actual.equals(complete.subList(0, expectedSize));
        }
        helper.assertTrue(matches, "abort traversal: complete=" + ids(complete) + ", actual=" + ids(actual));
    }

    private static List<Integer> ids(List<? extends Entity> entities) {
        return entities.stream().map(Entity::getId).toList();
    }

    private record QueryTrace(
            List<Entity> all,
            List<Zombie> typed,
            List<Zombie> limited,
            List<Entity> outer,
            List<Entity> nested
    ) {
    }
}

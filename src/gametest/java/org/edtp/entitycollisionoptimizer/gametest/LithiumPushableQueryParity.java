package org.edtp.entitycollisionoptimizer.gametest;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.adaptors.lithium.PushableQuery;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.collision.CollisionBodyAccess;
import org.edtp.entitycollisionoptimizer.mixin.PersistentEntitySectionManagerAccessor;
import org.edtp.entitycollisionoptimizer.mixin.ServerLevelAccessor;
import org.edtp.entitycollisionoptimizer.natives.NativePushQueryObservation;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnEntity;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;

/** Optional runtime integration: the reference traverses vanilla sections, never the native index. */
final class LithiumPushableQueryParity {
    private LithiumPushableQueryParity() {}

    static void verify(GameTestHelper helper) {
        if (!FabricLoader.getInstance().isModLoaded("lithium")) return;
        ServerLevel level = helper.getLevel();
        List<Entity> fixtures = new ArrayList<>();
        fixtures.add(spawnZombie(helper, new Vec3(1.5, 2, 1.5)));
        fixtures.add(spawnZombie(helper, new Vec3(17.5, 2, 1.5)));
        fixtures.add(spawnZombie(helper, new Vec3(33.5, 2, 1.5)));
        fixtures.add(spawnEntity(helper, EntityTypes.ARMOR_STAND, new Vec3(2, 2, 1.5)));
        fixtures.add(spawnEntity(helper, EntityTypes.OAK_BOAT, new Vec3(3, 2, 1.5)));
        fixtures.add(spawnEntity(helper, EntityTypes.MINECART, new Vec3(4, 2, 1.5)));
        Entity source = spawnZombie(helper, new Vec3(40.5, 2, 1.5));
        fixtures.add(source);
        AABB bounds = fixtures.getFirst().getBoundingBox().minmax(fixtures.get(2).getBoundingBox()).inflate(0.1);
        var scoreboard = level.getScoreboard();
        var team = scoreboard.addPlayerTeam("eco-li-" + UUID.randomUUID().toString().substring(0, 8));
        scoreboard.addPlayerToTeam(source.getScoreboardName(), team);
        try (var nativeQueries = new NativePushQueryObservation()) {
            Predicate<Entity> standard = EntitySelector.pushableBy(source);
            // With Lithium's optimization disabled, its marker/factory are absent and the
            // ordinary Level query remains in charge; this is an intentional supported case.
            if (!Class.forName("net.caffeinemc.mods.lithium.common.entity.pushable.EntityPushablePredicate")
                    .isInstance(standard)) return;
            helper.assertTrue(standard instanceof PushableQuery, "Lithium predicate bridge must be applied");
            PushableQuery captured = (PushableQuery) standard;
            helper.assertTrue(captured.eco$sourceTeam() == team
                    && captured.eco$sourceRule() == Team.CollisionRule.ALWAYS, "capture original factory locals");

            Method query = queryMethod();
            Object storage = ((PersistentEntitySectionManagerAccessor) (Object)
                    ((ServerLevelAccessor) (Object) level).eco$entityManager()).eco$sectionStorage();
            reference(level, source, bounds, standard);
            helper.assertValueEqual(nativeQueries.calls(), 0, "reference must not use the native push query");
            assertQuery(helper, query, storage, level, source, bounds, standard, "explicit expanded bounds");
            helper.assertTrue(nativeQueries.calls() > 0, "standard Lithium helper must reach the native push query");
            assertQuery(helper, query, storage, level, fixtures.getFirst(), bounds, standard, "exclusion differs from predicate source");
            assertQuery(helper, query, storage, level, null, bounds, standard, "null exclusion");
            assertQuery(helper, query, storage, level, source, bounds.move(0, 50, 0), standard, "empty query");

            team.setCollisionRule(Team.CollisionRule.NEVER);
            assertQuery(helper, query, storage, level, source, bounds, standard, "factory-captured rule is not reread");
            team.setCollisionRule(Team.CollisionRule.ALWAYS);
            scoreboard.addPlayerToTeam(fixtures.getFirst().getScoreboardName(), team);
            for (Team.CollisionRule rule : Team.CollisionRule.values()) {
                team.setCollisionRule(rule);
                if (rule != Team.CollisionRule.NEVER) {
                    assertQuery(helper, query, storage, level, source, bounds,
                            EntitySelector.pushableBy(source), "team rule " + rule);
                }
            }
            team.setCollisionRule(Team.CollisionRule.ALWAYS);

            int callsBeforeCustom = nativeQueries.calls();
            List<Entity> visits = new ArrayList<>();
            Predicate<Entity> custom = customPredicate(entity -> {
                visits.add(entity);
                // Re-enter the native index while the custom callback is running.
                level.getEntities(source, entity.getBoundingBox(), candidate -> true);
                return entity == fixtures.get(3) || entity == fixtures.get(1);
            });
            helper.assertTrue(((PushableQuery) custom).eco$sourceRule() == null,
                    "arbitrary Lithium markers must not acquire factory metadata");
            List<Entity> expected = reference(level, source, bounds, custom);
            List<Entity> expectedVisits = List.copyOf(visits);
            visits.clear();
            List<Entity> actual = query(query, storage, level, source, bounds, custom);
            helper.assertValueEqual(actual, expected, "custom predicate result/order");
            helper.assertValueEqual(visits, expectedVisits, "custom predicate callbacks/order");
            helper.assertTrue(actual.contains(fixtures.get(3)), "custom marker may accept an unpushable armor stand");
            helper.assertValueEqual(nativeQueries.calls(), callsBeforeCustom,
                    "custom marker must retain Lithium's path, not the native push query");

            // List collection must not create collision bodies, including for boats/minecarts.
            for (Entity fixture : fixtures) {
                helper.assertTrue(((CollisionBodyAccess) fixture).eco$positionBody().equals(MemorySegment.NULL),
                        "query-only path must leave bodies unbound");
            }
            EntityCollisionOptimizer.LOGGER.info(
                    "ECO_LITHIUM_QUERY_PARITY expanded_bounds=true captured_team=true custom_callbacks=true ordered=true query_only=true result=passed");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot exercise Lithium's pushable helper", failure);
        } finally {
            scoreboard.removePlayerTeam(team);
            fixtures.forEach(Entity::discard);
        }
    }

    private static void assertQuery(GameTestHelper helper, Method query, Object storage, ServerLevel level,
            Entity excluded, AABB bounds, Predicate<Entity> predicate, String scenario) throws ReflectiveOperationException {
        List<Entity> expected = reference(level, excluded, bounds, predicate);
        List<Entity> actual = query(query, storage, level, excluded, bounds, predicate);
        helper.assertValueEqual(actual, expected, "Lithium query parity: " + scenario);
    }

    private static List<Entity> reference(ServerLevel level, Entity excluded, AABB bounds, Predicate<Entity> predicate) {
        return VanillaEntityQueries.call(() -> level.getEntities(excluded, bounds,
                entity -> !entity.isSpectator() && !(entity instanceof EnderDragon) && predicate.test(entity)));
    }

    private static Method queryMethod() throws ReflectiveOperationException {
        return Class.forName("net.caffeinemc.mods.lithium.common.world.WorldHelper").getMethod("getPushableEntities",
                Level.class, EntitySectionStorage.class, Entity.class, AABB.class,
                Class.forName("net.caffeinemc.mods.lithium.common.entity.pushable.EntityPushablePredicate"));
    }

    @SuppressWarnings("unchecked")
    private static Predicate<Entity> customPredicate(Predicate<Entity> predicate) throws ReflectiveOperationException {
        return (Predicate<Entity>) Class.forName("net.caffeinemc.mods.lithium.common.entity.pushable.EntityPushablePredicate")
                .getMethod("and", Predicate.class, Predicate.class).invoke(null, (Predicate<Entity>) entity -> true, predicate);
    }

    @SuppressWarnings("unchecked")
    private static List<Entity> query(Method query, Object storage, ServerLevel level, Entity excluded,
            AABB bounds, Predicate<Entity> predicate) throws ReflectiveOperationException {
        try {
            return (List<Entity>) query.invoke(null, level, storage, excluded, bounds, predicate);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
}

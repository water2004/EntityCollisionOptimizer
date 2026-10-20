package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import org.edtp.entitycollisionoptimizer.collision.TypedEntityQueries;
import org.edtp.entitycollisionoptimizer.natives.NativeEntityQueryProbe;

import java.util.ArrayList;
import java.util.List;

/** Expectations come from fixture classes and independent untyped section order, not a second typed query. */
final class TypedEntityQueryChecks {
    static void verify(GameTestHelper helper, AABB query, List<Entity> sectionOrder) {
        var level = helper.getLevel();
        List<Zombie> zombies = sectionOrder.stream().filter(Zombie.class::isInstance).map(Zombie.class::cast).toList();
        helper.assertValueEqual(zombies.size(), 7, "typed fixture contains six zombies and one subclass");
        List<Zombie> exact = zombies.stream().filter(entity -> entity.getClass() == Zombie.class).toList();
        helper.assertValueEqual(exact.size(), 6, "exact-class fixture must exclude the husk");

        try (var observation = new NativeEntityQueryProbe()) {
            helper.assertValueEqual(level.getEntities(EntityTypeTest.forClass(Zombie.class), query, entity -> true),
                    zombies, "typed traversal retains subclass membership and section order");
            helper.assertTrue(observation.calls() <= 1, "a typed query never downcalls once per section");
        }
        // Exact and custom type tests retain Java filtering, regardless of native results.
        try (var injection = new NativeEntityQueryProbe(NativeEntityQueryProbe.Fault.EMPTY)) {
            helper.assertValueEqual(level.getEntities(EntityTypeTest.forExactClass(Zombie.class), query, entity -> true),
                    exact, "exact-class traversal must still apply tryCast");

            List<Entity> inspected = new ArrayList<>();
            Zombie selected = zombies.getLast();
            EntityTypeTest<Entity, Zombie> selective = new EntityTypeTest<>() {
                @Override public Class<? extends Entity> getBaseClass() { return Zombie.class; }
                @Override public Zombie tryCast(Entity entity) {
                    helper.assertTrue(entity instanceof Zombie, "unrelated classes must not reach typed filtering");
                    inspected.add(entity);
                    return entity == selected ? selected : null;
                }
            };
            helper.assertValueEqual(level.getEntities(selective, query, entity -> true), List.of(selected),
                    "custom type filtering must not be replaced by the base-class collection");
            helper.assertValueEqual(inspected, zombies, "only the typed collection is inspected, in section order");

            verifyAbort(helper, query, zombies);
            helper.assertValueEqual(injection.calls(), 0, "custom and exact-class queries must not reach native");
        }

        try (var observation = new NativeEntityQueryProbe()) {
            AABB narrow = zombies.getFirst().getBoundingBox();
            helper.assertValueEqual(level.getEntities(EntityTypeTest.forClass(Zombie.class), narrow, entity -> true),
                    zombies.stream().filter(entity -> entity.getBoundingBox().intersects(narrow)).toList(),
                    "the typed collection must still be filtered by the query bounds");
            List<Zombie> nested = new ArrayList<>();
            Zombie anchor = zombies.getLast();
            List<Zombie> outer = level.getEntities(EntityTypeTest.forClass(Zombie.class), query, entity -> {
                if (nested.isEmpty()) {
                    nested.addAll(level.getEntities(EntityTypeTest.forClass(Zombie.class),
                            anchor.getBoundingBox(), candidate -> true));
                }
                return true;
            });
            helper.assertValueEqual(outer, zombies, "nested typed query must not disturb the outer traversal");
            helper.assertValueEqual(nested, List.of(anchor), "nested query retains its own spatial bounds");
            helper.assertTrue(observation.calls() <= 3, "three queries use at most three native batches");
        }
        verifySelectivePlan(helper, query, sectionOrder);
        verifyNativeAbort(helper, query, sectionOrder);
        for (var fault : NativeEntityQueryProbe.Fault.values()) {
            try (var injection = new NativeEntityQueryProbe(fault)) {
                List<Entity> corrupted = level.getEntities(EntityTypeTest.forClass(Entity.class), query, entity -> true);
                helper.assertTrue(injection.calls() > 0 && !corrupted.equals(sectionOrder),
                        "independent fixture order must detect dense native query fault " + fault);
            }
        }
    }

    private static void verifyAbort(GameTestHelper helper, AABB query, List<Zombie> zombies) {
        long firstSection = SectionPos.of(zombies.getFirst()).asLong();
        int firstSectionSize = (int) zombies.stream()
                .takeWhile(entity -> SectionPos.of(entity).asLong() == firstSection).count();
        int limit = firstSectionSize + 1;
        helper.assertTrue(limit < zombies.size(), "abort fixture must leave a later section unvisited");
        List<Zombie> inspected = new ArrayList<>();
        EntityTypeTest<Entity, Zombie> observed = new EntityTypeTest<>() {
            @Override public Class<? extends Entity> getBaseClass() { return Zombie.class; }
            @Override public Zombie tryCast(Entity entity) {
                helper.assertTrue(entity instanceof Zombie, "abort query uses the typed collection");
                inspected.add((Zombie) entity);
                return (Zombie) entity;
            }
        };
        List<Zombie> limited = new ArrayList<>();
        helper.getLevel().getEntities(observed, query, entity -> true, limited, limit);
        List<Zombie> prefix = zombies.subList(0, limit);
        helper.assertValueEqual(limited, prefix, "limited query returns the section-order prefix");
        helper.assertValueEqual(inspected, prefix, "abort stops filtering as well as callbacks across sections");
    }

    private static void verifyNativeAbort(GameTestHelper helper, AABB query, List<Entity> sectionOrder) {
        List<Entity> limited = new ArrayList<>();
        try (var observation = new NativeEntityQueryProbe()) {
            helper.getLevel().getEntities(EntityTypeTest.forClass(Entity.class), query, entity -> true, limited, 1);
            helper.assertValueEqual(limited, sectionOrder.subList(0, 1), "native-backed typed abort retains order");
            helper.assertValueEqual(observation.calls(), 1, "a dense query uses one native batch");
            helper.assertValueEqual(observation.resultSizes(), List.of(sectionOrder.size()),
                    "dense native queries preserve the existing batch-before-callback behavior");
        }
    }

    private static void verifySelectivePlan(GameTestHelper helper, AABB query, List<Entity> sectionOrder) {
        var storage = new EntitySectionStorage<Entity>(Entity.class, ignored -> Visibility.TRACKED);
        var section = storage.getOrCreateSection(SectionPos.of(sectionOrder.getFirst()).asLong());
        Entity zombie = sectionOrder.stream().filter(Zombie.class::isInstance).findFirst().orElseThrow();
        Entity stand = sectionOrder.stream().filter(entity -> !(entity instanceof Zombie)).findFirst().orElseThrow();
        section.add(zombie);
        section.add(stand);
        try (var observation = new NativeEntityQueryProbe()) {
            var type = EntityTypeTest.<Entity, Zombie>forClass(Zombie.class);
            helper.assertTrue(!TypedEntityQueries.query(helper.getLevel(), storage, type, query, entity -> {
                throw new AssertionError("Selective plans must defer to original Java traversal");
            }), "a selective class collection retains the original method");
            section.remove(zombie);
            helper.assertTrue(TypedEntityQueries.query(helper.getLevel(), storage, type, query, entity -> {
                throw new AssertionError("An absent class must not invoke the consumer");
            }), "an empty class query is complete without native");
            helper.assertValueEqual(observation.calls(), 0, "selective and empty plans do not invoke native");
        }
    }

    private TypedEntityQueryChecks() {}
}

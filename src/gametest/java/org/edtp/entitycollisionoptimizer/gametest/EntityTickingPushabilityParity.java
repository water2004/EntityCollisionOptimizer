package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.function.Predicate;

/** Stationary entities in a loaded but non-ticking chunk must stay queryable. */
final class EntityTickingPushabilityParity {
    static void verify(GameTestHelper helper) {
        var level = helper.getLevel();
        // Stay outside the structure's tickets, but follow its fresh per-run origin.
        var origin = helper.absolutePos(BlockPos.ZERO);
        var anchor = new ChunkPos((origin.getX() >> 4) + 64, (origin.getZ() >> 4) + 64);
        var targetChunk = new ChunkPos(anchor.x + 1, anchor.z);
        var position = targetChunk.getWorldPosition().offset(8, 100, 8);
        Zombie[] entities = new Zombie[2];
        Runnable cleanup = () -> {
            for (Zombie entity : entities) if (entity != null) entity.discard();
            force(level, anchor, false);
            CollisionFrame.end(level);
        };
        // 1.21.11's GameTestHelper has no runBeforeTestEnd hook, so each step that can fail
        // releases the fixtures itself instead of relying on a test-teardown callback.
        // Forcing the neighbouring chunk loads the target as an accessible border chunk that does
        // not tick entities: exactly the state 1.21.11 still exposes to entity queries.
        var sequence = helper.startSequence();
        sequence.thenExecute(() -> force(level, anchor, true))
                .thenWaitUntil(cleanupOnFailure(cleanup, () -> {
                    helper.assertTrue(level.shouldTickBlocksAt(position), "weak chunk must be loaded");
                    helper.assertTrue(!level.isPositionEntityTicking(position), "weak chunk must not tick entities");
                })).thenExecute(cleanupOnFailure(cleanup, () -> {
                    for (int i = 0; i < entities.length; i++) {
                        Zombie entity = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                        helper.assertTrue(entity != null, "zombie fixture");
                        entity.setPos(position.getX() + 0.4 + i * 0.1, position.getY(), position.getZ() + 0.5);
                        entity.setNoAi(true);
                        entity.setNoGravity(true);
                        entity.setPersistenceRequired();
                        helper.assertTrue(level.addFreshEntity(entity), "spawn stationary zombie");
                        entities[i] = entity;
                    }
                }))
                // A fresh entity only enters the section storage on a later manager tick; wait for
                // the tracking callback instead of assuming a fixed delay. No cleanup wrapper here:
                // the condition is expected to become true, so a failing attempt must not discard
                // the fixtures the next attempt needs.
                .thenWaitUntil(() -> helper.assertTrue(candidateCount(entities[0]) == 1,
                        "tracked push candidate in a non-ticking chunk"))
                .thenExecute(() -> {
                    // Once tracked, a non-ticking chunk must still expose it as a push candidate.
                    check(helper, entities, 1, "weak chunk", cleanup);
                    helper.assertTrue(!level.isPositionEntityTicking(position), "chunk must stay non-ticking");
                    cleanup.run();
                }).thenSucceed();
    }

    private static int candidateCount(Zombie source) {
        try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
            return batch.size();
        }
    }

    private static void force(ServerLevel level, ChunkPos chunk, boolean forced) {
        level.setChunkForced(chunk.x, chunk.z, forced);
    }

    private static Runnable cleanupOnFailure(Runnable cleanup, Runnable step) {
        return () -> {
            try {
                step.run();
            } catch (RuntimeException | Error failure) {
                cleanup.run();
                throw failure;
            }
        };
    }

    private static void check(GameTestHelper helper, Zombie[] entities, int expected, String state, Runnable cleanup) {
        try {
            check(helper, entities, expected, state);
        } catch (RuntimeException | Error failure) {
            cleanup.run();
            throw failure;
        }
    }

    /**
     * 1.21.11 derives living-entity pushability from state alone ({@code isAlive},
     * spectator, climbing); chunk ticking only decides whether the entity is tracked.
     * The invariant under a ticket transition is therefore that the cached value keeps
     * agreeing with the live vanilla predicate, and that ticket changes neither add nor
     * drop native candidates once the neighbour is tracked.
     */
    private static void check(GameTestHelper helper, Zombie[] entities, int expected, String state) {
        Zombie source = entities[0];
        Predicate<Entity> selector = EntitySelector.pushableBy(source);
        int pushable = 0;
        for (Zombie entity : entities) {
            helper.assertValueEqual(entity.isPushable(), selector.test(entity),
                    state + " vanilla pushability predicate");
            helper.assertValueEqual(((CollisionCacheState) entity).entityCollisionOptimizer$isPushableCached(),
                    selector.test(entity), state + " cached pushability");
            if (entity != source && selector.test(entity)) pushable++;
        }
        helper.assertValueEqual(pushable, 1, state + " fixture pushable neighbour");
        try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
            helper.assertValueEqual(batch.size(), expected, state + " native candidate eligibility");
        }
    }
}

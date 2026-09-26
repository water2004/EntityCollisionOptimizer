package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

/** Change chunk tickets around stationary entities, without relying on movement invalidation. */
final class EntityTickingPushabilityParity {
    static void verify(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunks = level.getChunkSource();
        // Stay outside the structure's tickets, but follow its fresh per-run origin.
        var origin = helper.absolutePos(BlockPos.ZERO);
        var anchor = new ChunkPos((origin.getX() >> 4) + 64, (origin.getZ() >> 4) + 64);
        var targetChunk = new ChunkPos(anchor.x() + 1, anchor.z());
        var position = targetChunk.getWorldPosition().offset(8, 100, 8);
        Zombie[] entities = new Zombie[2];
        Runnable cleanup = () -> {
            for (Zombie entity : entities) if (entity != null) entity.discard();
            chunks.removeTicketWithRadius(TicketType.FORCED, targetChunk, 2);
            chunks.removeTicketWithRadius(TicketType.FORCED, anchor, 2);
            CollisionFrame.end(level);
        };
        helper.runBeforeTestEnd(cleanup);
        var sequence = helper.startSequence();
        sequence.thenExecute(() -> chunks.addTicketAndLoadWithRadius(TicketType.FORCED, anchor, 2))
                .thenWaitUntil(() -> {
                    helper.assertTrue(level.shouldTickBlocksAt(position), "weak chunk must be loaded");
                    helper.assertTrue(!level.isPositionEntityTicking(position), "weak chunk must not tick entities");
                }).thenExecute(() -> {
                    for (int i = 0; i < entities.length; i++) {
                        Zombie entity = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                        helper.assertTrue(entity != null, "zombie fixture");
                        entity.setPos(position.getX() + 0.4 + i * 0.1, position.getY(), position.getZ() + 0.5);
                        entity.setNoAi(true);
                        entity.setNoGravity(true);
                        entity.setPersistenceRequired();
                        helper.assertTrue(level.addFreshEntity(entity), "spawn stationary zombie");
                        entities[i] = entity;
                    }
                    check(helper, entities, false, cleanup);
                    var loaded = chunks.addTicketAndLoadWithRadius(TicketType.FORCED, targetChunk, 2);
                    level.getServer().managedBlock(loaded::isDone);
                    loaded.join();
                }).thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(position), "promoted chunk"))
                .thenExecute(() -> {
                    check(helper, entities, true, cleanup);
                    chunks.removeTicketWithRadius(TicketType.FORCED, targetChunk, 2);
                }).thenWaitUntil(() -> helper.assertTrue(!level.isPositionEntityTicking(position), "demoted chunk"))
                .thenExecute(() -> {
                    check(helper, entities, false, cleanup);
                    cleanup.run();
                }).thenSucceed();
    }

    private static void check(GameTestHelper helper, Zombie[] entities, boolean ticking, Runnable cleanup) {
        try {
            check(helper, entities, ticking);
        } catch (RuntimeException | Error failure) {
            cleanup.run();
            throw failure;
        }
    }

    private static void check(GameTestHelper helper, Zombie[] entities, boolean ticking) {
        for (Zombie entity : entities) {
            helper.assertValueEqual(entity.isPushable(), ticking, "vanilla ticking pushability");
            helper.assertValueEqual(((CollisionCacheState) entity).entityCollisionOptimizer$isPushableCached(),
                    ticking, "cached ticking pushability");
        }
        try (var batch = CollisionFrame.collectPushable(entities[0], null, Team.CollisionRule.ALWAYS, true)) {
            helper.assertValueEqual(batch.size(), ticking ? 1 : 0, "native ticking candidate eligibility");
        }
    }
}

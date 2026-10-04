package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.edtp.entitycollisionoptimizer.collision.CollisionCacheState;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Construction and bounds publication must not wait for chunks or the level's index lock. */
public final class IndexPublicationChecks {
    private IndexPublicationChecks() { }

    @SuppressWarnings("unchecked")
    public static void verify(GameTestHelper helper) {
        var level = helper.getLevel();
        CollisionFrame.begin(level);
        try {
            Field registry = CollisionFrame.class.getDeclaredField("LEVEL_FRAMES");
            registry.setAccessible(true);
            var frame = ((Map<?, LevelCollisionFrame>) registry.get(null)).get(level);
            FutureTask<Zombie> construction = new FutureTask<>(() -> {
                Zombie entity = new Zombie(EntityType.ZOMBIE, level);
                entity.setPos(1, 200, 1);
                return entity;
            });
            Thread worker = Thread.ofPlatform().daemon().name("eco-worldgen-construction-test").unstarted(construction);
            Zombie entity;
            synchronized (frame) {
                worker.start();
                // A chunk worker must finish construction even while the level holds its frame lock.
                entity = construction.get(3, TimeUnit.SECONDS);
            }
            var state = (CollisionCacheState) entity;
            helper.assertTrue(!state.entityCollisionOptimizer$isIndexed(), "construction must not enter the index");
            frame.addEntity(entity);
            try {
                helper.assertTrue(state.entityCollisionOptimizer$isIndexed(), "insertion must publish membership");
                int remoteX = 1_000_000;
                int remoteZ = 1_000_000;
                int chunkX = SectionPos.blockToSectionCoord(remoteX);
                int chunkZ = SectionPos.blockToSectionCoord(remoteZ);
                helper.assertTrue(!level.getChunkSource().hasChunk(chunkX, chunkZ), "fixture requires an unloaded chunk");
                entity.setPos(remoteX, 200, remoteZ);
                helper.assertTrue(!level.getChunkSource().hasChunk(chunkX, chunkZ),
                        "publishing indexed bounds must not load a chunk to evaluate pushability");
            } finally {
                frame.removeEntity(entity);
            }
            helper.assertTrue(!state.entityCollisionOptimizer$isIndexed(), "removal must clear membership");
            frame.addEntity(entity);
            helper.assertTrue(state.entityCollisionOptimizer$isIndexed(), "reinsertion must publish membership");
            TestFrameAccess.destroy(level);
            helper.assertTrue(!state.entityCollisionOptimizer$isIndexed(), "frame close must clear membership");
        } catch (Exception failure) {
            throw new AssertionError("Index publication contract failed", failure);
        } finally {
            CollisionFrame.end(level);
        }
    }
}

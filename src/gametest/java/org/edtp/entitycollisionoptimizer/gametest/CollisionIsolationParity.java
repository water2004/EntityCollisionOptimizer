package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.collision.VanillaMethodDetector;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.identitySet;

final class CollisionIsolationParity {
    private CollisionIsolationParity() {
    }

    static void verify(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(nether != null, "Nether level must be available");
        helper.assertTrue(end != null, "End level must be available");

        List<LevelQueryGroup> groups = List.of(
                createLevelQueryGroup(overworld, 3, 1_000_000.5),
                createLevelQueryGroup(nether, 5, 1_001_000.5),
                createLevelQueryGroup(end, 7, 1_002_000.5)
        );
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(groups.size())) {
            List<Future<?>> futures = new ArrayList<>();
            for (LevelQueryGroup group : groups) {
                futures.add(executor.submit(() -> {
                    start.await();
                    verifyGroup(group, 300);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent level collision test was interrupted", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Concurrent level collision test failed", failure);
        } finally {
            for (LevelQueryGroup group : groups) {
                for (Entity entity : group.entities()) entity.discard();
                CollisionFrame.end(group.level());
            }
        }
    }

    private static LevelQueryGroup createLevelQueryGroup(
            ServerLevel level,
            int entityCount,
            double coordinate
    ) {
        // Reset the fixture explicitly; ending a tick no longer discards persistent members.
        CollisionFrame.suspend(level);
        CollisionFrame.begin(level);
        List<Zombie> entities = new ArrayList<>(entityCount);
        Vec3 position = new Vec3(coordinate, 64.0, coordinate);
        for (int index = 0; index < entityCount; index++) {
            Zombie zombie = new WorldlessPushableZombie(level);
            zombie.setPos(position.add(index * 0.03, 0, index * 0.02));
            CollisionFrame.addEntity(zombie);
            entities.add(zombie);
        }
        return new LevelQueryGroup(level, List.copyOf(entities));
    }

    private static void verifyGroup(LevelQueryGroup group, int iterations) {
        List<Zombie> entities = group.entities();
        Set<Entity> expected = identitySet(new ArrayList<>(entities));
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (Zombie source : entities) {
                FFMBackend.QueryResult spatial = CollisionFrame.query(source);
                if (spatial.size() != entities.size() - 1) {
                    throw new AssertionError(
                            group.level().dimension().identifier()
                                    + " spatial result size was " + spatial.size()
                    );
                }
                Set<Entity> observed = identitySet(List.of());
                for (int index = 0; index < spatial.size(); index++) {
                    Entity candidate = CollisionFrame.entity(source, spatial.get(index));
                    if (candidate == source || !expected.contains(candidate) || !observed.add(candidate)) {
                        throw new AssertionError(
                                group.level().dimension().identifier()
                                        + " returned a foreign or duplicate entity"
                        );
                    }
                }

                PlayerTeam sourceTeam = source.getTeam();
                FFMBackend.QueryResult pushable = CollisionFrame.queryPushable(
                        source,
                        sourceTeam,
                        sourceTeam == null
                                ? Team.CollisionRule.ALWAYS : sourceTeam.getCollisionRule(),
                        VanillaMethodDetector.usesVanillaDoPush(source)
                );
                int expectedPushable = entities.size() - 1;
                if (pushable.pushableCount() != expectedPushable
                        || pushable.nonPassengerCount() != expectedPushable) {
                    throw new AssertionError(
                            group.level().dimension().identifier()
                                    + " pushable counts were " + pushable.pushableCount()
                                    + "/" + pushable.nonPassengerCount()
                    );
                }
                verifyNativeBatch(group, source);
            }
        }
    }

    private static void verifyNativeBatch(LevelQueryGroup group, Zombie source) {
        List<Zombie> entities = group.entities();
        for (Zombie entity : entities) entity.setDeltaMovement(Vec3.ZERO);
        for (Zombie target : entities) if (target != source) target.push(source);
        Vec3[] expected = new Vec3[entities.size()];
        for (int i = 0; i < entities.size(); i++) {
            expected[i] = entities.get(i).getDeltaMovement();
            entities.get(i).setDeltaMovement(Vec3.ZERO);
        }
        try (var batch = CollisionFrame.collectPushable(source, source.getTeam(),
                source.getTeam() == null
                        ? Team.CollisionRule.ALWAYS : source.getTeam().getCollisionRule(), true)) {
            for (int i = 0; i < batch.size(); i++) {
                if (!batch.usesNativePush(i) || !entities.contains(batch.target(i))) {
                    throw new AssertionError("Concurrent batch contains a foreign or non-native target");
                }
            }
            batch.applyNativeRun(source, 0, batch.size());
        }
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i).getDeltaMovement().distanceToSqr(expected[i]) > 1.0E-24) {
                throw new AssertionError("Concurrent native impulse buffer contamination in "
                        + group.level().dimension().identifier());
            }
        }
    }

    private record LevelQueryGroup(ServerLevel level, List<Zombie> entities) {
    }

    /** Avoids touching chunks from the worker threads used by the isolation test. */
    private static final class WorldlessPushableZombie extends Zombie {
        private WorldlessPushableZombie(ServerLevel level) {
            super(level);
        }

        @Override
        public boolean isPushable() {
            return true;
        }
    }
}

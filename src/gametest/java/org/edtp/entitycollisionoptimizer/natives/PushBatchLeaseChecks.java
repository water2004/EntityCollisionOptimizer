package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.collision.CollisionBodyAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Exercises real batch ownership without depending on a parallel-ticking mod. */
public final class PushBatchLeaseChecks {
    private PushBatchLeaseChecks() {}

    public static void release(GameTestHelper helper) throws ReflectiveOperationException {
        try (var fixture = new Fixture(helper); var batch = fixture.collect()) {
            helper.assertValueEqual(batch.size(), 1, "lease fixture has one target");
            int slot = ((CollisionBodyAccess) (Object) fixture.target).eco$boundBodySlot(fixture.bodies);
            fixture.frame.removeEntity(fixture.target);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread closer = worker("eco-lease-close", batch::close, failure);
            int during;
            boolean retained;
            try {
                synchronized (fixture.frame) {
                    closer.start();
                    awaitBlocked(closer);
                    during = fixture.borrowers();
                    retained = fixture.bodies.bound(slot);
                }
            } finally {
                finish(closer, failure);
            }
            helper.assertValueEqual(during, 1, "lease release must wait for its owning frame");
            helper.assertTrue(retained, "retired rows remain pinned while release waits");
            helper.assertValueEqual(fixture.borrowers(), 0, "close releases exactly one lease");
            helper.assertTrue(!fixture.bodies.bound(slot), "last release retires the target row");
            batch.close();
            helper.assertValueEqual(fixture.borrowers(), 0, "closing twice does not release twice");
            try (var reused = fixture.collect()) {
                helper.assertTrue(reused == batch, "released batch returns to its owner's pool");
                helper.assertValueEqual(fixture.borrowers(), 1, "reused batch borrows again");
                try {
                    fixture.bodies.clear();
                    throw new AssertionError("A borrowed table must not be cleared");
                } catch (IllegalStateException expected) {
                    helper.assertValueEqual(expected.getMessage(), "Clearing a borrowed collision table",
                            "borrowed-table lifetime guard is preserved");
                }
            }
        }
    }

    public static void nativeRun(GameTestHelper helper) throws ReflectiveOperationException {
        try (var fixture = new Fixture(helper); var batch = fixture.collect()) {
            helper.assertValueEqual(batch.size(), 1, "native lease fixture has one target");
            helper.assertTrue(batch.usesNativePush(0), "fixture exercises native pushing");
            Field execute = field(FFMBackend.class, "executeRun");
            MethodHandle original = (MethodHandle) execute.get(null);
            AtomicInteger calls = new AtomicInteger();
            MethodHandle check = MethodHandles.lookup().findStatic(PushBatchLeaseChecks.class, "nativeEntry",
                    MethodType.methodType(void.class, LevelCollisionFrame.class, AtomicInteger.class))
                    .bindTo(fixture.frame).bindTo(calls);
            execute.set(null, MethodHandles.foldArguments(original, check));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread runner = worker("eco-lease-native", () -> batch.applyNativeRun(fixture.source, 0, 1), failure);
            try {
                try {
                    synchronized (fixture.frame) {
                        runner.start();
                        awaitBlocked(runner);
                        var previous = fixture.bodies.memory();
                        int capacity = fixture.bodies.capacity();
                        for (int index = 0; index < capacity; index++) fixture.addTarget();
                        try (var expanded = fixture.collect()) {
                            helper.assertTrue(fixture.bodies.capacity() > capacity, "another lease grows the table");
                            helper.assertTrue(!previous.scope().isAlive(), "growth closes the previous arena");
                        }
                    }
                } finally {
                    finish(runner, failure);
                }
            } finally {
                execute.set(null, original);
            }
            helper.assertValueEqual(calls.get(), 1, "the waiting run executes its real downcall once");
            helper.assertTrue(fixture.source.getDeltaMovement().horizontalDistanceSqr() > 0,
                    "the original batch still pushes after table growth");
            helper.assertValueEqual(fixture.borrowers(), 1, "native execution retains the original lease");
        }
    }

    private static void nativeEntry(LevelCollisionFrame frame, AtomicInteger calls) {
        if (!Thread.holdsLock(frame)) throw new AssertionError("Native rows used outside the owning frame lock");
        calls.incrementAndGet();
    }

    private static Thread worker(String name, Runnable action, AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().daemon().name(name).unstarted(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
    }

    private static void awaitBlocked(Thread worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (worker.isAlive() && worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            LockSupport.parkNanos(100_000);
        }
        if (worker.getState() != Thread.State.BLOCKED) throw new AssertionError("Batch did not wait for its frame");
    }

    private static void finish(Thread worker, AtomicReference<Throwable> failure) {
        try {
            worker.join(3000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for batch worker", error);
        }
        if (worker.isAlive()) throw new AssertionError("Batch worker did not finish");
        if (failure.get() != null) throw new AssertionError("Batch worker failed", failure.get());
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture implements AutoCloseable {
        final LevelCollisionFrame frame = new LevelCollisionFrame();
        final CollisionStateTable bodies;
        final Probe source, target;

        Fixture(GameTestHelper helper) throws ReflectiveOperationException {
            // Isolated synthetic membership must not bootstrap or reset the world's real frame.
            field(LevelCollisionFrame.class, "initialized").setBoolean(frame, true);
            bodies = (CollisionStateTable) field(LevelCollisionFrame.class, "bodies").get(frame);
            source = new Probe(helper.getLevel());
            source.setPos(helper.absoluteVec(new Vec3(2, 2, 2)));
            frame.addEntity(source);
            target = addTarget();
        }

        Probe addTarget() {
            Probe entity = new Probe((ServerLevel) source.level());
            entity.setPos(source.position().add(.1, 0, .1));
            frame.addEntity(entity);
            return entity;
        }

        PushBatch collect() { return frame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true); }
        int borrowers() throws IllegalAccessException, NoSuchFieldException {
            return field(CollisionStateTable.class, "borrowers").getInt(bodies);
        }
        @Override public void close() { frame.close(); }
    }

    private static final class Probe extends Zombie {
        Probe(ServerLevel level) { super(level); }
        @Override public boolean isPushable() { return true; }
    }
}

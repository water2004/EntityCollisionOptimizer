package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityVelocityAccessor;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** A test-only observer at a real vanilla boat callback, between two ordinary push runs. */
public final class PushRunBoundaryParity {
    private static final ThreadLocal<Runnable> OBSERVER = new ThreadLocal<>();

    public static void observe() {
        Runnable observer = OBSERVER.get();
        if (observer != null) observer.run();
    }

    static void verify(GameTestHelper helper) {
        var expected = run(helper, false);
        var actual = run(helper, true);
        helper.assertValueEqual(actual.size(), expected.size(), "special push observation count");
        for (int i = 0; i < actual.size(); i++) {
            NativeImpulseParity.exact(helper, actual.get(i).velocity, expected.get(i).velocity, "special boundary raw velocity " + i);
            helper.assertValueEqual(actual.get(i).sync, expected.get(i).sync, "special boundary sync " + i);
        }
    }

    private static List<State> run(GameTestHelper helper, boolean enabled) {
        try (var scene = new InteractionScene(helper)) {
            List<Entity> entities = new ArrayList<>();
            entities.add(scene.spawn(EntityType.ZOMBIE, new Vec3(4.5, 1, 4.5)));
            entities.add(scene.spawn(EntityType.ZOMBIE, new Vec3(4.6, 1, 4.6)));
            entities.add(scene.spawn(EntityType.OAK_BOAT, new Vec3(4.55, 1, 4.55)));
            entities.add(scene.spawn(EntityType.ZOMBIE, new Vec3(4.65, 1, 4.6)));
            var source = (LivingEntity) entities.getFirst();
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).setDeltaMovement(new Vec3(.125 * i, -0.0, -.125 * i));
                entities.get(i).needsSync = false;
            }
            CollisionFrame.begin(helper.getLevel());
            if (enabled) try (var batch = CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true)) {
                helper.assertValueEqual(batch.size(), 3, "ordinary/boat/ordinary candidates");
                for (int i = 0; i < 3; i++) {
                    helper.assertTrue(batch.target(i) == entities.get(i + 1), "fixture must retain insertion order");
                    helper.assertValueEqual(batch.usesNativePush(i), i != 1, "two native runs around special boat push");
                }
            }
            List<State> states = new ArrayList<>();
            OBSERVER.set(() -> snapshot(entities, states));
            try {
                ((LivingEntityTestInvoker) source).entityCollisionOptimizer$invokePushEntities();
            } finally {
                OBSERVER.remove();
            }
            helper.assertValueEqual(states.size(), 4, "exactly one real boat callback observed");
            snapshot(entities, states);
            return states;
        }
    }

    private static void snapshot(List<Entity> entities, List<State> states) {
        for (Entity entity : entities) states.add(new State(((EntityVelocityAccessor) entity).eco$rawVelocity(), entity.needsSync));
    }

    private record State(Vec3 velocity, boolean sync) {}
}

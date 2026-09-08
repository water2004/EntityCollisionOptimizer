package org.edtp.entitycollisionoptimizer.gametest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Observations after real server ticks, with fixture translation removed. */
final class ChunkEntityTrace {
    record State(Vec3 position, Vec3 velocity, AABB box, int ticks,
                 boolean ground, boolean horizontal, boolean vertical, boolean removed) {
        static State capture(Entity entity, Vec3 origin) {
            return new State(entity.position().subtract(origin), entity.getDeltaMovement(),
                    entity.getBoundingBox().move(-origin.x, -origin.y, -origin.z), entity.tickCount,
                    entity.onGround(), entity.horizontalCollision, entity.verticalCollision, entity.isRemoved());
        }
        void compare(GameTestHelper helper, State expected, String label) {
            CollisionTestSupport.assertVectorEqual(helper, position, expected.position, label + " position");
            CollisionTestSupport.assertVectorEqual(helper, velocity, expected.velocity, label + " velocity");
            CollisionTestSupport.assertVectorEqual(helper, box.getMinPosition(), expected.box.getMinPosition(), label + " box min");
            CollisionTestSupport.assertVectorEqual(helper, box.getMaxPosition(), expected.box.getMaxPosition(), label + " box max");
            helper.assertValueEqual(ticks, expected.ticks, label + " entity ticks");
            helper.assertValueEqual(ground, expected.ground, label + " ground");
            helper.assertValueEqual(horizontal, expected.horizontal, label + " horizontal collision");
            helper.assertValueEqual(vertical, expected.vertical, label + " vertical collision");
            helper.assertValueEqual(removed, expected.removed, label + " removed");
        }
    }

    private final List<List<State>> samples = new ArrayList<>();
    State last(int entity) { return samples.getLast().get(entity); }
    void record(Vec3 origin, Entity... entities) {
        samples.add(java.util.Arrays.stream(entities).map(entity -> State.capture(entity, origin)).toList());
    }
    void compare(GameTestHelper helper, ChunkEntityTrace expected) {
        helper.assertValueEqual(samples.size(), expected.samples.size(), "chunk trace length");
        for (int tick = 0; tick < samples.size(); tick++)
            for (int entity = 0; entity < samples.get(tick).size(); entity++)
                samples.get(tick).get(entity).compare(helper, expected.samples.get(tick).get(entity),
                        "chunk trace tick=" + tick + " entity=" + entity);
    }
}

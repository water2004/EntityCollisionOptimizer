package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.assertEntityOutcomeMatches;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;
import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.zeroVelocities;

final class CollisionRepeatedFrameParity {
    private CollisionRepeatedFrameParity() {
    }

    static void verify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Zombie> vanilla = new ArrayList<>();
        List<Zombie> accelerated = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                Vec3 offset = offset(index, 0);
                vanilla.add(spawnZombie(helper, new Vec3(18.5, 1.0, 25.0).add(offset)));
                accelerated.add(spawnZombie(helper, new Vec3(30.5, 1.0, 25.0).add(offset)));
            }

            for (int frame = 0; frame < 4; frame++) {
                for (int index = 0; index < vanilla.size(); index++) {
                    Vec3 offset = offset(index, frame);
                    vanilla.get(index).setPos(helper.absoluteVec(
                            new Vec3(18.5, 1.0, 25.0).add(offset)
                    ));
                    accelerated.get(index).setPos(helper.absoluteVec(
                            new Vec3(30.5, 1.0, 25.0).add(offset)
                    ));
                    float health = frame == 1 && index == vanilla.size() - 1
                            ? 0.0F
                            : vanilla.get(index).getMaxHealth();
                    vanilla.get(index).setHealth(health);
                    accelerated.get(index).setHealth(health);
                }
                zeroVelocities(vanilla);
                zeroVelocities(accelerated);

                CollisionFrame.end(level);
                for (Zombie source : vanilla) {
                    if (source.isAlive()) {
                        VanillaReference.pushEntities(source);
                    }
                }

                CollisionFrame.begin(level);
                for (Zombie source : accelerated) {
                    CollisionPredicateParity.assertSpatialQueryMatches(
                            helper,
                            source,
                            new ArrayList<>(accelerated),
                            "repeated frame " + frame + " candidates"
                    );
                }
                for (Zombie source : accelerated) {
                    if (source.isAlive()) {
                        ((LivingEntityTestInvoker) source).entityCollisionOptimizer$invokePushEntities();
                    }
                }

                for (int index = 0; index < vanilla.size(); index++) {
                    assertEntityOutcomeMatches(
                            helper,
                            vanilla.get(index),
                            accelerated.get(index),
                            "repeated frame " + frame + " entity " + index
                    );
                }
            }
        } finally {
            for (Zombie entity : vanilla) {
                entity.discard();
            }
            for (Zombie entity : accelerated) {
                entity.discard();
            }
            CollisionFrame.end(level);
        }
    }

    private static Vec3 offset(int index, int frame) {
        double angle = index * (Math.PI * 2.0 / 6.0) + frame * 0.17;
        double radius = index == 5 && frame >= 2 ? 0.7 : 0.22 + frame * 0.02;
        return new Vec3(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
    }
}

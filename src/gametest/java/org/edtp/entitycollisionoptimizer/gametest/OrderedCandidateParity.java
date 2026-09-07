package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** Real section storage is the oracle, including reentry and shared multi-cell candidate streams. */
final class OrderedCandidateParity {
    static void verify(GameTestHelper helper) {
        for (int count : new int[]{2, 8, 20}) try (var scene = new InteractionScene(helper, true)) {
            List<LivingEntity> entities = new ArrayList<>();
            Vec3 origin = helper.absoluteVec(new Vec3(4, 2, 4));
            double x = Math.floor(origin.x / 16) * 16 + 16, z = Math.floor(origin.z / 16) * 16 + 16;
            for (int i = 0; i < count; i++) {
                var entity = (LivingEntity) scene.spawn(EntityTypes.ZOMBIE, new Vec3(4, 2, 4));
                int shuffled = (i * 7) % count;
                entity.setPos(x + (shuffled % 5 - 2) * .07, origin.y, z + (shuffled / 5 - 2) * .07);
                entities.add(entity);
            }
            CollisionFrame.begin(helper.getLevel());
            for (int phase = 0; phase < 12; phase++) {
                Entity changed = entities.get(phase % count);
                Vec3 position = changed.position();
                changed.setPos(position.add(phase % 2 == 0 ? 4 : 32, 0, 0));
                for (LivingEntity source : entities) CollisionContractParity.ordered(helper, source, "cell/section exit " + phase);
                changed.setPos(position);
                for (int repeat = 0; repeat < 3; repeat++) for (LivingEntity source : entities) {
                    CollisionContractParity.ordered(helper, source, "reentry/reuse " + phase + "/" + repeat);
                }
            }
            var added = (LivingEntity) scene.spawn(EntityTypes.ZOMBIE, new Vec3(4, 2, 4));
            added.setPos(entities.getFirst().position());
            for (LivingEntity source : entities) CollisionContractParity.ordered(helper, source, "same-frame insertion");
            added.discard();
            for (LivingEntity source : entities) CollisionContractParity.ordered(helper, source, "same-frame removal");
            CollisionFrame.begin(helper.getLevel());
            for (LivingEntity source : entities) CollisionContractParity.ordered(helper, source, "next-frame rebuilt order");
        }
    }
}

package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.PushBatch;
import net.minecraft.world.scores.Team;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;

final class PushBatchParity {
    static void verify(GameTestHelper helper) {
        var level = helper.getLevel();
        CollisionFrame.end(level);
        Zombie source = spawnZombie(helper, new Vec3(1.5, 1, 18.5));
        Zombie target = spawnZombie(helper, new Vec3(1.5, 1, 18.5));
        Zombie nestedSource = spawnZombie(helper, new Vec3(9.5, 1, 18.5));
        Zombie nestedTarget = spawnZombie(helper, new Vec3(9.7, 1, 18.5));
        try {
            CollisionFrame.begin(level);
            try (PushBatch outer = collect(source); PushBatch nested = collect(nestedSource)) {
                helper.assertValueEqual(outer.size(), 1, "outer batch candidates");
                helper.assertValueEqual(nested.size(), 1, "nested batch candidates");
                helper.assertTrue(outer.target(0) == target && outer.usesNativePush(0), "outer snapshot preserved");
                helper.assertTrue(nested.target(0) == nestedTarget, "nested snapshot isolated");
                // Emulate a preceding special push callback moving a later ordinary target.
                target.setPos(source.getX() + 0.2, source.getY(), source.getZ() + 0.1);
                source.setDeltaMovement(Vec3.ZERO);
                target.setDeltaMovement(Vec3.ZERO);
                target.push(source);
                Vec3 expectedSource = source.getDeltaMovement(), expectedTarget = target.getDeltaMovement();
                source.setDeltaMovement(Vec3.ZERO);
                target.setDeltaMovement(Vec3.ZERO);
                nested.applyNativeRun(nestedSource, 0, 1);
                outer.applyNativeRun(source, 0, 1);
                NativeImpulseParity.exact(helper, source.getDeltaMovement(), expectedSource, "late source geometry");
                NativeImpulseParity.exact(helper, target.getDeltaMovement(), expectedTarget, "late target geometry");
                // A completed run has already committed its velocity, even without a getter read.
                nestedTarget.needsSync = false;
                CollisionFrame.begin(level);
                helper.assertTrue(nestedTarget.getDeltaMovement().lengthSqr() > 0, "new frame preserves committed velocity");
                helper.assertTrue(!nestedTarget.needsSync, "new frame preserves reset sync");
            }
            try (PushBatch batch = collect(source)) {
                for (int phase = 0; phase < 3; phase++) {
                    if (phase == 1) target.setSleepingPos(target.blockPosition());
                    if (phase == 2) target.clearSleepingPos();
                    source.setDeltaMovement(Vec3.ZERO);
                    target.setDeltaMovement(Vec3.ZERO);
                    source.needsSync = target.needsSync = false;
                    target.push(source);
                    Vec3 expectedSource = source.getDeltaMovement(), expectedTarget = target.getDeltaMovement();
                    boolean sync = target.needsSync;
                    source.setDeltaMovement(Vec3.ZERO);
                    target.setDeltaMovement(Vec3.ZERO);
                    source.needsSync = target.needsSync = false;
                    batch.applyNativeRun(source, 0, 1);
                    NativeImpulseParity.exact(helper, source.getDeltaMovement(), expectedSource, "sleep source phase " + phase);
                    NativeImpulseParity.exact(helper, target.getDeltaMovement(), expectedTarget, "sleep target phase " + phase);
                    helper.assertValueEqual(target.needsSync, sync, "sleep sync phase " + phase);
                }
            }
        } finally {
            CollisionFrame.end(level);
            source.discard();
            target.discard();
            nestedSource.discard();
            nestedTarget.discard();
        }
    }

    private static PushBatch collect(Zombie source) {
        return CollisionFrame.collectPushable(source, null, Team.CollisionRule.ALWAYS, true);
    }
}

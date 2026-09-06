package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;

import static org.edtp.entitycollisionoptimizer.gametest.CollisionTestSupport.spawnZombie;

/** Independent oracle: execute Minecraft's Entity.push, not a second copy of its formula. */
final class NativeImpulseParity {
    static void verify(GameTestHelper helper) {
        Zombie source = spawnZombie(helper, new Vec3(4.5, 1, 18.5));
        Zombie target = spawnZombie(helper, new Vec3(4.7, 1, 18.6));
        try (var context = FFMBackend.createContext()) {
            // Keep epsilon-adjacent doubles representable; a distant GameTest origin would round them together.
            source.setPos(0, source.getY(), 0);
            double epsilon = 0.009999999776482582;
            double[] deltas = {0, Math.nextDown(epsilon), epsilon, Math.nextUp(epsilon),
                    -epsilon, 0.125, -0.25, 0.5, -0.75, Math.nextDown(1.0), 1.0,
                    Math.nextUp(1.0), -1.0, 1.5, -2.0};
            int count = deltas.length * deltas.length;
            double[] positions = new double[count * 2];
            double[] impulses = new double[count * 2];
            int index = 0;
            for (double x : deltas) for (double z : deltas) {
                positions[index++] = source.getX() + x;
                positions[index++] = source.getZ() + z;
            }
            FFMBackend.calculatePushImpulses(context, source.getX(), source.getZ(), positions, count, impulses);
            for (int i = 0; i < count; i++) {
                target.setPos(positions[2 * i], source.getY(), positions[2 * i + 1]);
                Vec3 initial = new Vec3(0.125, -0.0, -0.375);
                source.setDeltaMovement(initial);
                target.setDeltaMovement(initial);
                source.needsSync = target.needsSync = false;
                target.push(source);
                Vec3 expectedSource = source.getDeltaMovement();
                Vec3 expectedTarget = target.getDeltaMovement();
                boolean expectedSourceSync = source.needsSync;
                boolean expectedTargetSync = target.needsSync;
                source.setDeltaMovement(initial);
                target.setDeltaMovement(initial);
                source.needsSync = target.needsSync = false;
                if (!Double.isNaN(impulses[2 * i])) {
                    target.push(-impulses[2 * i], 0, -impulses[2 * i + 1]);
                    source.push(impulses[2 * i], 0, impulses[2 * i + 1]);
                }
                exact(helper, source.getDeltaMovement(), expectedSource, "kernel source " + i);
                exact(helper, target.getDeltaMovement(), expectedTarget, "kernel target " + i);
                helper.assertValueEqual(source.needsSync, expectedSourceSync, "kernel source sync " + i);
                helper.assertValueEqual(target.needsSync, expectedTargetSync, "kernel target sync " + i);
            }
            verifyArithmetic(helper, context, source, target);
        } finally {
            source.discard();
            target.discard();
        }
    }

    private static void verifyArithmetic(GameTestHelper helper, FFMBackend.Context context, Zombie source, Zombie target) {
        Vec3[] initial = {Vec3.ZERO, new Vec3(0x1.0p53, -0.0, 0x1.0p53),
                new Vec3(Double.MAX_VALUE, 0, Double.MAX_VALUE), new Vec3(1, 2, 3)};
        double[] positions = {0.125, -0.25, -0.125, 0.25, 1.5, -2, -1.5, 2, 0, 0};
        int count = positions.length / 2;
        double[] impulses = new double[positions.length];
        FFMBackend.calculatePushImpulses(context, source.getX(), source.getZ(), positions, count, impulses);
        for (int scenario = 0; scenario < initial.length; scenario++) {
            source.setDeltaMovement(initial[scenario]);
            target.setDeltaMovement(initial[scenario]);
            source.needsSync = target.needsSync = false;
            Vec3[] expectedSource = new Vec3[count], expectedTarget = new Vec3[count];
            boolean[] expectedSourceSync = new boolean[count], expectedTargetSync = new boolean[count];
            for (int i = 0; i < count; i++) {
                target.setPos(positions[2 * i], source.getY(), positions[2 * i + 1]);
                target.push(source);
                expectedSource[i] = source.getDeltaMovement();
                expectedTarget[i] = target.getDeltaMovement();
                expectedSourceSync[i] = source.needsSync;
                expectedTargetSync[i] = target.needsSync;
            }
            source.setDeltaMovement(initial[scenario]);
            target.setDeltaMovement(initial[scenario]);
            source.needsSync = target.needsSync = false;
            for (int i = 0; i < count; i++) {
                if (!Double.isNaN(impulses[2 * i])) {
                    target.push(-impulses[2 * i], 0, -impulses[2 * i + 1]);
                    source.push(impulses[2 * i], 0, impulses[2 * i + 1]);
                }
                exact(helper, source.getDeltaMovement(), expectedSource[i], "native accumulation source " + scenario + "/" + i);
                exact(helper, target.getDeltaMovement(), expectedTarget[i], "native accumulation target " + scenario + "/" + i);
                helper.assertValueEqual(source.needsSync, expectedSourceSync[i], "accumulation source sync");
                helper.assertValueEqual(target.needsSync, expectedTargetSync[i], "accumulation target sync");
            }
        }
    }

    static void exact(GameTestHelper helper, Vec3 actual, Vec3 expected, String label) {
        helper.assertTrue(Double.doubleToRawLongBits(actual.x) == Double.doubleToRawLongBits(expected.x)
                        && Double.doubleToRawLongBits(actual.y) == Double.doubleToRawLongBits(expected.y)
                        && Double.doubleToRawLongBits(actual.z) == Double.doubleToRawLongBits(expected.z),
                label + ": expected " + expected + ", got " + actual);
    }
}

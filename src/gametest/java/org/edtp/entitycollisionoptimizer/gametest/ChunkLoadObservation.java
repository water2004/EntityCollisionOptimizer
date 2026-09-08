package org.edtp.entitycollisionoptimizer.gametest;

import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

record ChunkLoadObservation(ChunkEntityTrace trace, ChunkEntityTrace portalTrace,
                            List<DimensionMomentumParity.Observation> transfers,
                            boolean weakLamp, boolean idleLamp, Vec3 portalVelocity,
                            boolean portalKeptEntity) {
    void compare(GameTestHelper helper, ChunkLoadObservation expected, int ticks, Vec3 inputVelocity) {
        DimensionMomentumParity.compare(helper, transfers, expected.transfers);
        trace.compare(helper, expected.trace);
        portalTrace.compare(helper, expected.portalTrace);
        helper.assertValueEqual(weakLamp, expected.weakLamp, "weak redstone lamp");
        helper.assertValueEqual(idleLamp, expected.idleLamp, "loaded-ring redstone lamp");
        helper.assertTrue(expected.weakLamp, "weak-load redstone must run");
        helper.assertTrue(!expected.idleLamp, "loaded-without-tick redstone must not run");
        helper.assertTrue(expected.trace.last(1).position().y > expected.trace.last(0).position().y + 1.0,
                "weak-load item must stay frozen while strong-load item falls");
        helper.assertTrue(expected.trace.last(2).position().x >= 16.0,
                "boundary item must leave the strong chunk");
        helper.assertTrue(expected.trace.last(2).ticks() > 0 && expected.trace.last(2).ticks() < ticks,
                "entity must stop ticking after entering the weak chunk");
        CollisionTestSupport.assertVectorEqual(helper, portalVelocity, expected.portalVelocity, "portal momentum");
        CollisionTestSupport.assertVectorEqual(helper, expected.portalVelocity, inputVelocity, "vanilla portal momentum");
        helper.assertValueEqual(portalKeptEntity, expected.portalKeptEntity, "portal entity identity");
        helper.assertTrue(trace.last(4).position().x < 16 && trace.last(4).ticks() > 0,
                "weak-to-strong piston displacement must reactivate entity ticking");
        helper.assertTrue(trace.last(3).position().x >= 32 && trace.last(3).ticks() == 0,
                "weak-to-loaded displacement must cross boundary without autonomous ticking");
        helper.assertValueEqual(trace.last(5).ticks(), 0, "loaded-only entity remains frozen");
        helper.assertTrue(trace.last(6).position().x > 15.4 && trace.last(6).position().x < 16,
                "strong entity must collide with weak chunk block");
        helper.assertTrue(trace.last(7).position().x > 15.2 && trace.last(7).position().x < 16,
                "strong entity must collide with non-ticking boat");
        helper.assertValueEqual(trace.last(8).ticks(), 0, "weak collision target must not tick");
    }
}

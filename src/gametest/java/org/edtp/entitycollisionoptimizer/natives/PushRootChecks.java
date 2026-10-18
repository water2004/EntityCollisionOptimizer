package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.edtp.entitycollisionoptimizer.natives.CollisionStateTable.*;

/** Root callback and body-slot lifetime checks, independent of query metadata. */
public final class PushRootChecks {
    private PushRootChecks() {}

    public static void verify(GameTestHelper helper) {
        RootProbe source = new RootProbe(helper.getLevel());
        RootProbe vehicle = new RootProbe(helper.getLevel());
        RootProbe replacement = new RootProbe(helper.getLevel());
        try (var bodies = new CollisionStateTable()) {
            int sourceSlot = bodies.bindBody(source);
            bodies.refreshPushStates();
            helper.assertTrue(rootSlot(bodies, sourceSlot) == sourceSlot, "self root uses the current slot");
            helper.assertValueEqual(source.rootReads, 1, "self root callback is preserved");

            source.root = vehicle;
            bodies.invalidatePushState(sourceSlot);
            bodies.refreshPushStates();
            int vehicleSlot = rootSlot(bodies, sourceSlot);
            helper.assertTrue(bodies.entity(vehicleSlot) == vehicle, "custom root is resolved even without a passenger flag");
            helper.assertValueEqual(source.rootReads, 2, "custom root callback is preserved");

            bodies.retire(vehicle);
            helper.assertValueEqual(bodies.bindBody(replacement), vehicleSlot, "retired root slot is reused");
            bodies.invalidatePushState(sourceSlot);
            bodies.refreshPushStates();
            helper.assertTrue(bodies.entity(rootSlot(bodies, sourceSlot)) == vehicle,
                    "root resolution never reuses a cached slot belonging to a replacement");

            source.root = source;
            bodies.invalidatePushState(sourceSlot);
            bodies.refreshPushStates();
            helper.assertTrue(rootSlot(bodies, sourceSlot) == sourceSlot, "return to self clears the previous root");
        }
    }

    private static int rootSlot(CollisionStateTable bodies, int slot) {
        return bodies.memory().get(JAVA_INT, (long) slot * STRIDE_BYTES + ROOT_OFFSET);
    }

    private static final class RootProbe extends Zombie {
        Entity root;
        int rootReads;
        RootProbe(ServerLevel level) { super(EntityTypes.ZOMBIE, level); }
        @Override public Entity getRootVehicle() { rootReads++; return root == null ? this : root; }
    }
}

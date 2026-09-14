package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Nested movement must not reuse its parent's packet; every exceptional exit releases its lease. */
public final class MovementLeaseChecks {
    public static void verify(GameTestHelper helper) {
        List<Vec3> vanilla = run(helper, false);
        List<Vec3> nativeResult = run(helper, true);
        helper.assertTrue(nativeResult.equals(vanilla), "nested movement and exception publication match vanilla");
        EntityCollisionOptimizer.LOGGER.info("ECO_MOVEMENT_LEASE nested_callback=true exception_release=true result=passed");
    }

    private static List<Vec3> run(GameTestHelper helper, boolean enabled) {
        Entity entity = new Zombie(helper.getLevel());
        entity.setPos(helper.absoluteVec(new Vec3(2, 6, 2)));
        List<Vec3> observed = new ArrayList<>();
        var marker = new IllegalStateException("movement lease fixture");
        boolean[] nested = {false}, fail = {false};
        entity.setLevelCallback(new EntityInLevelCallback() {
            public void onRemove(Entity.RemovalReason reason) {}
            public void onMove() {
                observed.add(entity.position());
                if (!nested[0]) {
                    nested[0] = true;
                    try { entity.move(MoverType.SELF, new Vec3(-.01, .02, .03)); }
                    finally { nested[0] = false; }
                }
                if (fail[0]) throw marker;
            }
        });
        try (var table = new CollisionStateTable()) {
            if (enabled) table.slot(entity);
            entity.move(MoverType.SELF, new Vec3(.03, .01, .02));
            int available = enabled ? availablePackets() : 0;
            for (int i = 0; i < 8; i++) {
                fail[0] = true;
                try {
                    entity.move(MoverType.SELF, new Vec3(.03, .01, .02));
                    helper.fail("Expected movement callback exception");
                } catch (IllegalStateException failure) {
                    if (failure != marker) throw failure;
                }
                if (enabled) helper.assertValueEqual(availablePackets(), available, "exception returns all movement packets");
                fail[0] = false;
                entity.move(MoverType.SELF, new Vec3(.03, .01, .02));
                observed.add(entity.position());
            }
        }
        return observed;
    }

    private static int availablePackets() {
        try {
            var field = NativeMovement.class.getDeclaredField("POOL");
            field.setAccessible(true);
            return ((ArrayDeque<?>) ((ThreadLocal<?>) field.get(null)).get()).size();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}

package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import java.util.ArrayList;
import java.util.List;

/** Nested and throwing world callbacks observe the same movement with and without a bound push body. */
public final class MovementReentryChecks {
    public static void verify(GameTestHelper helper) {
        List<Vec3> unbound = run(helper, false);
        List<Vec3> bound = run(helper, true);
        helper.assertTrue(bound.equals(unbound), "nested movement and exception publication are independent of push-body ownership");
        EntityCollisionOptimizer.LOGGER.info("ECO_MOVEMENT_REENTRY nested_callback=true exception_publication=true result=passed");
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
            if (enabled) table.bindBody(entity);
            entity.move(MoverType.SELF, new Vec3(.03, .01, .02));
            for (int i = 0; i < 8; i++) {
                fail[0] = true;
                try {
                    entity.move(MoverType.SELF, new Vec3(.03, .01, .02));
                    helper.fail("Expected movement callback exception");
                } catch (IllegalStateException failure) {
                    if (failure != marker) throw failure;
                }
                fail[0] = false;
                entity.move(MoverType.SELF, new Vec3(.03, .01, .02));
                observed.add(entity.position());
            }
        }
        return observed;
    }

}

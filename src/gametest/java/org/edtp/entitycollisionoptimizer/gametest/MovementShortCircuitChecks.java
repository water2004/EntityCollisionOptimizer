package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.edtp.entitycollisionoptimizer.collision.blocks.EntityMovementCollision;
import org.edtp.entitycollisionoptimizer.gametest.mixin.EntityCollisionInvoker;

import java.util.ArrayList;
import java.util.Optional;

/** Independent vanilla geometry plus test-only observation of avoided entity queries. */
public final class MovementShortCircuitChecks {
    private static Entity observed;
    private static int queries;

    private MovementShortCircuitChecks() {}

    public static void queried(Entity entity) { if (entity == observed) queries++; }

    static void verify(GameTestHelper helper) {
        BlockPos support = new BlockPos(1, 0, 1);
        var entity = CollisionTestSupport.spawnZombie(helper, new Vec3(1.5, 1, 1.5));
        var boat = helper.spawn(EntityType.OAK_BOAT, new Vec3(1.5, 1, 1.5));
        try {
            helper.setBlock(support, Blocks.STONE);
            entity.mainSupportingBlockPos = Optional.of(helper.absolutePos(support));
            for (double y : new double[]{-.08, -1e-7, -Math.nextUp(1e-7), -Math.nextDown(1e-7), -1e-8}) {
                check(helper, entity, new Vec3(-0.0, y, -0.0), y <= -1e-7 ? 0 : 1, "grounded " + y);
            }
            check(helper, entity, new Vec3(0, -1e-200, 0), 1, "squared-length underflow");
            check(helper, entity, new Vec3(.2, -.08, 0), 1, "horizontal movement");
            check(helper, entity, new Vec3(0, .2, 0), 1, "upward movement");
            entity.setPos(helper.absoluteVec(new Vec3(1.5, 3, 1.5)));
            check(helper, entity, new Vec3(0, -.08, 0), 1, "stale support while falling");
            entity.setPos(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
            helper.setBlock(support, Blocks.AIR);
            check(helper, entity, new Vec3(0, -.08, 0), 1, "removed support");
            helper.setBlock(support, Blocks.SCAFFOLDING);
            check(helper, entity, new Vec3(0, -.08, 0), 0, "context-dependent support");
            entity.setShiftKeyDown(true);
            check(helper, entity, new Vec3(0, -.08, 0), 1, "descending through scaffolding");
        } finally {
            observed = null;
            boat.discard();
            entity.discard();
            helper.setBlock(support, Blocks.AIR);
        }
    }

    private static void check(GameTestHelper helper, Entity entity, Vec3 requested, int expectedQueries, String label) {
        var bounds = entity.getBoundingBox();
        var scan = bounds.expandTowards(requested);
        var shapes = new ArrayList<VoxelShape>();
        for (Entity target : VanillaEntityQueries.call(() -> entity.level().getEntities(entity, scan.inflate(1e-7)))) {
            if (!target.isSpectator() && entity.canCollideWith(target)) shapes.add(Shapes.create(target.getBoundingBox()));
        }
        entity.level().getBlockCollisions(entity, scan).forEach(shapes::add);
        Vec3 expected = requested.lengthSqr() == 0 ? requested
                : EntityCollisionInvoker.eco$collideWithShapes(requested, bounds, shapes);
        observed = entity;
        queries = 0;
        Vec3 actual;
        try { actual = EntityMovementCollision.collide(entity, requested); }
        finally { observed = null; }
        NativeImpulseParity.exact(helper, actual, expected, "support short-circuit " + label);
        helper.assertValueEqual(queries, expectedQueries, "hard query count " + label);
    }
}

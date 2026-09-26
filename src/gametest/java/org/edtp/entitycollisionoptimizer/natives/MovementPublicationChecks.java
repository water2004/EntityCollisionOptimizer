package org.edtp.entitycollisionoptimizer.natives;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.List;

/** Observe the position inside vanilla's world notification, not merely at the end of a movement. */
public final class MovementPublicationChecks {
    public static void verify(GameTestHelper helper) {
        for (int x = 1; x < 7; x++) for (int z = 1; z < 7; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.STONE_SLAB);

        List<State> expected = run(helper, false);
        List<State> actual = run(helper, true);
        helper.assertValueEqual(actual.size(), expected.size(), "movement world observation count");
        for (int i = 0; i < expected.size(); i++) {
            helper.assertTrue(actual.get(i).equals(expected.get(i)),
                    "movement publication " + i + " expected=" + expected.get(i) + " actual=" + actual.get(i));
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_MOVEMENT_PUBLICATION observations={} raw_position=true callbacks=true lifecycle=true result=passed", actual.size());
    }

private static List<State> run(GameTestHelper helper, boolean enabled) {
    Entity entity = new Zombie(helper.getLevel());
    Vec3 start = helper.absoluteVec(new Vec3(3.5, 1, 3.5));
    entity.setPos(start);
    entity.setOnGround(true);
    List<State> result = new ArrayList<>();
    try (CollisionStateTable table = new CollisionStateTable()) {
            if (enabled) table.bindBody(entity);
            entity.setLevelCallback(new EntityInLevelCallback() {
                @Override public void onMove() { result.add(state(entity, true)); }
                @Override public void onRemove(Entity.RemovalReason reason) {}
            });
            for (int tick = 0; tick < 36; tick++) {
                entity.noPhysics = tick == 18;
                Vec3 request = new Vec3(tick < 18 ? .08 : -.08, tick == 18 ? .3 : -.12, (tick & 1) == 0 ? .02 : -.02);
                entity.setDeltaMovement(request);
                entity.move(tick >= 30 ? MoverType.PISTON : MoverType.SELF, request);
                result.add(state(entity, false));
            }
            // Ownership release and rebinding must preserve the position used by the next move.
            table.clear();
            result.add(state(entity, false));
            if (enabled) table.bindBody(entity);
            entity.move(MoverType.SELF, new Vec3(-.2, -.1, .05));
            result.add(state(entity, false));
        }
        return result;
    }

    private static State state(Entity entity, boolean callback) {
        int flags = (entity.onGround() ? 1 : 0) | (entity.horizontalCollision ? 2 : 0)
                | (entity.verticalCollision ? 4 : 0) | (entity.verticalCollisionBelow ? 8 : 0)
                | (entity.minorHorizontalCollision ? 16 : 0);
        return new State(entity.position(), entity.getBoundingBox(), entity.blockPosition(), entity.getDeltaMovement(),
                flags, entity.fallDistance, callback);
    }
    private record State(Vec3 position, AABB box, BlockPos block, Vec3 velocity, int flags, double fall, boolean callback) {}
}

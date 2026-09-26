package org.edtp.entitycollisionoptimizer.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

/** Real dimension transfers; the disabled-backend run supplies all trajectory oracles. */
final class DimensionMomentumParity {
    private static final Vec3 VELOCITY = new Vec3(0.35, 0.15, -0.22);

    record Observation(ChunkEntityTrace.State state, boolean sameEntity, boolean oldRemoved,
                       boolean uuidKept, float yaw, float pitch) {
        void compare(GameTestHelper helper, Observation expected, String label) {
            state.compare(helper, expected.state, label);
            helper.assertValueEqual(sameEntity, expected.sameEntity, label + " identity");
            helper.assertValueEqual(oldRemoved, expected.oldRemoved, label + " source retirement");
            helper.assertValueEqual(uuidKept, expected.uuidKept, label + " UUID");
            helper.assertValueEqual(yaw, expected.yaw, label + " yaw");
            helper.assertValueEqual(pitch, expected.pitch, label + " pitch");
        }
    }

    static List<Observation> capture(GameTestHelper helper, ServerLevel overworld, ServerLevel nether,
                                     Vec3 sourceOrigin, Vec3 destinationOrigin) {
        List<Observation> observations = new ArrayList<>();
        for (EntityType<?> type : new EntityType<?>[]{EntityTypes.ITEM, EntityTypes.TNT,
                EntityTypes.ENDER_PEARL, EntityTypes.OAK_BOAT}) {
            for (int mode = 0; mode < 3; mode++) {
                List<Entity> owned = new ArrayList<>();
                try {
                    Entity entity = type.create(overworld, EntitySpawnReason.COMMAND);
                    helper.assertTrue(entity != null, "create teleport fixture " + type);
                    owned.add(entity);
                    if (entity instanceof ItemEntity item) item.setItem(new ItemStack(Items.STONE));
                    entity.setPos(sourceOrigin.add(0, 4, 0));
                    entity.setDeltaMovement(VELOCITY);
                    entity.setYRot(20);
                    entity.setXRot(15);
                    helper.assertTrue(overworld.addFreshEntity(entity), "add teleport fixture");
                    Set<Relative> relatives = switch (mode) {
                        case 0 -> Set.of();
                        case 1 -> Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);
                        default -> Relative.DELTA; // Includes ROTATE_DELTA.
                    };
                    for (int leg = 0; leg < 2; leg++) {
                        ServerLevel destination = leg == 0 ? nether : overworld;
                        Vec3 origin = leg == 0 ? destinationOrigin : sourceOrigin;
                        Entity before = entity;
                        Vec3 beforeVelocity = before.getDeltaMovement();
                        entity = before.teleport(new TeleportTransition(destination, origin.add(0, 4, 0),
                                mode == 0 ? VELOCITY : Vec3.ZERO, leg == 0 ? 90 : -45,
                                leg == 0 ? 0 : 30, relatives, TeleportTransition.DO_NOTHING));
                        helper.assertTrue(entity != null && entity.level() == destination, "dimension transfer completed");
                        if (entity != before) owned.add(entity);
                        if (mode < 2) CollisionTestSupport.assertVectorEqual(helper, entity.getDeltaMovement(),
                                mode == 0 ? VELOCITY : beforeVelocity,
                                "absolute/preserved transfer momentum type=" + type + " mode=" + mode + " leg=" + leg);
                        else if (leg == 0) helper.assertTrue(entity.getDeltaMovement().distanceToSqr(beforeVelocity) > 1e-6,
                                "rotation case must actually rotate momentum type=" + type + " leg=" + leg
                                        + " before=" + beforeVelocity + " after=" + entity.getDeltaMovement());
                        for (int step = 0; step < 4; step++) {
                            // Includes a downward block collision after the transfer;
                            // no entity.tick() is manually invoked in a loaded world.
                            if (step != 0) entity.move(MoverType.SELF,
                                    step == 3 ? new Vec3(0, -5, 0) : entity.getDeltaMovement());
                            if (step == 3) helper.assertTrue(entity.verticalCollision,
                                    "post-transfer movement must collide with destination floor");
                            observations.add(new Observation(ChunkEntityTrace.State.capture(entity, origin),
                                    entity == before, before.isRemoved(), entity.getUUID().equals(before.getUUID()),
                                    entity.getYRot(), entity.getXRot()));
                        }
                    }
                } finally {
                    owned.forEach(Entity::discard);
                    CollisionFrame.end(overworld);
                    CollisionFrame.end(nether);
                }
            }
        }
        return List.copyOf(observations);
    }

    static void compare(GameTestHelper helper, List<Observation> actual, List<Observation> expected) {
        helper.assertValueEqual(actual.size(), expected.size(), "dimension observation count");
        for (int i = 0; i < actual.size(); i++) actual.get(i).compare(helper, expected.get(i), "dimension observation " + i);
    }
}

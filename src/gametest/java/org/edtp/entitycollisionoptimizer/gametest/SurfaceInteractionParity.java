package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** Exercise movement AND surface callbacks/friction, not just collision-shape equality. */
final class SurfaceInteractionParity {
    static void verify(GameTestHelper helper) {
        int cases = 0;
        for (var type : List.of(EntityType.ZOMBIE, EntityType.ITEM, EntityType.PLAYER,
                EntityType.OAK_BOAT, EntityType.TNT)) {
            for (Block floor : List.of(Blocks.STONE, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
                    Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.SOUL_SAND, Blocks.MUD)) {
                for (boolean falling : new boolean[]{false, true}) {
                    var expected = run(helper, false, type, floor, falling);
                    var actual = run(helper, true, type, floor, falling);
                    String label = type + " on " + floor + " falling=" + falling;
                    for (int i = 0; i < expected.size(); i++) actual.get(i).compare(helper, expected.get(i), label + " step=" + i);
                    helper.assertTrue(expected.stream().anyMatch(s -> s.velocity().lengthSqr() > 0), label + " must exercise motion");
                    if (falling && floor == Blocks.SLIME_BLOCK && (type == EntityType.ZOMBIE || type == EntityType.ITEM || type == EntityType.PLAYER)) {
                        helper.assertTrue(expected.stream().anyMatch(s -> s.velocity().y > 0.1), label + " must really bounce upward");
                    }
                    cases++;
                }
            }
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_SURFACE_INTERACTIONS cases={} steps_per_case=16 result=passed", cases);
    }

    private static List<InteractionScene.State> run(GameTestHelper helper, boolean enabled,
                                                     EntityType<?> type, Block floor, boolean falling) {
        try (var scene = new InteractionScene(helper)) {
            scene.floor(floor);
            for (int y = 1; y <= 4; y++) for (int z = 1; z <= 8; z++) scene.block(10, y, z, Blocks.STONE);
            Entity entity = scene.spawn(type, new Vec3(4.5, falling ? 3.2 : 1.0, 4.5));
            entity.setOnGround(!falling);
            entity.setDeltaMovement(new Vec3(0.24, falling ? -0.45 : 0, 0.06));
            List<InteractionScene.State> states = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                CollisionFrame.begin(helper.getLevel());
                InteractionScene.prepareTick(entity);
                Vec3 before = entity.position();
                if (entity instanceof LivingEntity living) {
                    living.baseTick();
                    living.travel(Vec3.ZERO);
                    living.applyEffectsFromBlocks(before, living.position());
                } else {
                    entity.tick();
                }
                states.add(InteractionScene.State.of(entity));
                CollisionFrame.end(helper.getLevel());
            }
            return states;
        }
    }
}

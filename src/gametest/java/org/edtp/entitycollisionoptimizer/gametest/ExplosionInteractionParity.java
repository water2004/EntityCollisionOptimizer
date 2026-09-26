package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.gametest.mixin.WindChargeTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** Actual TNT detonation / wind-charge impact, followed by ordered collision and movement traces. */
final class ExplosionInteractionParity {
    static void verify(GameTestHelper helper) {
        int cases = 0;
        for (int kind = 0; kind < 3; kind++) for (boolean shielded : new boolean[]{false, true}) {
            for (int count : new int[]{5, 20}) {
                var expected = run(helper, false, kind, shielded, count);
                var actual = run(helper, true, kind, shielded, count);
                for (int i = 0; i < expected.size(); i++) actual.get(i).compare(helper, expected.get(i),
                        "explosion kind=" + kind + " shielded=" + shielded + " count=" + count + " observation=" + i);
                cases++;
            }
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_EXPLOSION_INTERACTIONS cases={} entity_counts=5,20 observations_per_entity=9 result=passed", cases);
    }

    private static List<InteractionScene.State> run(GameTestHelper helper, boolean enabled,
                                                     int kind, boolean shielded, int count) {
        try (var scene = new InteractionScene(helper)) {
            scene.floor(Blocks.OBSIDIAN);
            for (int y = 1; y <= 4; y++) for (int z = 1; z <= 8; z++) {
                scene.block(11, y, z, Blocks.OBSIDIAN);
                if (shielded) scene.block(8, y, z, Blocks.OBSIDIAN);
            }
            List<Entity> targets = new ArrayList<>();
            var types = List.of(EntityType.ZOMBIE, EntityType.PLAYER, EntityType.ITEM,
                    EntityType.TNT);
            for (int i = 0; i < count; i++) {
                var entity = scene.spawn(types.get(i % types.size()), new Vec3(9.1 + (i % 4) * 0.12, 1.05, 4.1 + (i / 4) * 0.12));
                if (entity instanceof LivingEntity living) {
                    living.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
                    living.setHealth(200);
                }
                entity.setDeltaMovement(Vec3.ZERO);
                entity.setOnGround(true);
                targets.add(entity);
            }
            var actor = scene.spawn(kind == 0 ? EntityType.TNT : kind == 1 ? EntityType.WIND_CHARGE : EntityType.BREEZE_WIND_CHARGE,
                    new Vec3(7.5, 1.1, 4.5));
            actor.setNoGravity(true);
            actor.setDeltaMovement(Vec3.ZERO);
            CollisionFrame.begin(helper.getLevel());
            if (actor instanceof PrimedTnt tnt) {
                tnt.setFuse(1);
                tnt.tick();
            } else {
                if (kind == 1) ((Projectile) actor).setOwner(targets.get(1));
                Vec3 impact = helper.absoluteVec(new Vec3(7.5, 1.0, 4.5));
                ((WindChargeTestInvoker) actor).eco$hit(new BlockHitResult(impact, Direction.UP,
                        helper.absolutePos(new BlockPos(7, 0, 4)), false));
            }
            List<InteractionScene.State> states = new ArrayList<>();
            targets.forEach(entity -> states.add(InteractionScene.State.of(entity)));
            if (!shielded) helper.assertTrue(states.stream().anyMatch(s -> s.velocity().lengthSqr() > 0),
                    "unshielded explosion must produce a nonempty knockback comparison: kind=" + kind + " enabled=" + enabled
                            + " actor=" + actor.position() + " targets=" + states);
            for (int step = 0; step < 8; step++) {
                for (Entity entity : targets) {
                    if (!entity.isRemoved()) {
                        if (entity instanceof LivingEntity living) ((LivingEntityTestInvoker) living).entityCollisionOptimizer$invokePushEntities();
                        Vec3 before = entity.position();
                        entity.move(MoverType.SELF, entity.getDeltaMovement());
                        entity.applyEffectsFromBlocks(before, entity.position());
                    }
                    states.add(InteractionScene.State.of(entity));
                }
            }
            return states;
        }
    }
}

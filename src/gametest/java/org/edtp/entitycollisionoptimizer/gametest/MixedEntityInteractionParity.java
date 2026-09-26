package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

final class MixedEntityInteractionParity {
    static void verify(GameTestHelper helper) {
        int pairs = 0;
        for (var source : List.of(EntityType.ZOMBIE, EntityType.IRON_GOLEM, EntityType.SLIME,
                EntityType.MAGMA_CUBE, EntityType.WARDEN, EntityType.PLAYER)) {
            for (var target : List.of(EntityType.ZOMBIE, EntityType.ITEM, EntityType.EXPERIENCE_ORB,
                    EntityType.PLAYER, EntityType.BOAT, EntityType.MINECART, EntityType.TNT,
                    EntityType.WOLF, EntityType.SHULKER, EntityType.ARMOR_STAND, EntityType.SLIME, EntityType.MAGMA_CUBE)) {
                compare(helper, List.of(source, target), "pair " + source + " -> " + target);
                pairs++;
            }
        }
        List<EntityType<?>> mixed = new ArrayList<>();
        for (int i = 0; i < 20; i++) mixed.add(List.of(EntityType.ZOMBIE, EntityType.ITEM, EntityType.TNT,
                EntityType.MAGMA_CUBE, EntityType.PLAYER).get(i % 5));
        compare(helper, mixed, "medium mixed 20-entity group");
        EntityCollisionOptimizer.LOGGER.info("ECO_MIXED_ENTITY_INTERACTIONS pairs={} medium_entities=20 phases=4 result=passed", pairs);
    }

    private static void compare(GameTestHelper helper, List<? extends EntityType<?>> types, String label) {
        var expected = run(helper, false, types);
        var actual = run(helper, true, types);
        for (int i = 0; i < expected.size(); i++) actual.get(i).compare(helper, expected.get(i), label + " observation=" + i);
    }

    private static List<InteractionScene.State> run(GameTestHelper helper, boolean enabled, List<? extends EntityType<?>> types) {
        try (var scene = new InteractionScene(helper)) {
            scene.floor(Blocks.STONE);
            List<Entity> entities = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                var entity = scene.spawn(types.get(i), new Vec3(4.5 + (i % 5) * 0.13, 1, 4.5 + (i / 5) * 0.13));
                if (entity instanceof LivingEntity living) {
                    living.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
                    living.setHealth(200);
                }

                entity.setDeltaMovement(Vec3.ZERO);
                entity.hasImpulse = false;
                entities.add(entity);
            }
            for (Entity entity : entities) {
                entity.setDeltaMovement(Vec3.ZERO);
                entity.hasImpulse = false;
                entity.getRandom().setSeed(0xEC0262);
            }
            CollisionFrame.begin(helper.getLevel());
            List<InteractionScene.State> states = new ArrayList<>();
            entities.forEach(entity -> states.add(InteractionScene.State.of(entity)));
            for (int phase = 0; phase < 4; phase++) {
                // Include within-frame geometry changes; every phase preserves vanilla call order.
                if (phase == 2) entities.getLast().setPos(entities.getLast().position().add(0.08, 0, -0.04));
                for (Entity entity : entities) {
                    if (entity instanceof LivingEntity living) ((LivingEntityTestInvoker) living).entityCollisionOptimizer$invokePushEntities();
                }
                entities.forEach(entity -> states.add(InteractionScene.State.of(entity)));
            }
            return states;
        }
    }
}

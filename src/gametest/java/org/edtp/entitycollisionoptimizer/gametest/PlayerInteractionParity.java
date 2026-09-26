package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.gametest.mixin.AttackStrengthAccessor;
import org.edtp.entitycollisionoptimizer.gametest.mixin.LivingEntityTestInvoker;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** Player attacks, sprint knockback, contact pushing and item collection with identical initial inputs. */
final class PlayerInteractionParity {
    static void verify(GameTestHelper helper) {
        int cases = 0;
        for (int kind = 0; kind < 3; kind++) for (boolean sprinting : new boolean[]{false, true}) {
            var expected = attack(helper, false, kind, sprinting);
            var actual = attack(helper, true, kind, sprinting);
            for (int i = 0; i < expected.size(); i++) actual.get(i).compare(helper, expected.get(i),
                    "player KB-II target=" + kind + " sprinting=" + sprinting + " observation=" + i);
            cases++;
        }
        var expected = pickup(helper, false);
        var actual = pickup(helper, true);
        helper.assertValueEqual(actual, expected, "player item pickup, inventory and remaining entity");
        EntityCollisionOptimizer.LOGGER.info("ECO_PLAYER_INTERACTIONS attack_cases={} pickup_cases=1 result=passed", cases);
    }

    private static List<InteractionScene.State> attack(GameTestHelper helper, boolean enabled, int kind, boolean sprinting) {
        try (var scene = new InteractionScene(helper)) {
            scene.floor(Blocks.BLUE_ICE);
            for (int y = 1; y <= 4; y++) for (int z = 2; z <= 6; z++) scene.block(7, y, z, Blocks.STONE);
            var player = (ServerPlayer) scene.spawn(EntityType.PLAYER, new Vec3(4.5, 1, 4.5));
            player.setGameMode(GameType.SURVIVAL);
            var type = List.of(EntityType.ZOMBIE, EntityType.SLIME, EntityType.PLAYER).get(kind);
            var target = (LivingEntity) scene.spawn(type, new Vec3(5.1, 1, 4.5));
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
            target.setHealth(200);
            player.setYRot(-90);
            player.setSprinting(sprinting);
            player.setOnGround(true);
            player.setDeltaMovement(Vec3.ZERO);
            target.setDeltaMovement(Vec3.ZERO);
            var sword = Items.DIAMOND_SWORD.getDefaultInstance();
            sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK), 2);
            player.setItemInHand(InteractionHand.MAIN_HAND, sword);
            ((AttackStrengthAccessor) player).eco$attackStrength(100);
            CollisionFrame.begin(helper.getLevel());
            player.attack(target);
            helper.assertTrue(target.getHealth() < 200, "attack fixture must enter damage path: kind=" + kind + " sprinting=" + sprinting);
            List<InteractionScene.State> states = new ArrayList<>();
            states.add(InteractionScene.State.of(player)); states.add(InteractionScene.State.of(target));
            for (int step = 0; step < 12; step++) {
                for (var entity : List.of(player, target)) {
                    ((LivingEntityTestInvoker) entity).entityCollisionOptimizer$invokePushEntities();
                    Vec3 before = entity.position();
                    entity.move(entity == player ? MoverType.PLAYER : MoverType.SELF, entity.getDeltaMovement());
                    entity.applyEffectsFromBlocks(before, entity.position());
                    states.add(InteractionScene.State.of(entity));
                }
            }
            return states;
        }
    }

    private static List<Integer> pickup(GameTestHelper helper, boolean enabled) {
        try (var scene = new InteractionScene(helper)) {
            scene.floor(Blocks.STONE);
            var player = (ServerPlayer) scene.spawn(EntityType.PLAYER, new Vec3(4.5, 1, 4.5));
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            var item = (ItemEntity) scene.spawn(EntityType.ITEM, new Vec3(4.6, 1, 4.5));
            item.setNoPickUpDelay();
            CollisionFrame.begin(helper.getLevel());
            ((LivingEntityTestInvoker) player).entityCollisionOptimizer$invokePushEntities();
            item.playerTouch(player);
            int diamonds = player.getInventory().countItem(Items.DIAMOND);
            helper.assertValueEqual(diamonds, 7, "all dropped diamonds must be collected");
            return List.of(diamonds, item.getItem().getCount(), item.isRemoved() ? 1 : 0);
        }
    }
}

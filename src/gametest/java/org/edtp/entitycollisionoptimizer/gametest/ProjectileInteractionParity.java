package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;

import java.util.ArrayList;
import java.util.List;

/** Player item-use creates the projectile; compare flight, impact, owner teleport/damage and inventory. */
final class ProjectileInteractionParity {
    static void verify(GameTestHelper helper) {
        int cases = 0;
        for (Item item : List.of(Items.ENDER_PEARL, Items.WIND_CHARGE)) {
            for (int fixture = 0; fixture < 5; fixture++) {
                var expected = run(helper, false, item, fixture);
                var actual = run(helper, true, item, fixture);
                for (int i = 0; i < expected.states.size(); i++) actual.states.get(i).compare(helper, expected.states.get(i),
                        "thrown " + item + " fixture=" + fixture + " observation=" + i);
                helper.assertValueEqual(actual.remaining, expected.remaining, "projectile item consumption " + item);
                helper.assertValueEqual(actual.cooldown, expected.cooldown, "projectile item cooldown " + item);
                cases++;
            }
        }
        EntityCollisionOptimizer.LOGGER.info("ECO_THROWN_PROJECTILES cases={} steps_per_case=16 result=passed", cases);
    }

    private static Outcome run(GameTestHelper helper, boolean enabled, Item item, int fixture) {
        try (var scene = new InteractionScene(helper)) {
            scene.floor(Blocks.STONE);
            for (int y = 1; y <= 5; y++) for (int z = 2; z <= 6; z++) scene.block(10, y, z, Blocks.OBSIDIAN);
            for (int y = 1; y <= 3; y++) {
                scene.block(6, y, 4, switch (fixture) {
                    case 1 -> Blocks.HONEY_BLOCK;
                    case 2 -> Blocks.STONE_STAIRS;
                    case 3 -> Blocks.WATER;
                    case 4 -> Blocks.AIR;
                    default -> Blocks.STONE;
                });
            }
            var player = (ServerPlayer) scene.spawn(EntityType.PLAYER, new Vec3(2.5, 1.0, 4.5));
            player.setGameMode(GameType.SURVIVAL);
            player.setNoGravity(true);
            player.setOnGround(true);
            player.setYRot(-90);
            player.setXRot(0);
            player.setDeltaMovement(Vec3.ZERO);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, 16));
            var target = scene.spawn(EntityType.ZOMBIE, new Vec3(fixture == 4 ? 6.0 : 11.0, 1, 4.5));
            target.setNoGravity(true);
            target.setDeltaMovement(Vec3.ZERO);
            CollisionFrame.begin(helper.getLevel());
            item.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            Projectile projectile = null;
            for (var entity : helper.getLevel().getAllEntities()) {
                if (entity instanceof Projectile candidate && candidate.getOwner() == player && !candidate.isRemoved()) {
                    helper.assertTrue(projectile == null, "item use must create exactly one projectile");
                    projectile = candidate;
                }
            }
            helper.assertTrue(projectile != null, "player item use must launch " + item);
            scene.track(projectile);
            // Normalize random launch spread, so both physical simulations receive identical initial velocity.
            projectile.setDeltaMovement(new Vec3(1.5, 0, 0));
            projectile.getRandom().setSeed(0xEC0262);
            Vec3 start = player.position();
            List<InteractionScene.State> states = new ArrayList<>();
            for (int step = 0; step < 16; step++) {
                InteractionScene.prepareTick(player);
                if (!projectile.isRemoved()) {
                    InteractionScene.prepareTick(projectile);
                    projectile.tick();
                }
                Vec3 before = player.position();
                player.move(MoverType.PLAYER, player.getDeltaMovement());
                // In 1.21.1 Entity.move already calls tryCheckInsideBlocks.
                states.add(InteractionScene.State.of(projectile));
                states.add(InteractionScene.State.of(player));
                states.add(InteractionScene.State.of(target));
                player.getCooldowns().tick();
            }
            helper.assertTrue(projectile.isRemoved(), "projectile fixture must reach an impact " + item);
            if (item == Items.ENDER_PEARL) helper.assertTrue(player.position().distanceToSqr(start) > 1 && player.getHealth() < player.getMaxHealth(),
                    "ender pearl comparison must include teleport and damage: fixture=" + fixture + " start=" + start
                            + " end=" + player.position() + " health=" + player.getHealth());
            return new Outcome(states, player.getMainHandItem().getCount(), player.getCooldowns().isOnCooldown(item));
        }
    }

    private record Outcome(List<InteractionScene.State> states, int remaining, boolean cooldown) {}
}

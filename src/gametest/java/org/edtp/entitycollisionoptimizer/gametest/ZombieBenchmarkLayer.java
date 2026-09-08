package org.edtp.entitycollisionoptimizer.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


/** One real stone chamber and its independently verified survival attacker. */
final class ZombieBenchmarkLayer {
    static final int ENTITY_COUNT = 1_000;
    static final int VERTICAL_STRIDE = 3;
    static final float BENCHMARK_HEALTH = 100_000_000.0F;
    static final int ATTACK_INTERVAL_TICKS = 13;
    final List<Zombie> zombies = new ArrayList<>(ENTITY_COUNT);
    final int index;
    private final Vec3 center;
    private final GameTestHelper helper;
    private ServerPlayer player;
    private EmbeddedChannel playerChannel;
    int trialAttacks, trialAcceptedAttacks, trialObservedKnockbacks;
    int totalAttacks, totalAcceptedAttacks, totalObservedKnockbacks;

    ZombieBenchmarkLayer(GameTestHelper helper, int index) {
        this.helper = helper;
        this.index = index;
        this.center = new Vec3(2.5, 1.0 + index * VERTICAL_STRIDE, 2.5);
    }

    void buildChamber() {
        int floor = index * VERTICAL_STRIDE;
        for (int y = floor; y <= floor + 3; y++) {
            for (int x = 1; x <= 3; x++) {
                for (int z = 1; z <= 3; z++) {
                    boolean wall = y == floor || y == floor + 3 || x == 1 || x == 3 || z == 1 || z == 3;
                    helper.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
    }
    void spawnPlayer() {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "eco-bench-" + index);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                profile,
                ClientInformation.createDefault()
        );
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        playerChannel = new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        if (player.gameMode() != GameType.SURVIVAL) {
            throw new IllegalStateException("Failed to create a survival benchmark player");
        }
        AttributeInstance maxHealth = Objects.requireNonNull(
                player.getAttribute(Attributes.MAX_HEALTH),
                "Player max-health attribute"
        );
        maxHealth.setBaseValue(BENCHMARK_HEALTH);

        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        var knockback = helper.getLevel()
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.KNOCKBACK);
        sword.enchant(knockback, 2);
        if (sword.getEnchantments().getLevel(knockback) != 2) {
            throw new IllegalStateException("Failed to equip Knockback II for the benchmark player");
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, sword);
        resetPlayerForTrial();
    }

    void resetPlayerForTrial() {
        player.setHealth(BENCHMARK_HEALTH);
        player.setPos(helper.absoluteVec(center));
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetAttackStrengthTicker();
        trialAttacks = 0;
        trialAcceptedAttacks = 0;
        trialObservedKnockbacks = 0;
    }

    void attackIfReady(int trialTick) {
        if (trialTick == 0 || trialTick % ATTACK_INTERVAL_TICKS != 0) {
            return;
        }

        Zombie target = zombies.get(trialAttacks % zombies.size());
        Vec3 movementBefore = target.getDeltaMovement();
        player.attack(target);
        Vec3 movementAfter = target.getDeltaMovement();

        trialAttacks++;
        totalAttacks++;
        if (target.getLastHurtByPlayer() == player) {
            trialAcceptedAttacks++;
            totalAcceptedAttacks++;
        }
        double horizontalChange = Math.abs(movementAfter.x - movementBefore.x)
                + Math.abs(movementAfter.z - movementBefore.z);
        if (horizontalChange > 1.0E-9) {
            trialObservedKnockbacks++;
            totalObservedKnockbacks++;
        }
    }

    void spawnPopulation() {
        for (int index = 0; index < ENTITY_COUNT; index++) {
            Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, center);
            AttributeInstance maxHealth = Objects.requireNonNull(
                    zombie.getAttribute(Attributes.MAX_HEALTH),
                    "Zombie max-health attribute"
            );
            maxHealth.setBaseValue(BENCHMARK_HEALTH);
            zombie.setHealth(BENCHMARK_HEALTH);
            zombie.setPersistenceRequired();
            zombies.add(zombie);
        }
    }

    void verifyPopulation() {
        long alive = zombies.stream().filter(zombie -> !zombie.isRemoved() && zombie.isAlive()).count();
        if (alive != ENTITY_COUNT) {
            throw new IllegalStateException(
                    "Benchmark population changed: " + alive + " of " + ENTITY_COUNT + " zombies remain"
            );
        }
        double floor = helper.absoluteVec(center).y;
        if (zombies.stream().anyMatch(z -> z.getY() < floor - 1.0E-5
                || z.getY() + z.getBbHeight() > floor + 2.0 + 1.0E-5)) {
            throw new IllegalStateException("Zombie escaped layer " + index);
        }
        if (player == null || player.isRemoved() || !player.isAlive()) {
            throw new IllegalStateException("Benchmark player did not survive the trial");
        }
        if (trialAttacks == 0 || trialAcceptedAttacks == 0) {
            throw new IllegalStateException(
                    "None of the " + trialAttacks + " player attacks were accepted by the vanilla damage path"
            );
        }
        if (trialObservedKnockbacks != trialAcceptedAttacks) {
            throw new IllegalStateException(
                    trialObservedKnockbacks + " of " + trialAcceptedAttacks
                            + " accepted Knockback II attacks changed horizontal velocity"
            );
        }
    }

    void discardPopulation() {
        for (Zombie zombie : zombies) {
            zombie.discard();
        }
        zombies.clear();
    }
    void cleanup() {
        discardPopulation();
        if (player != null) {
            helper.getLevel().getServer().getPlayerList().remove(player);
            player = null;
        }
        if (playerChannel != null) {
            playerChannel.close();
            playerChannel = null;
        }
    }
}

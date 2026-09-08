package org.edtp.entitycollisionoptimizer.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** A one-block spawn pedestal above a real 3x3 chamber and its bottom attacker. */
final class ZombieBenchmarkChamber {
    static final int SPAWN_PER_TICK = 100;
    static final int DROP_HEIGHT = 5;
    static final int ATTACK_INTERVAL_TICKS = 13;
    private static final Vec3 BOTTOM = new Vec3(3.5, 1.0, 3.5);
    private final GameTestHelper helper;
    private final List<Zombie> zombies = new ArrayList<>();
    private final Random spawnRandom = new Random(0xEC020026L);
    private ServerPlayer player;
    private EmbeddedChannel playerChannel;
    private Connection connection;
    private int removed;
    int spawned, attacks, acceptedAttacks, observedKnockbacks, sweepAttacks, sweepVictims;

    ZombieBenchmarkChamber(GameTestHelper helper) {
        this.helper = helper;
    }

    void build() {
        // Spawn feet at y=6; the ceiling leaves two full blocks above that position.
        for (int y = 0; y <= DROP_HEIGHT + 3; y++) {
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    boolean wall = y == 0 || y == DROP_HEIGHT + 3 || x == 1 || x == 5 || z == 1 || z == 5;
                    helper.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
        helper.setBlock(new BlockPos(3, DROP_HEIGHT, 3), Blocks.STONE);
    }

    void spawnPlayer() {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "eco-bench");
        player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                profile, ClientInformation.createDefault());
        connection = new Connection(PacketFlow.SERVERBOUND);
        playerChannel = new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(
                connection, player, CommonListenerCookie.createInitial(profile, false));
        // PlayerList admission alone does not register an EmbeddedChannel for connection ticks.
        // The normal listener tick drives ServerPlayer.doTick, equipment updates and cooldowns.
        helper.getLevel().getServer().getConnection().getConnections().add(connection);
        player.setGameMode(GameType.SURVIVAL);
        player.getAbilities().invulnerable = true;
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        var knockback = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.KNOCKBACK);
        sword.enchant(knockback, 2);
        if (sword.getEnchantments().getLevel(knockback) != 2) {
            throw new IllegalStateException("Failed to equip Knockback II");
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, sword);
        player.setPos(helper.absoluteVec(BOTTOM));
        player.setDeltaMovement(Vec3.ZERO);
        // A stationary server-side player standing on the real floor; no movement packets arrive.
        player.setOnGround(true);
        player.resetAttackStrengthTicker();
    }

    void spawnWave() {
        int before = zombies.size();
        zombies.removeIf(Entity::isRemoved);
        removed += before - zombies.size();
        Vec3 spawn = BOTTOM.add(0, DROP_HEIGHT, 0);
        for (int i = 0; i < SPAWN_PER_TICK; i++) {
            // Full 0.6-wide body starts supported by the same block; only its position varies.
            Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, spawn.add(
                    (spawnRandom.nextDouble() - 0.5) * 0.36, 0,
                    (spawnRandom.nextDouble() - 0.5) * 0.36));
            zombies.add(zombie);
            spawned++;
            if (zombie.getMaxHealth() != 20.0F || zombie.getHealth() != 20.0F
                    || zombie.isNoGravity() || zombie.isNoAi()) {
                throw new IllegalStateException("Zombie must spawn with vanilla health, gravity and AI");
            }
        }
        MovementScanDiagnostics.population(zombies);
    }

    void tickConnection() {
        // Consume outbound packets like a client, including replies required for a live connection.
        Object packet;
        while ((packet = playerChannel.readOutbound()) != null) {
            if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
                player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
            } else if (packet instanceof ClientboundPingPacket ping) {
                player.connection.handlePong(new ServerboundPongPacket(ping.getId()));
            }
        }
    }

    void attackIfReady(int tick) {
        if (tick == 0 || tick % ATTACK_INTERVAL_TICKS != 0) return;
        double floor = helper.absoluteVec(BOTTOM).y;
        Zombie target = null;
        for (Zombie zombie : zombies) {
            if (zombie.isAlive() && !zombie.isRemoved() && zombie.getY() <= floor + 0.5
                    && player.distanceToSqr(zombie) <= 9.0 && player.hasLineOfSight(zombie)) {
                target = zombie;
                break;
            }
        }
        if (target == null) return;
        Vec3 direction = target.position().subtract(player.position());
        player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        float[] healthBefore = new float[zombies.size()];
        for (int i = 0; i < zombies.size(); i++) healthBefore[i] = zombies.get(i).getHealth();
        float targetHealth = target.getHealth();
        Vec3 movementBefore = target.getDeltaMovement();
        if (player.getAttackStrengthScale(0.5F) <= 0.9F) {
            throw new IllegalStateException("Normal player ticks must recharge the sword between attacks");
        }
        player.attack(target);
        attacks++;
        if (target.getHealth() < targetHealth) acceptedAttacks++;
        Vec3 movementAfter = target.getDeltaMovement();
        if (Math.abs(movementAfter.x - movementBefore.x)
                + Math.abs(movementAfter.z - movementBefore.z) > 1.0E-9) observedKnockbacks++;
        int secondaryHits = 0;
        // Synchronous observation: no fall/cramming tick can intervene inside player.attack.
        for (int i = 0; i < zombies.size(); i++) {
            Zombie other = zombies.get(i);
            if (other != target && other.getHealth() < healthBefore[i]) secondaryHits++;
        }
        if (secondaryHits > 0) sweepAttacks++;
        sweepVictims += secondaryHits;
    }

    void reportPopulation(int tick) {
        long alive = zombies.stream().filter(z -> !z.isRemoved() && z.isAlive()).count();
        long dying = zombies.stream().filter(z -> !z.isRemoved() && !z.isAlive()).count();
        long despawned = removed + zombies.stream().filter(Entity::isRemoved).count();
        double floor = helper.absoluteVec(BOTTOM).y;
        long bottomAlive = zombies.stream().filter(z -> !z.isRemoved() && z.isAlive()
                && z.getY() <= floor + 0.5).count();
        EntityCollisionOptimizer.LOGGER.info(
                "ECO_BENCHMARK_POPULATION tick={} spawned={} alive={} dying={} removed={} attacks={} "
                        + "accepted_attacks={} observed_knockbacks={} sweep_attacks={} sweep_victims={} bottom_alive={}",
                tick, spawned, alive, dying, despawned, attacks, acceptedAttacks, observedKnockbacks,
                sweepAttacks, sweepVictims, bottomAlive);
    }

    void verify(int ticks) {
        if (spawned != ticks * SPAWN_PER_TICK) throw new IllegalStateException("Incorrect spawn count");
        if (player == null || player.isRemoved() || !player.isAlive() || player.gameMode() != GameType.SURVIVAL) {
            throw new IllegalStateException("Survival attacker did not survive");
        }
        if (attacks == 0 || acceptedAttacks == 0 || observedKnockbacks == 0 || sweepAttacks == 0) {
            throw new IllegalStateException("No successful bottom Knockback II and sweeping attacks");
        }
    }

    void cleanup() {
        zombies.forEach(Entity::discard);
        zombies.clear();
        // Death drops remain real entities throughout measurement; remove them only afterwards.
        Vec3 origin = helper.absoluteVec(Vec3.ZERO);
        AABB chamber = new AABB(origin.add(1, 0, 1), origin.add(6, DROP_HEIGHT + 4, 6));
        for (Entity entity : helper.getLevel().getEntities((Entity) null, chamber,
                e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) entity.discard();
        if (player != null) {
            helper.getLevel().getServer().getPlayerList().remove(player);
            player = null;
        }
        if (playerChannel != null) {
            playerChannel.finishAndReleaseAll();
            playerChannel = null;
        }
        if (connection != null) {
            helper.getLevel().getServer().getConnection().getConnections().remove(connection);
            connection = null;
        }
    }
}

package org.edtp.entitycollisionoptimizer.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class CollisionTestSupport {
    private CollisionTestSupport() {
    }

    static void assertEntityOutcomeMatches(
            GameTestHelper helper,
            Entity vanilla,
            Entity accelerated,
            String scenario
    ) {
        assertVectorEqual(
                helper,
                accelerated.getDeltaMovement(),
                vanilla.getDeltaMovement(),
                scenario + " delta movement"
        );
        helper.assertValueEqual(accelerated.isAlive(), vanilla.isAlive(), scenario + " alive state");
        if (vanilla instanceof LivingEntity vanillaLiving
                && accelerated instanceof LivingEntity acceleratedLiving) {
            helper.assertValueEqual(
                    acceleratedLiving.getHealth(),
                    vanillaLiving.getHealth(),
                    scenario + " health"
            );
        }
    }

    static void assertVectorEqual(
            GameTestHelper helper,
            Vec3 actual,
            Vec3 expected,
            String scenario
    ) {
        helper.assertTrue(
                sameComponent(expected.x, actual.x) && sameComponent(expected.y, actual.y)
                        && sameComponent(expected.z, actual.z),
                scenario + ": expected=" + expected + ", actual=" + actual
        );
    }

    private static boolean sameComponent(double expected, double actual) {
        return Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual)
                || (Double.isFinite(expected) && Double.isFinite(actual) && Math.abs(expected - actual) <= 1.0E-12);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Entity spawnEntity(
            GameTestHelper helper,
            EntityType<? extends Entity> type,
            Vec3 position
    ) {
        Entity entity = helper.spawn((EntityType) type, position);
        entity.setNoGravity(true);
        entity.setInvulnerable(false);
        entity.setSilent(true);
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        return entity;
    }

    static Zombie spawnZombie(GameTestHelper helper, Vec3 position) {
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, position);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        zombie.setSilent(true);
        zombie.setPersistenceRequired();
        return zombie;
    }

    @SuppressWarnings("removal")
    static ServerPlayer spawnPlayer(GameTestHelper helper, Vec3 position) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 absolutePosition = helper.absoluteVec(position);
        player.teleportTo(absolutePosition.x, absolutePosition.y, absolutePosition.z);
        return player;
    }

    static ServerPlayer spawnMockServerPlayer(
            GameTestHelper helper,
            Vec3 position,
            GameType gameType
    ) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(playerId, "ar-test-" + playerId.toString().substring(0, 8)),
                ClientInformation.createDefault()
        );
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(player.getGameProfile(), false);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(gameType);
        Vec3 absolutePosition = helper.absoluteVec(position);
        player.teleportTo(absolutePosition.x, absolutePosition.y, absolutePosition.z);
        return player;
    }

    static void zeroVelocities(List<? extends Entity> entities) {
        for (Entity entity : entities) {
            entity.setDeltaMovement(Vec3.ZERO);
        }
    }

    static List<Vec3> velocities(List<? extends Entity> entities) {
        List<Vec3> result = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            result.add(entity.getDeltaMovement());
        }
        return result;
    }

    static Set<Entity> identitySet(List<? extends Entity> entities) {
        Set<Entity> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(entities);
        return result;
    }

    static long seedWhoseNextIntSucceeds(LivingEntity entity, int bound) {
        for (long seed = 0; seed < 10_000; seed++) {
            entity.getRandom().setSeed(seed);
            if (entity.getRandom().nextInt(bound) == 0) {
                return seed;
            }
        }
        throw new IllegalStateException("Could not find a deterministic random seed for bound " + bound);
    }

    static long seedWhoseNextIntFails(LivingEntity entity, int bound) {
        for (long seed = 0; seed < 10_000; seed++) {
            entity.getRandom().setSeed(seed);
            if (entity.getRandom().nextInt(bound) != 0) {
                return seed;
            }
        }
        throw new IllegalStateException("Could not find a deterministic failing random seed for bound " + bound);
    }
}

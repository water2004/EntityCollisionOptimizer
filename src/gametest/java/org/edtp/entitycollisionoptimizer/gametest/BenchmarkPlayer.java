package org.edtp.entitycollisionoptimizer.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** A stationary survival player with normal connection-driven ticks, without a Carpet dependency. */
final class BenchmarkPlayer implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerPlayer player;
    private final Connection connection;
    private final EmbeddedChannel channel;

    BenchmarkPlayer(GameTestHelper helper, String name, Vec3 position) {
        server = helper.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        player = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
        connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        // Admission alone does not register an EmbeddedChannel for the normal listener tick.
        server.getConnection().getConnections().add(connection);
        player.setGameMode(GameType.SURVIVAL);
        player.getAbilities().invulnerable = true;
        player.setPos(helper.absoluteVec(position));
        player.setDeltaMovement(Vec3.ZERO);
        player.setOnGround(true);
    }

    ServerPlayer player() { return player; }

    void respondToPackets() {
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
                player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
            } else if (packet instanceof ClientboundPingPacket ping) {
                player.connection.handlePong(new ServerboundPongPacket(ping.getId()));
            }
        }
    }

    @Override public void close() {
        server.getPlayerList().remove(player);
        channel.finishAndReleaseAll();
        server.getConnection().getConnections().remove(connection);
    }
}

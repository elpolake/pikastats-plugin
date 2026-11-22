package dev.PikaStats;

import com.viaversion.viaversion.api.connection.UserConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.util.List;
import java.util.function.Consumer;

/** Per-connection hook: feeds chat to the command handler and roster packets to the overlay tracker. */
final class PikaStatsPacketHandler extends PacketHandler {
    private final ProxyConnection connection;
    private final Services services;
    private final PikaStatsOverlayTracker tracker;
    private final CommandHandler commands;
    private final ChatIO chatIO;

    PikaStatsPacketHandler(ProxyConnection connection, Services services) {
        super(connection);
        this.connection = connection;
        this.services = services;
        ChatIO io = new ChatIO(connection, () -> services.config().chatDelayMillis());
        this.tracker = new PikaStatsOverlayTracker(services, this::ownName);
        this.commands = new CommandHandler(services, io, this.tracker, this::ownName);
        this.chatIO = io;
        Channel channel = connection.getChannel();
        if (channel != null) {
            channel.closeFuture().addListener(ignored -> {
                this.tracker.onConnectionClosed();
            });
        }
    }

    private String ownName() {
        UserConnection user = this.connection.getUserConnection();
        if (user != null && user.getProtocolInfo() != null && user.getProtocolInfo().getUsername() != null) {
            return user.getProtocolInfo().getUsername();
        }
        return this.connection.getGameProfile() == null ? null : this.connection.getGameProfile().getName();
    }

    @Override
    public boolean handleC2P(Packet packet, List<ChannelFutureListener> listeners) {
        if (!(packet instanceof UnknownPacket unknown) || this.connection.getC2pConnectionState() != ConnectionState.PLAY) {
            return true;
        }
        try {
            String input = this.chatIO.readClientInput(unknown);
            if (input != null) {
                if (this.commands.onClientInput(input) == CommandHandler.Outcome.CONSUME) {
                    return false;
                }
                this.chatIO.noteOutgoing();
            }
        } catch (Exception error) {
            System.err.println("[PikaStats] Ignored an unreadable outgoing chat packet: " + error.getMessage());
        }
        return true;
    }

    @Override
    public boolean handleP2S(Packet packet, List<ChannelFutureListener> listeners) {
        if (!(packet instanceof UnknownPacket unknown)
                || this.connection.getP2sConnectionState() != ConnectionState.PLAY
                || this.connection.getUserConnection() == null) {
            return true;
        }
        try {
            this.tracker.handlePacket(unknown, this.connection.getClientVersion());
            String chat = this.chatIO.readServerChat(unknown);
            if (chat != null) {
                this.tracker.onChatText(chat);
                this.commands.onServerChat(chat);
            }
        } catch (Exception error) {
            // A malformed or version-specific packet must never affect the proxy connection.
            System.err.println("[PikaStats] Ignored an unreadable chat packet: " + error.getMessage());
        }
        return true;
    }
}

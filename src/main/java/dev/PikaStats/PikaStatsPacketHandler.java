package dev.PikaStats;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.PlayerMessageSignature;
import com.viaversion.viaversion.api.minecraft.signature.model.DecoratableMessage;
import com.viaversion.viaversion.api.minecraft.signature.model.MessageMetadata;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_1;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_3;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.nbt.tag.Tag;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class PikaStatsPacketHandler extends PacketHandler {
    private static final int MAX_CHAT_COMPONENT_LENGTH = 262_144;
    private static final Pattern UNSUPPORTED_PUBLIC_CHAT_CHARS = Pattern.compile("[^A-Za-z0-9_ .,:|()/\\-]");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProxyConnection connection;
    private final PikaStatsApi api;
    private final PikaStatsOverlayTracker overlayTracker;
    private final Map<String, ArrayDeque<Long>> pendingStatsChatEchoes = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> pendingPunishmentChatEchoes = new HashMap<>();

    PikaStatsPacketHandler(ProxyConnection connection, PikaStatsApi api, PikaStatsOverlayTracker overlayTracker) {
        super(connection);
        this.connection = connection;
        this.api = api;
        this.overlayTracker = overlayTracker;
        Channel channel = connection.getChannel();
        if (channel != null) {
            channel.closeFuture().addListener(ignored -> this.overlayTracker.onConnectionClosed());
        }
    }

    @Override
    public boolean handleC2P(Packet packet, List<ChannelFutureListener> listeners) {
        if (packet instanceof UnknownPacket unknownPacket
                && this.connection.getC2pConnectionState() == ConnectionState.PLAY) {
            ProtocolVersion version = this.connection.getClientVersion();
            if (version != null && unknownPacket.packetId == MCPackets.C2S_CHAT.getId(version.getVersion())) {
                try {
                    String chatText = readClientChatText(unknownPacket);
                    PikaStatsChatParser.ParseResult result = PikaStatsChatParser.parseBroadcast(chatText);
                    if (result.detected() && result.request() != null) {
                        noteOutgoingStatsRequest(result.request());
                        requestStats(result.request());
                    } else if (result.detected()) {
                        sendPublic("Usage: -stats <player> [period mode] [stat]. Keep your API key in plugins/PikaStats/overlay.properties, not in chat.");
                    }

                    PikaStatsChatParser.PunishmentsParseResult punishments =
                            PikaStatsChatParser.parsePunishmentsBroadcast(chatText);
                    if (punishments.detected()) {
                        if (punishments.username() == null) {
                            sendPublic("Usage: -punishments <player> [reason|count].");
                        } else {
                            noteOutgoingPunishmentsRequest(punishments.username());
                            requestPunishments(punishments.username(), punishments.mode());
                        }
                    }
                } catch (Exception error) {
                    System.err.println("[PikaStats] Ignored an unreadable outgoing chat packet: "
                            + error.getMessage());
                }
            }
        }
        // Let the original chat packet continue to the server unchanged.
        return true;
    }

    @Override
    public boolean handleP2S(Packet packet, List<ChannelFutureListener> listeners) {
        if (!(packet instanceof UnknownPacket unknownPacket)
                || this.connection.getP2sConnectionState() != ConnectionState.PLAY
                || this.connection.getUserConnection() == null) {
            return true;
        }

        try {
            this.overlayTracker.handlePacket(unknownPacket, this.connection.getClientVersion());
            String chatText = readChatText(unknownPacket);
            this.overlayTracker.onChatText(chatText);
            PikaStatsChatParser.ParseResult result = PikaStatsChatParser.parseBroadcast(chatText);
            if (result.detected() && result.request() != null && !consumeOutgoingStatsRequest(result.request())) {
                requestStats(result.request());
            }
            PikaStatsChatParser.PunishmentsParseResult punishments =
                    PikaStatsChatParser.parsePunishmentsBroadcast(chatText);
            if (punishments.detected() && punishments.username() != null
                    && !consumeOutgoingPunishmentsRequest(punishments.username())) {
                requestPunishments(punishments.username(), punishments.mode());
            }
        } catch (Exception error) {
            // A malformed or version-specific chat packet must not affect the proxy connection.
            System.err.println("[PikaStats] Ignored an unreadable chat packet: " + error.getMessage());
        }
        return true;
    }

    private static String readClientChatText(UnknownPacket packet) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packet.data == null ? new byte[0] : packet.data);
        try {
            return PacketTypes.readString(input, 256);
        } finally {
            input.release();
        }
    }

    private synchronized void noteOutgoingStatsRequest(StatsRequest request) {
        long now = System.currentTimeMillis();
        pruneOutgoingStatsRequests(now);
        this.pendingStatsChatEchoes.computeIfAbsent(requestKey(request), ignored -> new ArrayDeque<>()).addLast(now);
    }

    private synchronized boolean consumeOutgoingStatsRequest(StatsRequest request) {
        long now = System.currentTimeMillis();
        pruneOutgoingStatsRequests(now);
        String key = requestKey(request);
        ArrayDeque<Long> timestamps = this.pendingStatsChatEchoes.get(key);
        if (timestamps == null || timestamps.isEmpty()) {
            return false;
        }
        timestamps.removeFirst();
        if (timestamps.isEmpty()) {
            this.pendingStatsChatEchoes.remove(key);
        }
        return true;
    }

    private synchronized void noteOutgoingPunishmentsRequest(String username) {
        long now = System.currentTimeMillis();
        pruneOutgoingPunishmentsRequests(now);
        this.pendingPunishmentChatEchoes.computeIfAbsent(username.toLowerCase(Locale.ROOT), ignored -> new ArrayDeque<>())
                .addLast(now);
    }

    private synchronized boolean consumeOutgoingPunishmentsRequest(String username) {
        long now = System.currentTimeMillis();
        pruneOutgoingPunishmentsRequests(now);
        String key = username.toLowerCase(Locale.ROOT);
        ArrayDeque<Long> timestamps = this.pendingPunishmentChatEchoes.get(key);
        if (timestamps == null || timestamps.isEmpty()) {
            return false;
        }
        timestamps.removeFirst();
        if (timestamps.isEmpty()) {
            this.pendingPunishmentChatEchoes.remove(key);
        }
        return true;
    }

    private void pruneOutgoingStatsRequests(long now) {
        this.pendingStatsChatEchoes.entrySet().removeIf(entry -> {
            ArrayDeque<Long> timestamps = entry.getValue();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > 10_000L) {
                timestamps.removeFirst();
            }
            return timestamps.isEmpty();
        });
    }

    private void pruneOutgoingPunishmentsRequests(long now) {
        this.pendingPunishmentChatEchoes.entrySet().removeIf(entry -> {
            ArrayDeque<Long> timestamps = entry.getValue();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > 10_000L) {
                timestamps.removeFirst();
            }
            return timestamps.isEmpty();
        });
    }

    private static String requestKey(StatsRequest request) {
        return request.username().toLowerCase(Locale.ROOT) + ':' + request.period().name() + ':'
                + request.mode().name();
    }

    private String readChatText(UnknownPacket packet) throws Exception {
        // PacketHandlers run on ViaProxy's client-facing side of the translator.
        ProtocolVersion version = this.connection.getClientVersion();
        if (version == null) {
            return null;
        }
        int protocolVersion = version.getVersion();
        int chatMessageId = MCPackets.S2C_CHAT_MESSAGE.getId(protocolVersion);
        int systemChatId = MCPackets.S2C_SYSTEM_CHAT.getId(protocolVersion);
        int playerChatId = MCPackets.S2C_PLAYER_CHAT.getId(protocolVersion);
        if (packet.packetId != chatMessageId && packet.packetId != systemChatId && packet.packetId != playerChatId) {
            return null;
        }

        ByteBuf input = Unpooled.wrappedBuffer(packet.data == null ? new byte[0] : packet.data);
        try {
            if (packet.packetId == playerChatId && playerChatId >= 0) {
                return readPlayerChat(input, version);
            }

            String component;
            boolean modernComponent = version.newerThanOrEqualTo(ProtocolVersion.v1_20_3);
            if (packet.packetId == systemChatId && modernComponent) {
                Tag tag = Types.TEXT_COMPONENT_TAG.read(input);
                component = PikaStatsChatParser.tagToPlainText(tag);
            } else {
                component = PacketTypes.readString(input, MAX_CHAT_COMPONENT_LENGTH);
            }
            if (packet.packetId == chatMessageId && chatMessageId >= 0 && input.isReadable()) {
                int position = input.readUnsignedByte();
                if (position == 2) { // Ignore action-bar messages.
                    return null;
                }
            } else if (packet.packetId == systemChatId && systemChatId >= 0 && input.isReadable()) {
                boolean overlay = input.readBoolean();
                if (overlay) {
                    return null;
                }
            }
            return PikaStatsChatParser.componentToPlainText(component);
        } finally {
            input.release();
        }
    }

    private static String readPlayerChat(ByteBuf input, ProtocolVersion version) throws Exception {
        if (version.olderThan(ProtocolVersion.v1_19_3)) {
            return null;
        }
        Types.UUID.read(input); // Sender UUID
        Types.VAR_INT.read(input); // Message index
        Types.OPTIONAL_SIGNATURE_BYTES.read(input);
        return PacketTypes.readString(input, 256);
    }

    private void requestStats(StatsRequest request) {
        this.api.lookup(request).whenComplete((stats, error) -> {
            if (error != null) {
                System.err.println("[PikaStats] Lookup failed for " + request.username() + ": " + rootMessage(error));
                if (PikaStatsApi.errorKind(error) == PikaStatsApi.ErrorKind.PLAYER_NOT_FOUND) {
                    sendPublic("Player not found: " + request.username());
                } else if (PikaStatsApi.errorKind(error) == PikaStatsApi.ErrorKind.AUTH_REQUIRED) {
                    sendPublic("PikaNetwork requires a valid API key. Run /apikey, set api-key in plugins/PikaStats/overlay.properties, then restart ViaProxy.");
                } else if (PikaStatsApi.errorKind(error) == PikaStatsApi.ErrorKind.RATE_LIMITED) {
                    sendPublic("PikaNetwork is rate limiting stats requests. Try again shortly.");
                } else if (PikaStatsApi.errorKind(error) == PikaStatsApi.ErrorKind.BAD_REQUEST) {
                    sendPublic("PikaNetwork rejected the stats query.");
                } else {
                    sendPublic("PikaNetwork stats are temporarily unavailable.");
                }
                return;
            }

            sendPublic(stats.publicMessage(request));
        });
    }

    private void requestPunishments(String username, PikaStatsChatParser.PunishmentsMode mode) {
        this.api.lookupPunishments(username).whenComplete((punishments, error) -> {
            if (error != null) {
                System.err.println("[PikaStats] Punishments lookup failed for " + username + ": " + rootMessage(error));
                if (PikaStatsApi.errorKind(error) == PikaStatsApi.ErrorKind.PLAYER_NOT_FOUND) {
                    sendPublic("No punishment record found.");
                } else {
                    sendPublic("Punishment lookup failed.");
                }
                return;
            }
            switch (mode) {
                case COUNT -> sendPublic(punishments.countsMessage());
                case REASON -> sendPublicSequence(punishments.reasonMessages(), 250L);
                case DEFAULT -> sendPublic(punishments.compactMessage());
            }
        });
    }

    private void sendPublicSequence(List<String> messages, long delayMillis) {
        Channel channel = this.connection.getChannel();
        if (channel == null || !channel.isActive()) {
            return;
        }
        for (int index = 0; index < messages.size(); index++) {
            String message = messages.get(index);
            long delay = index * delayMillis;
            try {
                channel.eventLoop().schedule(() -> sendPublicOnEventLoop(channel, message), delay,
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException error) {
                System.err.println("[PikaStats] Could not schedule punishment chat response: " + error.getMessage());
                return;
            }
        }
    }

    private void sendPublic(String message) {
        Channel channel = this.connection.getChannel();
        if (channel == null || !channel.isActive()) {
            return;
        }
        try {
            channel.eventLoop().execute(() -> sendPublicOnEventLoop(channel, message));
        } catch (RuntimeException error) {
            System.err.println("[PikaStats] Could not schedule public chat response: " + error.getMessage());
        }
    }

    private void sendPublicOnEventLoop(Channel channel, String message) {
        if (!channel.isActive()) {
            return;
        }
        // The raw packet is written into ViaProxy's serverbound pipeline, which translates
        // this client-version packet to the server's protocol before sending it.
        ProtocolVersion version = this.connection.getClientVersion();
        if (version == null) {
            return;
        }

        message = fitServerChatLimit(message);

        int protocolVersion = version.getVersion();
        int packetId = MCPackets.C2S_CHAT.getId(protocolVersion);
        if (packetId < 0) {
                System.err.println("[PikaStats] Public chat is unavailable for client protocol " + version.getName());
            return;
        }

        ByteBuf output = Unpooled.buffer();
        try {
            PacketTypes.writeVarInt(output, packetId);
            if (version.newerThanOrEqualTo(ProtocolVersion.v1_19_3)) {
                writeSignedChat(output, message, version);
            } else if (version.newerThanOrEqualTo(ProtocolVersion.v1_19_1)) {
                writeLegacySignedChat(output, message);
            } else if (version.newerThanOrEqualTo(ProtocolVersion.v1_19)) {
                writeChat19(output, message);
            } else {
                PacketTypes.writeString(output, message);
            }
            channel.writeAndFlush(output).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        } catch (Exception error) {
            output.release();
            System.err.println("[PikaStats] Could not send public chat response: " + error.getMessage());
        }
    }

    private String fitServerChatLimit(String message) {
        message = UNSUPPORTED_PUBLIC_CHAT_CHARS.matcher(message).replaceAll("");
        ProtocolVersion serverVersion = this.connection.getServerVersion();
        int maxLength = serverVersion == null || serverVersion.olderThan(ProtocolVersion.v1_11) ? 100 : 256;
        if (message.length() <= maxLength) {
            return message;
        }

        // 1.8 servers disconnect clients for chat messages longer than 100 characters.
        int end = maxLength - 3;
        if (end > 0 && end < message.length()
                && Character.isHighSurrogate(message.charAt(end - 1))
                && Character.isLowSurrogate(message.charAt(end))) {
            end--;
        }
        return message.substring(0, end).stripTrailing() + "...";
    }

    private void writeSignedChat(ByteBuf output, String message, ProtocolVersion clientVersion) throws Exception {
        UserConnection userConnection = this.connection.getUserConnection();
        if (userConnection == null || !userConnection.has(ChatSession1_19_3.class)) {
            throw new IllegalStateException("No signed chat session is available");
        }

        long timestamp = System.currentTimeMillis();
        long salt = RANDOM.nextLong();
        MessageMetadata metadata = new MessageMetadata(null, timestamp, salt);
        ChatSession1_19_3 session = userConnection.get(ChatSession1_19_3.class);
        byte[] signature = session.signChatMessage(metadata, message, new com.viaversion.viaversion.api.minecraft.PlayerMessageSignature[0]);

        PacketTypes.writeString(output, message);
        output.writeLong(timestamp);
        output.writeLong(salt);
        Types.OPTIONAL_SIGNATURE_BYTES.write(output, signature);
        PacketTypes.writeVarInt(output, 0);
        Types.ACKNOWLEDGED_BIT_SET.write(output, new BitSet(20));
        if (clientVersion.newerThanOrEqualTo(ProtocolVersion.v1_21_5)) {
            output.writeByte(0);
        }
    }

    private void writeLegacySignedChat(ByteBuf output, String message) throws Exception {
        UserConnection userConnection = this.connection.getUserConnection();
        if (userConnection == null || !userConnection.has(ChatSession1_19_1.class)) {
            throw new IllegalStateException("No signed chat session is available");
        }

        long timestamp = System.currentTimeMillis();
        long salt = RANDOM.nextLong();
        MessageMetadata metadata = new MessageMetadata(userConnection.getProtocolInfo().getUuid(), timestamp, salt);
        ChatSession1_19_1 session = userConnection.get(ChatSession1_19_1.class);
        byte[] signature = session.signChatMessage(
                metadata,
                new DecoratableMessage(message),
                new PlayerMessageSignature[0]);

        PacketTypes.writeString(output, message);
        output.writeLong(timestamp);
        output.writeLong(salt);
        Types.OPTIONAL_SIGNATURE_BYTES.write(output, signature);
        PacketTypes.writeVarInt(output, 0);
        Types.ACKNOWLEDGED_BIT_SET.write(output, new BitSet(20));
    }

    private static void writeChat19(ByteBuf output, String message) {
        // Minecraft 1.19.0 has an unsigned signature byte-array field and no chat-session ack fields.
        PacketTypes.writeString(output, message);
        output.writeLong(System.currentTimeMillis());
        output.writeLong(RANDOM.nextLong());
        Types.BYTE_ARRAY_PRIMITIVE.write(output, new byte[0]);
        Types.BOOLEAN.write(output, false);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}

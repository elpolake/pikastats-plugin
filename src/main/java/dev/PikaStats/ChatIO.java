package dev.PikaStats;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.PlayerMessageSignature;
import com.viaversion.viaversion.api.minecraft.signature.model.DecoratableMessage;
import com.viaversion.viaversion.api.minecraft.signature.model.MessageMetadata;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_1;
import com.viaversion.viaversion.api.minecraft.signature.storage.ChatSession1_19_3;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer;
import com.viaversion.viaversion.util.ComponentUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Reads and writes chat for one proxied connection. Public messages go through a queue with a
 * minimum delay so the server's anti-spam never kicks the player.
 */
final class ChatIO {
    private static final int MAX_COMPONENT_LENGTH = 262_144;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProxyConnection connection;
    private final IntSupplier delayMillis;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean draining;
    private volatile long lastSentAtMillis;

    ChatIO(ProxyConnection connection, IntSupplier delayMillis) {
        this.connection = connection;
        this.delayMillis = delayMillis;
    }

    // ---------------------------------------------------------------- reading

    /** Text the client typed: a chat message, or a command with its leading slash restored. */
    String readClientInput(UnknownPacket packet) throws Exception {
        ProtocolVersion version = this.connection.getClientVersion();
        if (version == null) {
            return null;
        }
        int protocol = version.getVersion();
        boolean chat = packet.packetId == MCPackets.C2S_CHAT.getId(protocol);
        boolean command = packet.packetId >= 0 && (packet.packetId == MCPackets.C2S_CHAT_COMMAND.getId(protocol)
                || packet.packetId == MCPackets.C2S_CHAT_COMMAND_SIGNED.getId(protocol));
        if (!chat && !command) {
            return null;
        }
        ByteBuf input = Unpooled.wrappedBuffer(packet.data == null ? new byte[0] : packet.data);
        try {
            String text = PacketTypes.readString(input, 32_767);
            return command ? "/" + text : text;
        } finally {
            input.release();
        }
    }

    /** Plain text of a server chat packet, or null for anything else (including action bar). */
    String readServerChat(UnknownPacket packet) throws Exception {
        ProtocolVersion version = this.connection.getClientVersion();
        if (version == null) {
            return null;
        }
        int protocol = version.getVersion();
        int chatMessageId = MCPackets.S2C_CHAT_MESSAGE.getId(protocol);
        int systemChatId = MCPackets.S2C_SYSTEM_CHAT.getId(protocol);
        int playerChatId = MCPackets.S2C_PLAYER_CHAT.getId(protocol);
        int disguisedChatId = MCPackets.S2C_DISGUISED_CHAT.getId(protocol);
        int id = packet.packetId;
        if (id < 0 || (id != chatMessageId && id != systemChatId && id != playerChatId && id != disguisedChatId)) {
            return null;
        }
        ByteBuf input = Unpooled.wrappedBuffer(packet.data == null ? new byte[0] : packet.data);
        try {
            if (id == playerChatId) {
                if (version.olderThan(ProtocolVersion.v1_19_3)) {
                    return null;
                }
                Types.UUID.read(input);
                Types.VAR_INT.read(input);
                Types.OPTIONAL_SIGNATURE_BYTES.read(input);
                return PacketTypes.readString(input, 256);
            }
            boolean modern = version.newerThanOrEqualTo(ProtocolVersion.v1_20_3);
            String text = modern ? tagToPlainText(Types.TEXT_COMPONENT_TAG.read(input))
                    : componentToPlainText(PacketTypes.readString(input, MAX_COMPONENT_LENGTH));
            if (id == chatMessageId && input.isReadable() && input.readUnsignedByte() == 2) {
                return null; // Action bar.
            }
            if (id == systemChatId && version.newerThanOrEqualTo(ProtocolVersion.v1_19_1) && input.isReadable()
                    && input.readBoolean()) {
                return null; // Overlay (action bar).
            }
            return text;
        } finally {
            input.release();
        }
    }

    static String componentToPlainText(String componentJson) {
        try {
            JsonElement element = JsonParser.parseString(componentJson);
            return TextComponentSerializer.V1_8.forkIfDefault().deserialize(element.toString()).asUnformattedString();
        } catch (RuntimeException exception) {
            try {
                JsonElement element = JsonParser.parseString(componentJson);
                return element.isJsonPrimitive() ? element.getAsString() : componentJson;
            } catch (RuntimeException ignored) {
                return componentJson;
            }
        }
    }

    static String tagToPlainText(Tag tag) {
        StringBuilder text = new StringBuilder();
        appendTagText(tag, text);
        return text.toString();
    }

    private static void appendTagText(Tag tag, StringBuilder output) {
        if (tag instanceof StringTag stringTag) {
            output.append(stringTag.getValue());
        } else if (tag instanceof CompoundTag compound) {
            Tag text = compound.get("text");
            if (text == null) {
                text = compound.get("");
            }
            if (text != null) {
                appendTagText(text, output);
            }
            Tag with = compound.get("with");
            if (with instanceof ListTag<?> list) {
                for (Tag child : list) {
                    appendTagText(child, output);
                }
            }
            Tag extra = compound.get("extra");
            if (extra instanceof ListTag<?> list) {
                for (Tag child : list) {
                    appendTagText(child, output);
                }
            }
        } else if (tag instanceof ListTag<?> list) {
            for (Tag child : list) {
                appendTagText(child, output);
            }
        }
    }

    // ---------------------------------------------------------------- client-only messages

    /** Shows a formatted message ({@code &#rrggbb} / {@code &a} codes) only to this player. */
    void sendClient(String formatted) {
        Channel client = this.connection.getC2P();
        ProtocolVersion version = this.connection.getClientVersion();
        if (client == null || !client.isActive() || version == null) {
            return;
        }
        try {
            JsonObject component = ChatFormat.toComponent(formatted,
                    version.newerThanOrEqualTo(ProtocolVersion.v1_16));
            int protocol = version.getVersion();
            ByteBuf data = Unpooled.buffer();
            int packetId;
            if (version.newerThanOrEqualTo(ProtocolVersion.v1_19)) {
                packetId = MCPackets.S2C_SYSTEM_CHAT.getId(protocol);
                if (version.newerThanOrEqualTo(ProtocolVersion.v1_20_3)) {
                    Types.TEXT_COMPONENT_TAG.write(data, ComponentUtil.jsonToTag(component));
                } else {
                    PacketTypes.writeString(data, component.toString());
                }
                if (version.newerThanOrEqualTo(ProtocolVersion.v1_19_1)) {
                    data.writeBoolean(false);
                } else {
                    PacketTypes.writeVarInt(data, 1); // 1.19.0: system message type.
                }
            } else {
                packetId = MCPackets.S2C_CHAT_MESSAGE.getId(protocol);
                PacketTypes.writeString(data, component.toString());
                if (version.newerThanOrEqualTo(ProtocolVersion.v1_8)) {
                    data.writeByte(1); // System message position.
                }
                if (version.newerThanOrEqualTo(ProtocolVersion.v1_16)) {
                    Types.UUID.write(data, new UUID(0L, 0L));
                }
            }
            if (packetId < 0) {
                data.release();
                return;
            }
            byte[] bytes = new byte[data.readableBytes()];
            data.readBytes(bytes);
            data.release();
            client.writeAndFlush(new UnknownPacket(packetId, bytes))
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        } catch (Exception error) {
            System.err.println("[PikaStats] Could not show a local message: " + error.getMessage());
        }
    }

    // ---------------------------------------------------------------- server chat

    /** Queues messages for the server; they are sent in order with the configured spacing. */
    void sendServer(List<String> messages) {
        Channel channel = this.connection.getChannel();
        if (channel == null || !channel.isActive() || messages.isEmpty()) {
            return;
        }
        channel.eventLoop().execute(() -> {
            this.queue.addAll(messages);
            if (!this.draining) {
                this.draining = true;
                drain(channel);
            }
        });
    }

    void sendServer(String message) {
        sendServer(List.of(message));
    }

    /** The player just sent chat themselves; keep our next message a full delay after it. */
    void noteOutgoing() {
        this.lastSentAtMillis = System.currentTimeMillis();
    }

    /**
     * Longest chat message that survives the trip to the server: 100 characters when either the
     * client or the server speaks a version before 1.11 (ViaVersion cuts longer ones), else 256.
     */
    int protocolChatLimit() {
        ProtocolVersion client = this.connection.getClientVersion();
        ProtocolVersion server = this.connection.getServerVersion();
        boolean old = (client != null && client.olderThan(ProtocolVersion.v1_11))
                || (server != null && server.olderThan(ProtocolVersion.v1_11));
        return old ? 100 : 256;
    }

    int queuedMessages() {
        return this.queue.size();
    }

    private void drain(Channel channel) {
        if (!channel.isActive()) {
            this.queue.clear();
            this.draining = false;
            return;
        }
        long wait = this.lastSentAtMillis + this.delayMillis.getAsInt() - System.currentTimeMillis();
        if (wait > 0) {
            channel.eventLoop().schedule(() -> drain(channel), wait, TimeUnit.MILLISECONDS);
            return;
        }
        String message = this.queue.pollFirst();
        if (message == null) {
            this.draining = false;
            return;
        }
        writeServerChat(channel, message);
        this.lastSentAtMillis = System.currentTimeMillis();
        if (this.queue.isEmpty()) {
            this.draining = false;
        } else {
            channel.eventLoop().schedule(() -> drain(channel), this.delayMillis.getAsInt(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Writes a chat packet in the client's protocol into ViaProxy's serverbound pipeline, which
     * translates it to the server's protocol.
     */
    private void writeServerChat(Channel channel, String message) {
        ProtocolVersion version = this.connection.getClientVersion();
        if (version == null) {
            return;
        }
        message = stripUnsafe(message);
        int maxLength = protocolChatLimit();
        if (message.length() > maxLength) {
            message = message.substring(0, maxLength);
        }
        int packetId = MCPackets.C2S_CHAT.getId(version.getVersion());
        if (packetId < 0 || message.isBlank()) {
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
                PacketTypes.writeString(output, message);
                output.writeLong(System.currentTimeMillis());
                output.writeLong(RANDOM.nextLong());
                Types.BYTE_ARRAY_PRIMITIVE.write(output, new byte[0]);
                Types.BOOLEAN.write(output, false);
            } else {
                PacketTypes.writeString(output, message);
            }
            channel.writeAndFlush(output).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        } catch (Exception error) {
            output.release();
            System.err.println("[PikaStats] Could not send chat: " + error.getMessage());
        }
    }

    /** Characters a vanilla server kicks for: control characters, DEL and the section sign. */
    static String stripUnsafe(String message) {
        StringBuilder output = new StringBuilder(message.length());
        message.codePoints().filter(ChatCharset::isSafe).forEach(output::appendCodePoint);
        return output.toString();
    }

    private void writeSignedChat(ByteBuf output, String message, ProtocolVersion version) throws Exception {
        UserConnection user = this.connection.getUserConnection();
        if (user == null || !user.has(ChatSession1_19_3.class)) {
            // Without a chat session ViaProxy still accepts unsigned messages for old servers.
            PacketTypes.writeString(output, message);
            output.writeLong(System.currentTimeMillis());
            output.writeLong(RANDOM.nextLong());
            Types.OPTIONAL_SIGNATURE_BYTES.write(output, null);
            PacketTypes.writeVarInt(output, 0);
            Types.ACKNOWLEDGED_BIT_SET.write(output, new BitSet(20));
            if (version.newerThanOrEqualTo(ProtocolVersion.v1_21_5)) {
                output.writeByte(0);
            }
            return;
        }
        long timestamp = System.currentTimeMillis();
        long salt = RANDOM.nextLong();
        byte[] signature = user.get(ChatSession1_19_3.class)
                .signChatMessage(new MessageMetadata(null, timestamp, salt), message, new PlayerMessageSignature[0]);
        PacketTypes.writeString(output, message);
        output.writeLong(timestamp);
        output.writeLong(salt);
        Types.OPTIONAL_SIGNATURE_BYTES.write(output, signature);
        PacketTypes.writeVarInt(output, 0);
        Types.ACKNOWLEDGED_BIT_SET.write(output, new BitSet(20));
        if (version.newerThanOrEqualTo(ProtocolVersion.v1_21_5)) {
            output.writeByte(0);
        }
    }

    private void writeLegacySignedChat(ByteBuf output, String message) throws Exception {
        UserConnection user = this.connection.getUserConnection();
        if (user == null || !user.has(ChatSession1_19_1.class)) {
            throw new IllegalStateException("No signed chat session is available");
        }
        long timestamp = System.currentTimeMillis();
        long salt = RANDOM.nextLong();
        byte[] signature = user.get(ChatSession1_19_1.class).signChatMessage(
                new MessageMetadata(user.getProtocolInfo().getUuid(), timestamp, salt),
                new DecoratableMessage(message), new PlayerMessageSignature[0]);
        PacketTypes.writeString(output, message);
        output.writeLong(timestamp);
        output.writeLong(salt);
        Types.OPTIONAL_SIGNATURE_BYTES.write(output, signature);
        PacketTypes.writeVarInt(output, 0);
        Types.ACKNOWLEDGED_BIT_SET.write(output, new BitSet(20));
    }
}

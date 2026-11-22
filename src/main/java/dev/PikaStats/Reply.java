package dev.PikaStats;

import java.util.ArrayList;
import java.util.List;

/** Where a command answer goes, and how it is rendered for that place. */
final class Reply {
    enum Channel {
        /** Whatever chat the player is in, which is known to be party chat: colors work. */
        CURRENT_PARTY("", true),
        /** Whatever chat the player is in, which is public/global chat: no colors. */
        PUBLIC("", false),
        /** Party chat explicitly. */
        PARTY("/p c ", true),
        /** Global chat while the player is in party-chat mode ("!" stays visible on PikaNetwork). */
        GLOBAL("!", false),
        /** Only the player sees it. */
        PRIVATE("", true);

        private final String prefix;
        private final boolean colors;

        Channel(String prefix, boolean colors) {
            this.prefix = prefix;
            this.colors = colors;
        }

        String prefix() {
            return this.prefix;
        }
    }

    private static final ChatCharset UNRESTRICTED = unrestricted();

    private final ChatIO io;
    private final Services services;
    private final Channel channel;

    Reply(ChatIO io, Services services, Channel channel) {
        this.io = io;
        this.services = services;
        this.channel = channel;
    }

    Channel channel() {
        return this.channel;
    }

    Reply privately() {
        return this.channel == Channel.PRIVATE ? this : new Reply(this.io, this.services, Channel.PRIVATE);
    }

    void send(ChatLine line) {
        send(List.of(line));
    }

    /**
     * Sends every line. Lines longer than the chat limit are split into several messages; the chat
     * queue keeps at least 500 ms between them.
     */
    void send(List<ChatLine> lines) {
        if (this.channel == Channel.PRIVATE) {
            for (ChatLine line : lines) {
                this.io.sendClient(line.renderUnlimited(UNRESTRICTED, this.services.config().colorMode()));
            }
            return;
        }
        int budget = maxLength() - this.channel.prefix().length();
        ChatFormat.ColorMode mode = colorMode();
        List<String> messages = new ArrayList<>();
        for (ChatLine line : lines) {
            // Stats lines keep their colors and lose their least important stats if too long;
            // free text (punishment reasons, help) is split over several messages instead.
            List<String> parts = line.isSplittable()
                    ? line.renderSplit(this.services.charset(), mode, budget)
                    : List.of(line.renderColorsFirst(this.services.charset(), mode, budget));
            for (String part : parts) {
                if (!part.isBlank()) {
                    messages.add(this.channel.prefix() + part);
                }
            }
        }
        this.io.sendServer(messages);
    }

    /** Sends already-formatted raw text (used by the gradient commands). */
    void sendRaw(String formatted) {
        if (this.channel == Channel.PRIVATE) {
            this.io.sendClient(formatted);
        } else {
            this.io.sendServer(this.channel.prefix() + formatted);
        }
    }

    /** Party chat renders color codes; public/global chat shows them as text (measured on PikaNetwork). */
    ChatFormat.ColorMode colorMode() {
        PluginConfig config = this.services.config();
        return this.channel.colors ? config.colorMode() : config.publicColorMode();
    }

    int budget() {
        return this.channel == Channel.PRIVATE ? 4096 : maxLength() - this.channel.prefix().length();
    }

    /** The configured limit, but never more than the protocol between ViaProxy and the server can carry. */
    private int maxLength() {
        return Math.min(this.services.config().chatMaxLength(), this.io.protocolChatLimit());
    }

    private static ChatCharset unrestricted() {
        ChatCharset charset = new ChatCharset();
        StringBuilder all = new StringBuilder();
        for (int codePoint = 0xA0; codePoint < 0x30000; codePoint++) {
            if (ChatCharset.isSafe(codePoint)) {
                all.appendCodePoint(codePoint);
            }
        }
        charset.allowAll(all.toString());
        return charset;
    }
}

package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonParser;
import com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentSerializer;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;

import java.util.Arrays;
import java.util.regex.Pattern;

final class PikaStatsChatParser {
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)§[0-9A-FK-ORX]");

    record ParseResult(boolean detected, StatsRequest request) {
    }

    enum PunishmentsMode {
        DEFAULT,
        REASON,
        COUNT
    }

    record PunishmentsParseResult(boolean detected, String username, PunishmentsMode mode) {
    }

    private PikaStatsChatParser() {
    }

    static ParseResult parseBroadcast(String broadcastText) {
        if (broadcastText == null || broadcastText.isBlank()) {
            return new ParseResult(false, null);
        }

        String plainText = COLOR_CODES.matcher(broadcastText).replaceAll("").strip();
        int commandIndex = indexOfStatsCommand(plainText);
        if (commandIndex < 0) {
            return new ParseResult(false, null);
        }

        // Chat formats can prepend channel, party, rank, or other labels. Search the whole
        // message, then parse the command, optional period/mode pair, and optional stat selector.
        String[] tokens = plainText.substring(commandIndex).strip().split("\\s+");
        if (tokens.length < 2) {
            return new ParseResult(true, null);
        }
        int maxCommandTokens = Math.min(tokens.length, 5);
        for (int tokenCount = maxCommandTokens; tokenCount >= 2; tokenCount--) {
            if (tokenCount == 2 && tokens.length > 2) {
                // Do not turn a malformed extra argument into a valid default request.
                continue;
            }
            String command = String.join(" ", Arrays.copyOf(tokens, tokenCount));
            StatsRequest request = StatsRequest.parse(command);
            if (request != null) {
                return new ParseResult(true, request);
            }
        }
        return new ParseResult(true, null);
    }

    static PunishmentsParseResult parsePunishmentsBroadcast(String broadcastText) {
        if (broadcastText == null || broadcastText.isBlank()) {
            return new PunishmentsParseResult(false, null, null);
        }

        String plainText = COLOR_CODES.matcher(broadcastText).replaceAll("").strip();
        int commandIndex = indexOfCommand(plainText, "-punishments");
        if (commandIndex < 0) {
            return new PunishmentsParseResult(false, null, null);
        }

        String[] tokens = plainText.substring(commandIndex).strip().split("\\s+");
        if ((tokens.length != 2 && tokens.length != 3)
                || !tokens[1].matches("[A-Za-z0-9_]{1,16}")) {
            return new PunishmentsParseResult(true, null, null);
        }
        PunishmentsMode mode = PunishmentsMode.DEFAULT;
        if (tokens.length == 3) {
            if (tokens[2].equalsIgnoreCase("reason")) {
                mode = PunishmentsMode.REASON;
            } else if (tokens[2].equalsIgnoreCase("count")) {
                mode = PunishmentsMode.COUNT;
            } else {
                return new PunishmentsParseResult(true, null, null);
            }
        }
        return new PunishmentsParseResult(true, tokens[1], mode);
    }

    static String componentToPlainText(String componentJson) {
        try {
            JsonElement element = JsonParser.parseString(componentJson);
            return TextComponentSerializer.V1_8.forkIfDefault().deserialize(element.toString()).asUnformattedString();
        } catch (RuntimeException exception) {
            // Some servers send a plain JSON string rather than a component object.
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
            if (text != null) {
                appendTagText(text, output);
            }
            Tag extra = compound.get("extra");
            if (extra instanceof ListTag<?> list) {
                for (Tag child : list) {
                    appendTagText(child, output);
                }
            }
            Tag with = compound.get("with");
            if (with instanceof ListTag<?> list) {
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

    private static int indexOfStatsCommand(String text) {
        for (int index = 0; index <= text.length() - 6; index++) {
            if (text.regionMatches(true, index, "-stats", 0, 6)) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfCommand(String text, String command) {
        for (int index = 0; index <= text.length() - command.length(); index++) {
            if (!text.regionMatches(true, index, command, 0, command.length())) {
                continue;
            }
            boolean startsAtBoundary = index == 0 || Character.isWhitespace(text.charAt(index - 1));
            int afterCommand = index + command.length();
            boolean endsAtBoundary = afterCommand == text.length() || Character.isWhitespace(text.charAt(afterCommand));
            if (startsAtBoundary && endsAtBoundary) {
                return index;
            }
        }
        return -1;
    }
}

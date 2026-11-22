package dev.PikaStats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Characters the server accepts in chat. Printable ASCII is always allowed; anything else must be
 * listed in {@code plugins/PikaStats/allowed-chars.txt} or in the
 * {@code extra-allowed-chars} setting. Disallowed characters are transliterated or dropped so a
 * reply is never rejected or gets the player kicked.
 */
final class ChatCharset {
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final BitSet allowed = new BitSet(0x110000);
    private int extraCount;

    ChatCharset() {
        this.allowed.set(0x20, 0x7F);
    }

    static ChatCharset load(List<Path> files, String extraCharacters) {
        ChatCharset charset = new ChatCharset();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.startsWith("#")) {
                        continue;
                    }
                    charset.allowAll(line);
                }
                System.out.println("[PikaStats] Loaded allowed chat characters from " + file);
            } catch (IOException error) {
                System.err.println("[PikaStats] Could not read " + file + ": " + error.getMessage());
            }
        }
        if (extraCharacters != null) {
            charset.allowAll(extraCharacters);
        }
        return charset;
    }

    void allowAll(String characters) {
        characters.codePoints().forEach(codePoint -> {
            if (isSafe(codePoint) && !Character.isWhitespace(codePoint) && !this.allowed.get(codePoint)) {
                this.allowed.set(codePoint);
                this.extraCount++;
            }
        });
    }

    int extraCount() {
        return this.extraCount;
    }

    /** Vanilla servers kick for control characters, DEL and the section sign. */
    static boolean isSafe(int codePoint) {
        return codePoint >= 0x20 && codePoint != 0x7F && codePoint != 0xA7
                && !(codePoint >= 0x80 && codePoint < 0xA0)
                && !(codePoint >= 0xD800 && codePoint <= 0xDFFF)
                && codePoint != 0xFEFF && Character.isValidCodePoint(codePoint);
    }

    boolean allows(int codePoint) {
        return codePoint >= 0 && codePoint < 0x110000 && this.allowed.get(codePoint);
    }

    boolean allowsAll(String text) {
        return text.codePoints().allMatch(this::allows);
    }

    /** {@code preferred} when every character is allowed, otherwise {@code fallback}. */
    String glyph(String preferred, String fallback) {
        return preferred != null && allowsAll(preferred) ? preferred : fallback;
    }

    /** Removes or transliterates characters the server would reject. */
    String sanitize(String text) {
        if (text == null || text.isEmpty() || allowsAll(text)) {
            return text == null ? "" : text;
        }
        StringBuilder output = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (allows(codePoint)) {
                output.appendCodePoint(codePoint);
                return;
            }
            String replacement = transliterate(codePoint);
            if (replacement != null) {
                output.append(replacement);
            }
        });
        return output.toString();
    }

    private String transliterate(int codePoint) {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            return " ";
        }
        String decomposed = COMBINING_MARKS.matcher(
                Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKD)).replaceAll("");
        if (!decomposed.isEmpty() && allowsAll(decomposed)) {
            return decomposed;
        }
        return switch (codePoint) {
            case 0x2018, 0x2019, 0x201B, 0x2032 -> "'";
            case 0x201C, 0x201D, 0x201E, 0x2033 -> "\"";
            case 0x2013, 0x2014, 0x2015, 0x2212 -> "-";
            case 0x2026 -> "...";
            case 0x00BB, 0x203A, 0x2192, 0x279C, 0x27A4 -> ">";
            case 0x00AB, 0x2039, 0x2190 -> "<";
            case 0x2022, 0x00B7, 0x2219, 0x22C5, 0x25CF, 0x25AA -> "*";
            case 0x2502, 0x2503, 0x2759, 0x23D0, 0x00A6, 0x2223 -> "|";
            case 0x00D7, 0x2715 -> "x";
            case 0x00DF -> "ss";
            case 0x00E6 -> "ae";
            case 0x00C6 -> "AE";
            case 0x0153 -> "oe";
            case 0x00F8 -> "o";
            case 0x00D8 -> "O";
            default -> null;
        };
    }
}

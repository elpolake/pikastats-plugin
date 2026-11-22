package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Color-code helpers for the server's {@code &#rrggbb} and {@code &a} chat formatting. */
final class ChatFormat {
    private static final Pattern CODES = Pattern.compile("(?i)[&§]#[0-9a-f]{6}|[&§]x(?:[&§][0-9a-f]){6}|[&§][0-9a-fk-or]");
    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
    private static final int[] LEGACY_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String[] LEGACY_NAMES = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private static final String LEGACY_CHARS = "0123456789abcdef";

    private ChatFormat() {
    }

    enum ColorMode {
        /** {@code &#rrggbb} codes (8 characters each). */
        HEX,
        /** Nearest of the 16 classic colors, {@code &a} (2 characters each). */
        LEGACY,
        /** No color codes at all. */
        NONE;

        static ColorMode parse(String value, ColorMode fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    static String stripCodes(String text) {
        return text == null ? "" : CODES.matcher(text).replaceAll("");
    }

    static boolean isHex(String value) {
        return value != null && HEX.matcher(value).matches();
    }

    /** Normalises {@code FFAA00}, {@code #ffaa00} or {@code &#ffaa00} to {@code #ffaa00}. */
    static String normalizeHex(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("&")) {
            trimmed = trimmed.substring(1);
        }
        if (!isHex(trimmed)) {
            return null;
        }
        return "#" + (trimmed.startsWith("#") ? trimmed.substring(1) : trimmed).toLowerCase(Locale.ROOT);
    }

    static int rgb(String hex) {
        String normalized = normalizeHex(hex);
        return normalized == null ? 0xFFFFFF : Integer.parseInt(normalized.substring(1), 16);
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    /** The chat code for a color in the given mode; empty for {@link ColorMode#NONE}. */
    static String code(String hex, ColorMode mode) {
        return switch (mode) {
            case HEX -> "&" + normalizeHex(hex);
            case LEGACY -> "&" + LEGACY_CHARS.charAt(nearestLegacy(rgb(hex)));
            case NONE -> "";
        };
    }

    static int codeLength(ColorMode mode) {
        return switch (mode) {
            case HEX -> 8;
            case LEGACY -> 2;
            case NONE -> 0;
        };
    }

    static int nearestLegacy(int rgb) {
        int best = 15;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 0; index < LEGACY_RGB.length; index++) {
            double distance = colorDistance(rgb, LEGACY_RGB[index]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    private static double colorDistance(int first, int second) {
        int r1 = first >> 16 & 0xFF;
        int g1 = first >> 8 & 0xFF;
        int b1 = first & 0xFF;
        int r2 = second >> 16 & 0xFF;
        int g2 = second >> 8 & 0xFF;
        int b2 = second & 0xFF;
        double meanRed = (r1 + r2) / 2.0;
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        // "Redmean" approximation of perceived color difference.
        return (2 + meanRed / 256) * dr * dr + 4 * dg * dg + (2 + (255 - meanRed) / 256) * db * db;
    }

    static int lerp(int from, int to, double t) {
        int r = (int) Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int g = (int) Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return r << 16 | g << 8 | b;
    }

    /** Color at position {@code t} (0..1) along a multi-stop gradient. */
    static int gradientAt(int[] stops, double t) {
        if (stops.length == 1) {
            return stops[0];
        }
        double scaled = Math.max(0.0, Math.min(1.0, t)) * (stops.length - 1);
        int index = Math.min(stops.length - 2, (int) Math.floor(scaled));
        return lerp(stops[index], stops[index + 1], scaled - index);
    }

    /**
     * Colors {@code text} with a gradient that fits in {@code budget} characters. Characters are
     * grouped into the most color steps the budget allows; spaces never get their own code.
     */
    static String gradient(String text, int[] stops, ColorMode mode, int budget) {
        int visible = 0;
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                visible++;
            }
        }
        if (mode == ColorMode.NONE || visible == 0) {
            return text.length() <= budget ? text : text.substring(0, Math.max(0, budget));
        }
        int codeLength = codeLength(mode);
        int maxSteps = Math.max(1, (budget - text.length()) / codeLength);
        for (int steps = Math.min(visible, maxSteps); steps >= 1; steps--) {
            String rendered = renderGradient(text, stops, mode, steps, visible);
            if (rendered.length() <= budget) {
                return rendered;
            }
        }
        String fallback = code(hex(stops[0]), mode) + text;
        return fallback.length() <= budget ? fallback : fallback.substring(0, budget);
    }

    private static String renderGradient(String text, int[] stops, ColorMode mode, int steps, int visible) {
        StringBuilder output = new StringBuilder();
        String lastCode = null;
        int seen = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isWhitespace(character)) {
                output.append(character);
                continue;
            }
            int group = (int) Math.min(steps - 1, (long) seen * steps / visible);
            double t = steps == 1 ? 0.0 : (double) group / (steps - 1);
            String code = code(hex(gradientAt(stops, t)), mode);
            if (!code.equals(lastCode)) {
                output.append(code);
                lastCode = code;
            }
            output.append(character);
            if (Character.isHighSurrogate(character) && index + 1 < text.length()) {
                output.append(text.charAt(++index));
            }
            seen++;
        }
        return output.toString();
    }

    /**
     * Converts text with {@code &}/{@code §} legacy and {@code &#rrggbb} codes into a JSON chat
     * component for a client. Hex colors become the nearest classic color on pre-1.16 clients.
     */
    static JsonObject toComponent(String formatted, boolean hexSupported) {
        List<JsonObject> parts = new ArrayList<>();
        String color = null;
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        boolean obfuscated = false;
        StringBuilder text = new StringBuilder();
        int index = 0;
        while (index < formatted.length()) {
            char character = formatted.charAt(index);
            if ((character == '&' || character == '§') && index + 1 < formatted.length()) {
                char next = Character.toLowerCase(formatted.charAt(index + 1));
                String newColor = null;
                int consumed = 0;
                boolean reset = false;
                char format = 0;
                if (next == '#' && index + 8 <= formatted.length() && isHex(formatted.substring(index + 2, index + 8))) {
                    newColor = "#" + formatted.substring(index + 2, index + 8).toLowerCase(Locale.ROOT);
                    consumed = 8;
                } else if (LEGACY_CHARS.indexOf(next) >= 0) {
                    newColor = LEGACY_NAMES[LEGACY_CHARS.indexOf(next)];
                    consumed = 2;
                } else if ("klmno".indexOf(next) >= 0) {
                    format = next;
                    consumed = 2;
                } else if (next == 'r') {
                    reset = true;
                    consumed = 2;
                }
                if (consumed > 0) {
                    if (text.length() > 0) {
                        parts.add(part(text.toString(), color, bold, italic, underlined, strikethrough, obfuscated,
                                hexSupported));
                        text.setLength(0);
                    }
                    if (newColor != null || reset) {
                        color = reset ? null : newColor;
                        bold = italic = underlined = strikethrough = obfuscated = false;
                    } else {
                        switch (format) {
                            case 'l' -> bold = true;
                            case 'o' -> italic = true;
                            case 'n' -> underlined = true;
                            case 'm' -> strikethrough = true;
                            case 'k' -> obfuscated = true;
                            default -> {
                            }
                        }
                    }
                    index += consumed;
                    continue;
                }
            }
            text.append(character);
            index++;
        }
        if (text.length() > 0) {
            parts.add(part(text.toString(), color, bold, italic, underlined, strikethrough, obfuscated, hexSupported));
        }
        JsonObject root = new JsonObject();
        root.addProperty("text", "");
        JsonArray extra = new JsonArray();
        parts.forEach(extra::add);
        if (extra.isEmpty()) {
            return root;
        }
        root.add("extra", extra);
        return root;
    }

    private static JsonObject part(String text, String color, boolean bold, boolean italic, boolean underlined,
                                   boolean strikethrough, boolean obfuscated, boolean hexSupported) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        if (color != null) {
            if (color.startsWith("#") && !hexSupported) {
                color = LEGACY_NAMES[nearestLegacy(rgb(color))];
            }
            part.addProperty("color", color);
        }
        // Explicit false values stop formats leaking from parent components on old clients.
        part.addProperty("bold", bold);
        part.addProperty("italic", italic);
        if (underlined) {
            part.addProperty("underlined", true);
        }
        if (strikethrough) {
            part.addProperty("strikethrough", true);
        }
        if (obfuscated) {
            part.addProperty("obfuscated", true);
        }
        return part;
    }
}

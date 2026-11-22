package dev.PikaStats;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Settings in {@code plugins/PikaStats/config.properties}. Values from the 1.x
 * {@code overlay.properties} (api-key, websocket-port, fallback-mode) are migrated automatically.
 */
final class PluginConfig {
    private static final String FILE_NAME = "config.properties";
    private static final String LEGACY_FILE_NAME = "overlay.properties";

    /** Key, default value and comment, in the order they are written. */
    private static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>();

    static {
        option("api-key", "", "PikaNetwork API key. Get one in game with /apikey. Never share it.");
        option("api-keys", "", "More API keys, comma separated (one per account). Each adds 120 requests/minute."
                + " Easiest to manage in ViaProxy's PikaStats tab.");
        option("chat-style", ChatTheme.DEFAULT_ID, "Chat color style. Type -styles in game to preview them.");
        option("color-mode", "hex", "Colors in party chat: hex (&#rrggbb), legacy (&a) or none.");
        option("public-color-mode", "none", "Colors in public/global chat. PikaNetwork shows codes there as plain text"
                + " (measured), so the default is none.");
        option("chat-max-length", "128", "Longest chat message the server accepts, including commands and codes.");
        option("chat-delay-ms", "500", "Pause between chat messages the plugin sends (minimum 500, anti-spam).");
        option("party-commands", "true", "Party members may use -stats/-sw/-profile/-compare/-punishments in party chat"
                + " (answers go to party chat). Toggle in game with -partycommands on|off.");
        option("global-commands", "false", "Everyone may use those commands; answers go to global chat with a ! prefix."
                + " Toggle in game with -global on|off.");
        option("party-allowed-commands", CommandPermissions.ALL, "Commands party members may use (when party-commands"
                + " is on): stats, sw, compare, profile, punishments, comma separated. Easiest to edit in the overlay (gear).");
        option("global-allowed-commands", CommandPermissions.ALL, "Commands players in global chat may use (when"
                + " global-commands is on). Same names as above.");
        option("others-cooldown-seconds", "4", "Minimum seconds between answers to the same command from others.");
        option("extra-allowed-chars", "", "Extra characters the server allows in chat (besides ASCII).");
        option("websocket-port", "8765", "Local port for the desktop overlay (1024-65535).");
        option("fallback-mode", "QUADS", "BedWars mode used when the current mode cannot be detected.");
        option("overlay-profiles", "true", "Also load network level and rank for players in the overlay.");
    }

    private static void option(String key, String defaultValue, String comment) {
        DEFAULTS.put(key, new String[]{defaultValue, comment});
    }

    private final File file;
    private final Properties properties = new Properties();

    private PluginConfig(File file) {
        this.file = file;
    }

    static PluginConfig load(File dataFolder) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            System.err.println("[PikaStats] Could not create plugin data folder: " + dataFolder);
        }
        PluginConfig config = new PluginConfig(new File(dataFolder, FILE_NAME));
        boolean changed = false;
        if (config.file.isFile()) {
            config.read(config.file);
        } else {
            File legacy = new File(dataFolder, LEGACY_FILE_NAME);
            if (legacy.isFile()) {
                config.read(legacy);
                System.out.println("[PikaStats] Migrated settings from overlay.properties to config.properties");
            }
            changed = true;
        }
        // 2.0 -> 2.1: respond-to-others became party-commands; public-replies is gone (own replies are private).
        String legacyOthers = (String) config.properties.remove("respond-to-others");
        if (legacyOthers != null && !config.properties.containsKey("party-commands")) {
            config.properties.setProperty("party-commands", legacyOthers.trim());
        }
        if (config.properties.remove("public-replies") != null || legacyOthers != null) {
            changed = true;
        }
        for (Map.Entry<String, String[]> entry : DEFAULTS.entrySet()) {
            if (!config.properties.containsKey(entry.getKey())) {
                config.properties.setProperty(entry.getKey(), entry.getValue()[0]);
                changed = true;
            }
        }
        if (changed) {
            config.save();
        }
        return config;
    }

    private void read(File source) {
        try (java.io.Reader input = Files.newBufferedReader(source.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            this.properties.load(input);
        } catch (IOException error) {
            System.err.println("[PikaStats] Could not read " + source.getName() + ": " + error.getMessage());
        }
    }

    synchronized void save() {
        StringBuilder output = new StringBuilder("# PikaStats ").append(PikaStatsPlugin.VERSION)
                .append(" settings. Restart ViaProxy or type -ps reload after editing.\n");
        for (Map.Entry<String, String[]> entry : DEFAULTS.entrySet()) {
            output.append('\n').append("# ").append(entry.getValue()[1]).append('\n');
            output.append(escape(entry.getKey())).append('=')
                    .append(escape(this.properties.getProperty(entry.getKey(), entry.getValue()[0]))).append('\n');
        }
        for (String key : this.properties.stringPropertyNames()) {
            if (!DEFAULTS.containsKey(key)) {
                output.append('\n').append(escape(key)).append('=').append(escape(this.properties.getProperty(key)))
                        .append('\n');
            }
        }
        try {
            Path temporary = this.file.toPath().resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temporary, output.toString());
            Files.move(temporary, this.file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            System.err.println("[PikaStats] Could not save config.properties: " + error.getMessage());
        }
    }

    /** Escapes like {@link Properties#store} but keeps non-ASCII characters readable (UTF-8 file). */
    private static String escape(String value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                case '=', ':', '#', '!' -> output.append('\\').append(character);
                case ' ' -> output.append(index == 0 ? "\\ " : " ");
                default -> output.append(character);
            }
        }
        return output.toString();
    }

    synchronized void set(String key, String value) {
        this.properties.setProperty(key, value);
        save();
    }

    /** Sets several values and saves once. */
    synchronized void setAll(Map<String, String> values) {
        values.forEach(this.properties::setProperty);
        save();
    }

    synchronized String string(String key) {
        String fallback = DEFAULTS.containsKey(key) ? DEFAULTS.get(key)[0] : "";
        return this.properties.getProperty(key, fallback).trim();
    }

    int integer(String key, int min, int max) {
        int fallback = Integer.parseInt(DEFAULTS.get(key)[0]);
        try {
            int value = Integer.parseInt(string(key));
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    boolean bool(String key) {
        String value = string(key);
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equals("1");
    }

    /** Every configured key: PIKA_STATS_API_KEY (comma separated allowed), api-key, then api-keys. */
    java.util.List<String> apiKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        String environment = System.getenv("PIKA_STATS_API_KEY");
        for (String source : new String[]{environment == null ? "" : environment, string("api-key"), string("api-keys")}) {
            for (String key : source.split("[,;\s]+")) {
                if (!key.isBlank()) {
                    keys.add(key.trim());
                }
            }
        }
        return java.util.List.copyOf(keys);
    }

    /** Stores the full key list in api-keys (api-key is cleared so nothing is listed twice). */
    synchronized void setApiKeys(java.util.List<String> keys) {
        this.properties.setProperty("api-keys", String.join(",", keys));
        this.properties.setProperty("api-key", "");
        save();
    }

    int websocketPort() {
        return integer("websocket-port", 1024, 65535);
    }

    BedWarsMode fallbackMode() {
        BedWarsMode mode = BedWarsMode.fromName(string("fallback-mode"));
        return mode == null || mode == BedWarsMode.ALL_MODES ? BedWarsMode.QUADS : mode;
    }

    ChatTheme chatTheme() {
        return ChatTheme.get(string("chat-style"));
    }

    ChatFormat.ColorMode colorMode() {
        return ChatFormat.ColorMode.parse(string("color-mode"), ChatFormat.ColorMode.HEX);
    }

    ChatFormat.ColorMode publicColorMode() {
        return ChatFormat.ColorMode.parse(string("public-color-mode"), ChatFormat.ColorMode.NONE);
    }

    int chatMaxLength() {
        return integer("chat-max-length", 32, 256);
    }

    int chatDelayMillis() {
        return Math.max(500, integer("chat-delay-ms", 500, 5000));
    }

    boolean partyCommands() {
        return bool("party-commands");
    }

    boolean globalCommands() {
        return bool("global-commands");
    }

    int othersCooldownSeconds() {
        return integer("others-cooldown-seconds", 0, 600);
    }

    String extraAllowedChars() {
        return string("extra-allowed-chars");
    }

    boolean overlayProfiles() {
        return bool("overlay-profiles");
    }

    File dataFolder() {
        return this.file.getParentFile();
    }
}

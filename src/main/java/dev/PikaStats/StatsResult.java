package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Parsed leaderboard response for one player, game, period and mode. */
final class StatsResult {
    private final String username;
    private final GameType game;
    private final Map<Stat, Long> values;
    private final Map<Stat, Long> places;

    StatsResult(String username, GameType game, Map<Stat, Long> values, Map<Stat, Long> places) {
        this.username = username;
        this.game = game;
        this.values = values.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(values));
        this.places = places.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(places));
    }

    static StatsResult parse(String requestedUsername, GameType game, String responseBody) {
        JsonElement root = JsonParser.parseString(responseBody);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("The stats response was not a JSON object");
        }
        JsonObject json = root.getAsJsonObject();
        Map<Stat, Long> values = new EnumMap<>(Stat.class);
        Map<Stat, Long> places = new EnumMap<>(Stat.class);
        String canonical = requestedUsername;
        for (Stat stat : Stat.values()) {
            JsonObject entry = firstEntry(json, stat.apiName());
            if (entry == null) {
                continue;
            }
            long value = readLong(entry.get("value"));
            long place = readLong(entry.get("place"));
            values.put(stat, value);
            if (place > 0) {
                places.put(stat, place);
            }
            JsonElement id = entry.get("id");
            if (id != null && id.isJsonPrimitive() && PartyStore.isValidUsername(id.getAsString())) {
                canonical = id.getAsString();
            }
        }
        return new StatsResult(canonical, game, values, places);
    }

    private static JsonObject firstEntry(JsonObject json, String statName) {
        JsonElement stat = json.get(statName);
        if (stat == null || !stat.isJsonObject()) {
            return null;
        }
        JsonElement entries = stat.getAsJsonObject().get("entries");
        if (entries == null || !entries.isJsonArray() || entries.getAsJsonArray().isEmpty()) {
            return null;
        }
        JsonElement first = entries.getAsJsonArray().get(0);
        return first != null && first.isJsonObject() ? first.getAsJsonObject() : null;
    }

    private static long readLong(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.getAsString().trim());
        } catch (NumberFormatException | UnsupportedOperationException ignored) {
            try {
                return (long) Double.parseDouble(value.getAsString().trim());
            } catch (NumberFormatException ignoredAgain) {
                return 0L;
            }
        }
    }

    String username() {
        return this.username;
    }

    GameType game() {
        return this.game;
    }

    long get(Stat stat) {
        return this.values.getOrDefault(stat, 0L);
    }

    /** Leaderboard position, or 0 when the player is unranked for this stat. */
    long place(Stat stat) {
        return this.places.getOrDefault(stat, 0L);
    }

    boolean isEmpty() {
        return this.values.values().stream().allMatch(value -> value == 0L);
    }

    double fkdr() {
        return ratio(get(Stat.FINAL_KILLS), get(Stat.FINAL_DEATHS));
    }

    double wlr() {
        return ratio(get(Stat.WINS), get(Stat.LOSSES));
    }

    double kdr() {
        return ratio(get(Stat.KILLS), get(Stat.DEATHS));
    }

    /** Beds broken per loss. */
    double bblr() {
        return ratio(get(Stat.BEDS), get(Stat.LOSSES));
    }

    double finalsPerGame() {
        long games = get(Stat.GAMES);
        return games <= 0 ? 0.0 : (double) get(Stat.FINAL_KILLS) / games;
    }

    double winRate() {
        long games = get(Stat.GAMES);
        if (games <= 0) {
            long decided = get(Stat.WINS) + get(Stat.LOSSES);
            return decided <= 0 ? 0.0 : 100.0 * get(Stat.WINS) / decided;
        }
        return Math.min(100.0, 100.0 * get(Stat.WINS) / games);
    }

    double bowAccuracy() {
        long shot = get(Stat.ARROWS_SHOT);
        return shot <= 0 ? 0.0 : Math.min(100.0, 100.0 * get(Stat.ARROWS_HIT) / shot);
    }

    /** One number used to rank how dangerous a player is; mirrors the overlay's default weights. */
    double threatScore() {
        return Math.min(fkdr(), 12.0) + Math.min(wlr(), 8.0) * 0.8 + Math.min(kdr(), 6.0) * 0.4;
    }

    /** Ratio with no decimals lost when the denominator is zero (treated as 1, like most stat sites). */
    static double ratio(long numerator, long denominator) {
        if (numerator <= 0) {
            return 0.0;
        }
        return (double) numerator / Math.max(1L, denominator);
    }

    static String formatRatio(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String formatCount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** Short count form for tight chat lines: 1234 -> 1.2k, 1500000 -> 1.5m. */
    static String formatCompact(long value) {
        if (value < 10_000) {
            return formatCount(value);
        }
        if (value < 1_000_000) {
            return trimZero(String.format(Locale.ROOT, "%.1f", value / 1_000.0)) + "k";
        }
        return trimZero(String.format(Locale.ROOT, "%.2f", value / 1_000_000.0)) + "m";
    }

    static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String trimZero(String value) {
        if (value.contains(".")) {
            value = value.replaceAll("0+$", "");
            if (value.endsWith(".")) {
                value = value.substring(0, value.length() - 1);
            }
        }
        return value;
    }
}

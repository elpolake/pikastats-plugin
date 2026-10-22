package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;

final class PikaStatsParser {
    private PikaStatsParser() {
    }

    static StatsResult parse(String canonicalUsername, String responseBody) {
        JsonElement root = JsonParser.parseString(responseBody);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("The stats response was not a JSON object");
        }

        JsonObject stats = root.getAsJsonObject();
        return new StatsResult(
                canonicalUsername,
                getStat(stats, "Wins"),
                getStat(stats, "Losses"),
                getStat(stats, "Final kills"),
                getStat(stats, "Final deaths"),
                getStat(stats, "Kills"),
                getStat(stats, "Deaths"),
                getStat(stats, "Games played"),
                getStat(stats, "Beds destroyed"),
                getStat(stats, "Melee kills"),
                getStat(stats, "Bow kills"),
                getStat(stats, "Void kills"),
                getStat(stats, "Arrows shot"),
                getStat(stats, "Arrows hit"),
                getStat(stats, "Highest winstreak reached"));
    }

    /** Reads only the player's entries[0].value. Leaderboard metadata is intentionally ignored. */
    static long getStat(JsonObject json, String statName) {
        if (json == null || statName == null || !json.has(statName)) {
            return 0L;
        }

        JsonElement statElement = json.get(statName);
        if (statElement == null || !statElement.isJsonObject()) {
            return 0L;
        }
        JsonElement entriesElement = statElement.getAsJsonObject().get("entries");
        if (entriesElement == null || !entriesElement.isJsonArray() || entriesElement.getAsJsonArray().isEmpty()) {
            return 0L;
        }

        JsonElement firstEntry = entriesElement.getAsJsonArray().get(0);
        if (firstEntry == null || !firstEntry.isJsonObject()) {
            return 0L;
        }
        JsonElement value = firstEntry.getAsJsonObject().get("value");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.getAsString());
        } catch (NumberFormatException | UnsupportedOperationException ignored) {
            return 0L;
        }
    }
}

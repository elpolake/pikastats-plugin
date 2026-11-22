package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** The general PikaNetwork profile: network level, ranks, clan and last-seen time. */
record ProfileResult(
        String username,
        int level,
        double levelProgress,
        String levelTitle,
        List<String> ranks,
        String clanName,
        String clanTag,
        int clanLevel,
        int clanMembers,
        long lastSeenMillis,
        int friends,
        boolean discordVerified) {

    ProfileResult {
        ranks = List.copyOf(ranks);
    }

    static ProfileResult parse(String requestedUsername, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("The profile response was not a JSON object");
        }
        JsonObject json = root.getAsJsonObject();
        String username = string(json, "username", requestedUsername);
        if (!PartyStore.isValidUsername(username)) {
            username = requestedUsername;
        }

        int level = 0;
        double progress = 0.0;
        String title = "";
        JsonObject rank = object(json, "rank");
        if (rank != null) {
            level = (int) number(rank, "level");
            progress = number(rank, "percentage");
            title = ChatFormat.stripCodes(string(rank, "rankDisplay", ""))
                    .replace("[", "").replace("]", "").trim();
        }

        List<String> ranks = new ArrayList<>();
        JsonElement rankList = json.get("ranks");
        if (rankList != null && rankList.isJsonArray()) {
            for (JsonElement element : rankList.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    String display = ChatFormat.stripCodes(string(element.getAsJsonObject(), "displayName", ""));
                    if (!display.isBlank()) {
                        ranks.add(display.trim());
                    }
                }
            }
        }

        String clanName = "";
        String clanTag = "";
        int clanLevel = 0;
        int clanMembers = 0;
        JsonObject clan = object(json, "clan");
        if (clan != null) {
            clanName = ChatFormat.stripCodes(string(clan, "name", ""));
            clanTag = ChatFormat.stripCodes(string(clan, "tag", ""));
            JsonObject leveling = object(clan, "leveling");
            if (leveling != null) {
                clanLevel = (int) number(leveling, "level");
            }
            JsonElement members = clan.get("members");
            if (members != null && members.isJsonArray()) {
                clanMembers = members.getAsJsonArray().size();
            }
        }

        JsonElement friends = json.get("friends");
        int friendCount = friends instanceof JsonArray array ? array.size() : 0;
        long lastSeen = (long) number(json, "lastSeen");
        boolean discord = json.has("discord_verified") && json.get("discord_verified").isJsonPrimitive()
                && json.get("discord_verified").getAsBoolean();
        return new ProfileResult(username, level, progress, title, ranks, clanName, clanTag, clanLevel,
                clanMembers, lastSeen, friendCount, discord);
    }

    String primaryRank() {
        return this.ranks.isEmpty() ? "" : this.ranks.get(0);
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static double number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return 0.0;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }
}

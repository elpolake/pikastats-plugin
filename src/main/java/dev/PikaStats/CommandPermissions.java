package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which lookup commands other players may use in party chat and in global chat. You can always use
 * every command yourself. The overlay edits this with {@code command_settings_request} /
 * {@code command_settings_update} messages and gets {@code command_settings} back.
 */
final class CommandPermissions {
    /** A group of command aliases that is allowed or blocked together. */
    record Group(String id, String label, String usage, List<String> aliases) {
    }

    static final List<Group> GROUPS = List.of(
            new Group("stats", "BedWars stats", "-stats / -bw <player>", List.of("stats", "s", "bw", "bedwars")),
            new Group("sw", "SkyWars stats", "-sw <player>", List.of("sw", "skywars")),
            new Group("compare", "Compare", "-compare / -vs <p1> <p2>", List.of("compare", "vs")),
            new Group("profile", "Profile & level", "-profile / -level <player>", List.of("profile", "pf", "level", "lvl")),
            new Group("punishments", "Punishments", "-punishments / -bans <player>",
                    List.of("punishments", "pun", "bans", "history")));

    static final String PARTY_KEY = "party-allowed-commands";
    static final String GLOBAL_KEY = "global-allowed-commands";
    static final String ALL = "stats,sw,compare,profile,punishments";

    enum Scope { PARTY, GLOBAL }

    private final Services services;

    CommandPermissions(Services services) {
        this.services = services;
        services.websocket().addMessageHandler(this::onMessage);
    }

    /** The group id of a command alias, or null when it is not a lookup others may use. */
    static String groupOf(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (Group group : GROUPS) {
            if (group.aliases().contains(lower)) {
                return group.id();
            }
        }
        return null;
    }

    static Group group(String id) {
        for (Group group : GROUPS) {
            if (group.id().equals(id)) {
                return group;
            }
        }
        return null;
    }

    static Set<String> allowed(PluginConfig config, Scope scope) {
        String raw = config.string(scope == Scope.PARTY ? PARTY_KEY : GLOBAL_KEY).toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        if (raw.equals("all") || raw.equals("*")) {
            GROUPS.forEach(group -> result.add(group.id()));
            return result;
        }
        for (String token : raw.split("[,;\\s]+")) {
            String id = group(token) != null ? token : groupOf(token);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    static boolean isAllowed(PluginConfig config, Scope scope, String command) {
        String id = groupOf(command);
        return id != null && allowed(config, scope).contains(id);
    }

    static void setAllowed(PluginConfig config, Scope scope, Set<String> ids) {
        config.set(scope == Scope.PARTY ? PARTY_KEY : GLOBAL_KEY, join(ids));
    }

    /** Group ids in their fixed order, comma separated. */
    static String join(Set<String> ids) {
        List<String> ordered = new ArrayList<>();
        for (Group group : GROUPS) {
            if (ids.contains(group.id())) {
                ordered.add(group.id());
            }
        }
        return String.join(",", ordered);
    }

    /** Sends the current settings to every overlay (also after in-game changes). */
    void publish() {
        this.services.websocket().publish(stateMessage(this.services.config()), null);
    }

    static String stateMessage(PluginConfig config) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "command_settings");
        message.addProperty("partyEnabled", config.partyCommands());
        message.addProperty("globalEnabled", config.globalCommands());
        message.addProperty("cooldown", config.othersCooldownSeconds());
        message.add("party", toArray(allowed(config, Scope.PARTY)));
        message.add("global", toArray(allowed(config, Scope.GLOBAL)));
        JsonArray commands = new JsonArray();
        for (Group group : GROUPS) {
            JsonObject item = new JsonObject();
            item.addProperty("id", group.id());
            item.addProperty("label", group.label());
            item.addProperty("usage", group.usage());
            commands.add(item);
        }
        message.add("commands", commands);
        return message.toString();
    }

    private static JsonArray toArray(Set<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private void onMessage(String payload) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(payload);
        } catch (RuntimeException ignored) {
            return;
        }
        if (!parsed.isJsonObject()) {
            return;
        }
        JsonObject message = parsed.getAsJsonObject();
        String type = message.has("type") && message.get("type").isJsonPrimitive() ? message.get("type").getAsString() : "";
        if (type.equals("command_settings_request")) {
            publish();
        } else if (type.equals("command_settings_update")) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            if (message.has("partyEnabled") && message.get("partyEnabled").isJsonPrimitive()) {
                values.put("party-commands", Boolean.toString(message.get("partyEnabled").getAsBoolean()));
            }
            if (message.has("globalEnabled") && message.get("globalEnabled").isJsonPrimitive()) {
                values.put("global-commands", Boolean.toString(message.get("globalEnabled").getAsBoolean()));
            }
            if (message.has("cooldown") && message.get("cooldown").isJsonPrimitive()) {
                int seconds = Math.max(0, Math.min(600, message.get("cooldown").getAsInt()));
                values.put("others-cooldown-seconds", Integer.toString(seconds));
            }
            if (message.has("party") && message.get("party").isJsonArray()) {
                values.put(PARTY_KEY, join(readIds(message.getAsJsonArray("party"))));
            }
            if (message.has("global") && message.get("global").isJsonArray()) {
                values.put(GLOBAL_KEY, join(readIds(message.getAsJsonArray("global"))));
            }
            if (!values.isEmpty()) {
                this.services.config().setAll(values);
            }
            publish();
        }
    }

    private static Set<String> readIds(JsonArray array) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && group(element.getAsString()) != null) {
                ids.add(element.getAsString());
            }
        }
        return ids;
    }
}

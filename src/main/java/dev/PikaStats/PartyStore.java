package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Stores a manually maintained party and exposes it on the local WebSocket feed. */
final class PartyStore {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Path file;
    private final LinkedHashMap<String, String> members = new LinkedHashMap<>();

    PartyStore(File dataFolder) {
        this.file = new File(dataFolder, "party.json").toPath();
        load();
    }

    static boolean isValidUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    synchronized List<String> members() {
        return List.copyOf(this.members.values());
    }

    synchronized AddResult add(List<String> usernames) throws IOException {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(this.members);
        List<String> added = new ArrayList<>();
        int alreadyPresent = 0;
        int invalid = 0;
        for (String username : usernames) {
            if (!isValidUsername(username)) {
                invalid++;
                continue;
            }
            String key = username.toLowerCase(Locale.ROOT);
            if (updated.containsKey(key)) {
                alreadyPresent++;
                continue;
            }
            updated.put(key, username);
            added.add(username);
        }
        if (!added.isEmpty()) {
            save(updated);
            this.members.clear();
            this.members.putAll(updated);
        }
        return new AddResult(List.copyOf(added), alreadyPresent, invalid);
    }

    synchronized boolean remove(String username) throws IOException {
        if (!isValidUsername(username)) {
            return false;
        }
        String key = username.toLowerCase(Locale.ROOT);
        if (!this.members.containsKey(key)) {
            return false;
        }
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(this.members);
        updated.remove(key);
        save(updated);
        this.members.clear();
        this.members.putAll(updated);
        return true;
    }

    synchronized int clear() throws IOException {
        int removed = this.members.size();
        if (removed == 0) {
            return 0;
        }
        LinkedHashMap<String, String> updated = new LinkedHashMap<>();
        save(updated);
        this.members.clear();
        return removed;
    }

    static String apiMessage(List<String> members) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "party_update");
        JsonArray array = new JsonArray();
        members.forEach(array::add);
        message.add("members", array);
        return message.toString();
    }

    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(this.file));
            if (!parsed.isJsonObject()) {
                throw new IOException("party.json must contain a JSON object");
            }
            JsonElement storedMembers = parsed.getAsJsonObject().get("members");
            if (storedMembers == null || !storedMembers.isJsonArray()) {
                throw new IOException("party.json is missing its members array");
            }
            for (JsonElement element : storedMembers.getAsJsonArray()) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    continue;
                }
                String username = element.getAsString();
                if (isValidUsername(username)) {
                    this.members.putIfAbsent(username.toLowerCase(Locale.ROOT), username);
                }
            }
        } catch (Exception error) {
            System.err.println("[PikaStats] Could not load party.json: " + error.getMessage());
        }
    }

    private void save(Map<String, String> updated) throws IOException {
        Path parent = this.file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JsonObject data = new JsonObject();
        JsonArray array = new JsonArray();
        updated.values().forEach(array::add);
        data.add("members", array);

        Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        Files.writeString(temporary, data.toString() + System.lineSeparator(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record AddResult(List<String> added, int alreadyPresent, int invalid) {
    }
}

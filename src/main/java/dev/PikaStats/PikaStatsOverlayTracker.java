package dev.PikaStats;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import com.viaversion.nbt.tag.Tag;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tracks the current BedWars tab list and publishes cached stats to the Python overlay. */
final class PikaStatsOverlayTracker {
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern MODE = Pattern.compile("(?i)\\b(solo|doubles|triples|quads?|quadruples)\\b");
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final int MAX_COMPONENT_LENGTH = 262_144;
    private static final int MAX_RETRIES = 3;
    private static final long REFRESH_INTERVAL_SECONDS = 10;

    private final PikaStatsApi api;
    private final LocalWebSocketServer websocket;
    private final BedWarsMode fallbackMode;
    private final Map<UUID, String> tabPlayers = new LinkedHashMap<>();
    private final Map<UUID, String> knownPlayers = new HashMap<>();
    private final Map<String, PlayerState> players = new LinkedHashMap<>();
    private final Map<String, String> scoreboardObjectives = new HashMap<>();
    private final Collection<String> scoreboardLines = new java.util.LinkedHashSet<>();
    private final ScheduledExecutorService refreshScheduler;

    private boolean inBedWarsGame;
    private String activeObjective;
    private String currentObjectiveTitle = "";
    private BedWarsMode mode = BedWarsMode.ALL_MODES;
    private StatsPeriod period = StatsPeriod.ALL_TIME;
    private boolean modeManuallySelected = true;
    private boolean closed;
    private long gameGeneration;

    PikaStatsOverlayTracker(PikaStatsApi api, LocalWebSocketServer websocket, BedWarsMode fallbackMode) {
        this.api = api;
        this.websocket = websocket;
        this.fallbackMode = fallbackMode;
        this.refreshScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "PikaStats-Overlay-Refresh");
            thread.setDaemon(true);
            return thread;
        });
        this.refreshScheduler.scheduleWithFixedDelay(this::refreshPlayers, REFRESH_INTERVAL_SECONDS,
                REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.websocket.setMessageHandler(this::onOverlayMessage);
    }

    private void onOverlayMessage(String payload) {
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject message = parsed.getAsJsonObject();
            if (!message.has("type") || !message.get("type").isJsonPrimitive()) {
                return;
            }
            String messageType = message.get("type").getAsString();
            if ("player_details_request".equalsIgnoreCase(messageType)) {
                String name = message.has("name") && message.get("name").isJsonPrimitive()
                        ? message.get("name").getAsString() : "";
                String requestId = message.has("requestId") && message.get("requestId").isJsonPrimitive()
                        ? message.get("requestId").getAsString() : "";
                BedWarsMode requestedMode = message.has("mode") && message.get("mode").isJsonPrimitive()
                        ? BedWarsMode.fromName(message.get("mode").getAsString()) : null;
                StatsPeriod requestedPeriod = message.has("period") && message.get("period").isJsonPrimitive()
                        ? StatsPeriod.fromName(message.get("period").getAsString()) : null;
                requestPlayerDetails(name, requestId, requestedMode, requestedPeriod);
                return;
            }
            if ("reload_players".equalsIgnoreCase(messageType)) {
                reloadPlayers();
                return;
            }
            if (!"settings_update".equalsIgnoreCase(messageType)) {
                return;
            }

            List<PlayerState> requests = new ArrayList<>();
            String snapshot;
            boolean settingsChanged = false;
            boolean gameStarted = false;
            boolean playersAdded = false;
            synchronized (this) {
                if (message.has("mode") && message.get("mode").isJsonPrimitive()) {
                    BedWarsMode selected = BedWarsMode.fromName(message.get("mode").getAsString());
                    if (selected != null) {
                        this.modeManuallySelected = true;
                        if (this.mode != selected) {
                            this.mode = selected;
                            settingsChanged = true;
                        }
                    }
                }
                if (message.has("period") && message.get("period").isJsonPrimitive()) {
                    StatsPeriod selected = StatsPeriod.fromName(message.get("period").getAsString());
                    if (selected != null && this.period != selected) {
                        this.period = selected;
                        settingsChanged = true;
                    }
                }
                if (message.has("active") && message.get("active").isJsonPrimitive()
                        && message.get("active").getAsBoolean() && !this.inBedWarsGame) {
                    this.inBedWarsGame = true;
                    this.gameGeneration++;
                    gameStarted = true;
                }
                if (this.inBedWarsGame && message.has("players") && message.get("players").isJsonArray()) {
                    for (JsonElement entry : message.getAsJsonArray("players")) {
                        if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                            continue;
                        }
                        String name = entry.getAsString();
                        if (!PLAYER_NAME.matcher(name).matches()) {
                            continue;
                        }
                        String key = playerKey(name);
                        if (!this.players.containsKey(key)) {
                            PlayerState added = new PlayerState(UUID.randomUUID(), name, this.mode,
                                    this.period, this.gameGeneration);
                            this.players.put(key, added);
                            requests.add(added);
                            playersAdded = true;
                        }
                    }
                }
                if (!settingsChanged && !gameStarted && !playersAdded) {
                    return;
                }

                if (settingsChanged || gameStarted) {
                    this.gameGeneration++;
                    requests.clear();
                    for (PlayerState player : this.players.values()) {
                        player.requestedMode = this.mode;
                        player.requestedPeriod = this.period;
                        player.generation = this.gameGeneration;
                        player.status = player.hasStats() ? "refreshing" : "loading";
                        player.retriesUsed = 0;
                        player.inFlight = false;
                        if (this.inBedWarsGame) {
                            requests.add(player);
                        }
                    }
                }
                snapshot = buildMessage("game_update");
            }
            this.websocket.publish(snapshot, snapshot);
            requests.forEach(this::requestPlayerStats);
        } catch (RuntimeException ignored) {
            // Invalid local control messages must not affect ViaProxy.
        }
    }

    private void requestPlayerDetails(
            String name,
            String requestId,
            BedWarsMode requestedMode,
            StatsPeriod requestedPeriod) {
        if (!PLAYER_NAME.matcher(name).matches()) {
            return;
        }

        BedWarsMode queryMode;
        StatsPeriod queryPeriod;
        synchronized (this) {
            queryMode = requestedMode == null ? this.mode : requestedMode;
            queryPeriod = requestedPeriod == null ? this.period : requestedPeriod;
        }

        System.out.println("[PikaStats] Loading overlay details for " + name + " ("
                + queryPeriod.displayName() + " " + queryMode.displayName() + ")");

        CompletableFuture<LookupResult<StatsResult>> statsFuture = this.api
                .lookup(new StatsRequest(name, queryPeriod, queryMode))
                .handle(LookupResult::new);
        CompletableFuture<LookupResult<PunishmentsResult>> punishmentsFuture = this.api
                .lookupPunishments(name)
                .handle(LookupResult::new);

        statsFuture.thenCombine(punishmentsFuture, (stats, punishments) -> {
            if (stats.error() != null) {
                System.err.println("[PikaStats] Overlay stats details failed for " + name + ": "
                        + rootMessage(stats.error()));
            }
            if (punishments.error() != null) {
                System.err.println("[PikaStats] Overlay punishments details failed for " + name + ": "
                        + rootMessage(punishments.error()));
            }
            return buildPlayerDetails(name, requestId, queryMode, queryPeriod, stats, punishments);
        }).whenComplete((message, error) -> {
            if (error != null) {
                System.err.println("[PikaStats] Could not build overlay details for " + name + ": "
                        + rootMessage(error));
                publishPlayerDetailsUnavailable(name, requestId, "Detail lookup failed");
            } else {
                this.websocket.publish(message.toString(), null);
            }
        });
    }

    private void publishPlayerDetailsUnavailable(String name, String requestId, String error) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "player_details");
        message.addProperty("name", name);
        message.addProperty("requestId", requestId);
        message.addProperty("statsError", error);
        message.addProperty("punishmentsError", error);
        this.websocket.publish(message.toString(), null);
    }

    private static JsonObject buildPlayerDetails(
            String name,
            String requestId,
            BedWarsMode mode,
            StatsPeriod period,
            LookupResult<StatsResult> statsLookup,
            LookupResult<PunishmentsResult> punishmentsLookup) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "player_details");
        message.addProperty("name", name);
        message.addProperty("requestId", requestId);
        message.addProperty("mode", mode.name());
        message.addProperty("period", period.apiValue());

        if (statsLookup.value() == null) {
            message.addProperty("statsError", detailError(statsLookup.error(), "Stats unavailable"));
        } else {
            StatsResult stats = statsLookup.value();
            JsonObject values = new JsonObject();
            values.addProperty("canonicalUsername", stats.canonicalUsername());
            values.addProperty("wins", stats.wins());
            values.addProperty("losses", stats.losses());
            values.addProperty("finalKills", stats.finalKills());
            values.addProperty("finalDeaths", stats.finalDeaths());
            values.addProperty("kills", stats.kills());
            values.addProperty("deaths", stats.deaths());
            values.addProperty("gamesPlayed", stats.gamesPlayed());
            values.addProperty("bedsDestroyed", stats.bedsDestroyed());
            values.addProperty("meleeKills", stats.meleeKills());
            values.addProperty("bowKills", stats.bowKills());
            values.addProperty("voidKills", stats.voidKills());
            values.addProperty("arrowsShot", stats.arrowsShot());
            values.addProperty("arrowsHit", stats.arrowsHit());
            values.addProperty("highestWinStreakReached", stats.highestWinStreakReached());
            values.addProperty("fkdr", stats.fkdr());
            values.addProperty("wlr", stats.wlr());
            values.addProperty("kdr", stats.kdr());
            message.add("stats", values);
        }

        if (punishmentsLookup.value() == null) {
            message.addProperty("punishmentsError", detailError(
                    punishmentsLookup.error(), "Punishments unavailable"));
        } else {
            PunishmentsResult punishments = punishmentsLookup.value();
            JsonObject values = new JsonObject();
            values.addProperty("bans", punishments.bans());
            values.addProperty("mutes", punishments.mutes());
            values.addProperty("warns", punishments.warnings());
            values.addProperty("kicks", punishments.kicks());
            JsonArray entries = new JsonArray();
            for (PunishmentsResult.Entry entry : punishments.entries()) {
                JsonObject item = new JsonObject();
                item.addProperty("type", entry.type());
                item.addProperty("date", entry.date());
                item.addProperty("reason", entry.reason());
                entries.add(item);
            }
            values.add("entries", entries);
            message.add("punishments", values);
        }
        return message;
    }

    private static String detailError(Throwable error, String fallback) {
        if (error == null) {
            return fallback;
        }
        return PikaStatsApi.errorKind(error) == PikaStatsApi.ErrorKind.PLAYER_NOT_FOUND
                ? "Player not found" : fallback;
    }

    private record LookupResult<T>(T value, Throwable error) {
    }

    private void reloadPlayers() {
        List<PlayerState> requests = new ArrayList<>();
        String snapshot;
        synchronized (this) {
            if (!this.inBedWarsGame || this.closed) {
                return;
            }

            Set<String> currentRoster = new HashSet<>();
            for (String name : this.tabPlayers.values()) {
                if (PLAYER_NAME.matcher(name).matches()) {
                    currentRoster.add(playerKey(name));
                }
            }
            this.players.keySet().removeIf(name -> !currentRoster.contains(name));

            this.gameGeneration++;
            for (Map.Entry<UUID, String> entry : this.tabPlayers.entrySet()) {
                String name = entry.getValue();
                if (!PLAYER_NAME.matcher(name).matches()) {
                    continue;
                }
                PlayerState player = getOrCreatePlayer(entry.getKey(), name);
                if (player == null) {
                    player = this.players.get(playerKey(name));
                }
                if (player == null) {
                    continue;
                }
                player.requestedMode = this.mode;
                player.requestedPeriod = this.period;
                player.generation = this.gameGeneration;
                player.status = player.hasStats() ? "refreshing" : "loading";
                player.retriesUsed = 0;
                player.inFlight = false;
                requests.add(player);
            }
            snapshot = buildMessage("game_update");
        }
        this.websocket.publish(snapshot, snapshot);
        requests.forEach(this::requestPlayerStats);
    }

    void handlePacket(UnknownPacket packet, ProtocolVersion version) {
        if (version == null) {
            return;
        }
        int protocol = version.getVersion();
        try {
            if (packet.packetId == MCPackets.S2C_PLAYER_INFO.getId(protocol)) {
                handleLegacyPlayerInfo(packet.data, version);
            } else if (packet.packetId == MCPackets.S2C_PLAYER_INFO_UPDATE.getId(protocol)) {
                handleModernPlayerInfoUpdate(packet.data, version);
            } else if (packet.packetId == MCPackets.S2C_PLAYER_INFO_REMOVE.getId(protocol)) {
                handlePlayerInfoRemove(packet.data);
            } else if (packet.packetId == MCPackets.S2C_SET_OBJECTIVE.getId(protocol)) {
                handleScoreboardObjective(packet.data, version);
            } else if (packet.packetId == MCPackets.S2C_SET_DISPLAY_OBJECTIVE.getId(protocol)) {
                handleDisplayObjective(packet.data);
            } else if (packet.packetId == MCPackets.S2C_SET_SCORE.getId(protocol)) {
                handleScoreboardLine(packet.data);
            } else if (packet.packetId == MCPackets.S2C_SET_PLAYER_TEAM.getId(protocol)) {
                handleScoreboardTeam(packet.data, version);
            } else if (packet.packetId == MCPackets.S2C_SET_TITLE_TEXT.getId(protocol)
                    || packet.packetId == MCPackets.S2C_SET_SUBTITLE_TEXT.getId(protocol)
                    || packet.packetId == MCPackets.S2C_SET_ACTION_BAR_TEXT.getId(protocol)) {
                handleTitleText(packet.data, version);
            }
        } catch (Exception ignored) {
            // A packet layout unsupported by this client version must never interrupt ViaProxy.
        }
    }

    void onChatText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = normalize(text);
        if (isGameExitMessage(normalized)) {
            stopGame();
            return;
        }

        BedWarsMode detected = findMode(normalized);
        boolean hasBedWarsContext = containsBedWars(normalized);
        boolean gameStart = hasBedWarsContext && (containsAny(normalized,
                "game starts", "starting in", "game started", "game has started", "game begins",
                "starting the game", "enjoy the game", "mode:"));
        if (hasBedWarsContext && (detected != null || gameStart)) {
            if (isNewGameMessage(normalized) && this.inBedWarsGame) {
                resetRosterForNewGame(detected == null ? this.mode : detected);
            } else {
                startGame(detected == null ? this.fallbackMode : detected);
            }
        }
    }

    void onConnectionClosed() {
        stopGame();
        synchronized (this) {
            this.closed = true;
        }
        this.refreshScheduler.shutdownNow();
    }

    private void handleLegacyPlayerInfo(byte[] packetData, ProtocolVersion version) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packetData == null ? new byte[0] : packetData);
        try {
            int action = Types.VAR_INT.read(input);
            int count = Types.VAR_INT.read(input);
            if (count < 0 || count > 4096) {
                return;
            }
            List<PlayerChange> changes = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                UUID id = Types.UUID.read(input);
                if (action == 0) {
                    String name = PacketTypes.readString(input, 64);
                    Types.PROFILE_PROPERTY_ARRAY.read(input);
                    Types.VAR_INT.read(input); // Game mode
                    Types.VAR_INT.read(input); // Latency
                    readOptionalComponent(input, version);
                    changes.add(new PlayerChange(id, name, false));
                } else if (action == 1 || action == 2) {
                    Types.VAR_INT.read(input);
                } else if (action == 3) {
                    readOptionalComponent(input, version);
                } else if (action == 4) {
                    changes.add(new PlayerChange(id, null, true));
                } else {
                    return;
                }
            }
            applyChanges(changes);
        } finally {
            input.release();
        }
    }

    private void handleModernPlayerInfoUpdate(byte[] packetData, ProtocolVersion version) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packetData == null ? new byte[0] : packetData);
        try {
            BitSet actions;
            if (version.newerThanOrEqualTo(ProtocolVersion.v1_21_4)) {
                actions = Types.PROFILE_ACTIONS_ENUM1_21_4.read(input);
            } else if (version.newerThanOrEqualTo(ProtocolVersion.v1_21_2)) {
                actions = Types.PROFILE_ACTIONS_ENUM1_21_2.read(input);
            } else {
                actions = Types.PROFILE_ACTIONS_ENUM1_19_3.read(input);
            }

            int count = Types.VAR_INT.read(input);
            if (count < 0 || count > 4096) {
                return;
            }
            List<PlayerChange> changes = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                UUID id = Types.UUID.read(input);
                String name = null;
                Boolean listed = null;
                if (actions.get(0)) {
                    if (version.newerThanOrEqualTo(ProtocolVersion.v1_21_2)) {
                        var profile = Types.RESOLVABLE_PROFILE.read(input);
                        if (profile.profile() != null) {
                            name = profile.profile().name();
                        }
                    } else {
                        name = PacketTypes.readString(input, 64);
                        Types.PROFILE_PROPERTY_ARRAY.read(input);
                    }
                }
                if (actions.get(1)) {
                    Types.OPTIONAL_PROFILE_KEY.read(input);
                }
                if (actions.get(2)) {
                    Types.VAR_INT.read(input); // Game mode
                }
                if (actions.get(3)) {
                    listed = Types.BOOLEAN.read(input); // Listed in the player list
                }
                if (actions.get(4)) {
                    Types.VAR_INT.read(input); // Latency
                }
                if (actions.get(5)) {
                    readOptionalComponent(input, version);
                }
                if (actions.get(6)) {
                    Types.VAR_INT.read(input); // List order (1.21.2+)
                }
                if (actions.get(7)) {
                    Types.BOOLEAN.read(input); // Hat visibility (1.21.4+)
                }
                if (name != null || listed != null) {
                    changes.add(new PlayerChange(id, name, false, listed));
                }
            }
            applyChanges(changes);
        } finally {
            input.release();
        }
    }

    private void handlePlayerInfoRemove(byte[] packetData) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packetData == null ? new byte[0] : packetData);
        try {
            UUID[] ids = Types.UUID_ARRAY.read(input);
            List<PlayerChange> changes = new ArrayList<>(ids.length);
            for (UUID id : ids) {
                changes.add(new PlayerChange(id, null, true));
            }
            applyChanges(changes);
        } finally {
            input.release();
        }
    }

    private void applyChanges(List<PlayerChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<PlayerState> requests = new ArrayList<>();
        boolean rosterWasCleared;
        boolean playerInfoRemoved = false;
        synchronized (this) {
            for (PlayerChange change : changes) {
                if (change.removed()) {
                    playerInfoRemoved = true;
                    String oldName = this.tabPlayers.remove(change.id());
                    this.knownPlayers.remove(change.id());
                    if (oldName != null) {
                        this.players.remove(playerKey(oldName));
                        removed.add(oldName);
                    }
                    continue;
                }

                String name = change.name();
                if (name != null && !PLAYER_NAME.matcher(name).matches()) {
                    continue;
                }
                if (name != null) {
                    this.knownPlayers.put(change.id(), name);
                } else {
                    name = this.knownPlayers.get(change.id());
                }

                if (Boolean.FALSE.equals(change.listed())) {
                    String oldName = this.tabPlayers.remove(change.id());
                    if (oldName != null) {
                        this.players.remove(playerKey(oldName));
                        removed.add(oldName);
                    }
                    continue;
                }
                if (name == null || !PLAYER_NAME.matcher(name).matches()) {
                    continue;
                }

                String previousName = this.tabPlayers.put(change.id(), name);
                if (previousName != null && !previousName.equals(name)) {
                    this.players.remove(playerKey(previousName));
                    removed.add(previousName);
                }
                if (this.inBedWarsGame) {
                    PlayerState state = getOrCreatePlayer(change.id(), name);
                    if (state != null && !state.inFlight
                            && (state.status.equals("loading") || state.status.equals("refreshing"))) {
                        requests.add(state);
                    }
                    if (state != null && (previousName == null || !previousName.equals(name))) {
                        added.add(name);
                    }
                }
            }
            rosterWasCleared = playerInfoRemoved && this.inBedWarsGame && this.tabPlayers.isEmpty();
        }
        removed.forEach(this::publishPlayerRemoved);
        if (rosterWasCleared) {
            stopGame();
            return;
        }
        if (!added.isEmpty()) {
            publishPlayerUpdate();
        }
        requests.forEach(this::requestPlayerStats);
    }

    private PlayerState getOrCreatePlayer(UUID id, String name) {
        String key = playerKey(name);
        PlayerState state = this.players.get(key);
        if (state != null) {
            if (!state.id.equals(id)) {
                state.id = id;
                state.status = state.hasStats() ? "refreshing" : "loading";
                state.retriesUsed = 0;
                state.inFlight = false;
                state.generation = this.gameGeneration;
                state.requestedMode = this.mode;
                state.requestedPeriod = this.period;
                state.name = name;
                return state;
            }
            state.id = id;
            state.name = name;
            return null;
        }
        state = new PlayerState(id, name, this.mode, this.period, this.gameGeneration);
        this.players.put(key, state);
        return state;
    }

    private void requestPlayerStats(PlayerState state) {
        String queryName;
        UUID queryId;
        BedWarsMode queryMode;
        StatsPeriod queryPeriod;
        long queryGeneration;
        synchronized (this) {
            if (!this.inBedWarsGame || this.players.get(playerKey(state.name)) != state
                    || state.inFlight || this.closed) {
                return;
            }
            state.inFlight = true;
            queryName = state.name;
            queryId = state.id;
            queryMode = state.requestedMode;
            queryPeriod = state.requestedPeriod;
            queryGeneration = state.generation;
        }
        StatsRequest request = new StatsRequest(queryName, queryPeriod, queryMode);
        this.api.refresh(request).whenComplete((stats, error) -> {
            synchronized (PikaStatsOverlayTracker.this) {
                PlayerState current = this.players.get(playerKey(queryName));
                if (current == null || !current.id.equals(queryId) || current.generation != queryGeneration
                        || current.requestedMode != queryMode || current.requestedPeriod != queryPeriod
                        || !this.inBedWarsGame) {
                    return;
                }
                current.inFlight = false;
                if (error == null) {
                    current.name = stats.canonicalUsername();
                    current.fkdr = parseRatio(stats.fkdr());
                    current.wlr = parseRatio(stats.wlr());
                    current.kdr = parseRatio(stats.kdr());
                    current.loadedMode = queryMode;
                    current.loadedPeriod = queryPeriod;
                    current.status = "ready";
                    current.retriesUsed = 0;
                } else {
                    if (current.retriesUsed < MAX_RETRIES) {
                        current.retriesUsed++;
                        current.status = "retrying";
                    } else {
                        current.status = "nicked";
                        current.retriesUsed = MAX_RETRIES;
                        System.err.println("[PikaStats] Overlay stats did not load for " + queryName
                                + " after " + (MAX_RETRIES + 1) + " attempts: " + rootMessage(error));
                    }
                }
            }
            publishPlayerUpdate();
        });
    }

    private void refreshPlayers() {
        List<PlayerState> requests = new ArrayList<>();
        String snapshot;
        synchronized (this) {
            if (!this.inBedWarsGame || this.closed) {
                return;
            }
            for (PlayerState player : this.players.values()) {
                if (player.inFlight || player.status.equals("ready")) {
                    continue;
                }
                if (player.status.equals("nicked") && player.retriesUsed >= MAX_RETRIES) {
                    // Restart this player's recovery cycle without disturbing loaded players.
                    player.retriesUsed = 0;
                }
                if (player.hasStats()) {
                    player.status = "refreshing";
                }
                requests.add(player);
            }
            if (requests.isEmpty()) {
                return;
            }
            snapshot = buildMessage("game_update");
        }
        this.websocket.publish(snapshot, snapshot);
        requests.forEach(this::requestPlayerStats);
    }

    private void handleScoreboardObjective(byte[] packetData, ProtocolVersion version) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packetData == null ? new byte[0] : packetData);
        try {
            String name = PacketTypes.readString(input, 256);
            int action = input.readUnsignedByte();
            if (action == 1) {
                this.scoreboardObjectives.remove(name);
                boolean leavingBedWars = false;
                synchronized (this) {
                    if (name.equals(this.activeObjective)) {
                        leavingBedWars = containsBedWars(this.currentObjectiveTitle);
                        this.activeObjective = null;
                        this.currentObjectiveTitle = "";
                    }
                }
                if (leavingBedWars) {
                    stopGame();
                }
                return;
            }
            if (action != 0 && action != 2) {
                return;
            }
            String title = readComponent(input, version);
            this.scoreboardObjectives.put(name, title);
            synchronized (this) {
                if (name.equals(this.activeObjective)) {
                    this.currentObjectiveTitle = title;
                }
            }
            if (name.equals(this.activeObjective)) {
                onScoreboardText(title);
            }
        } finally {
            input.release();
        }
    }

    private void handleDisplayObjective(byte[] packetData) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packetData == null ? new byte[0] : packetData);
        try {
            int slot = input.readUnsignedByte();
            String objective = PacketTypes.readString(input, 256);
            if (slot != 1) {
                return;
            }
            synchronized (this) {
                this.activeObjective = objective.isEmpty() ? null : objective;
                this.currentObjectiveTitle = this.scoreboardObjectives.getOrDefault(objective, "");
            }
            if (!objective.isEmpty()) {
                onScoreboardText(this.scoreboardObjectives.getOrDefault(objective, objective));
            }
        } finally {
            input.release();
        }
    }

    private void handleScoreboardLine(byte[] packetData) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packetData == null ? new byte[0] : packetData);
        try {
            String line = PacketTypes.readString(input, 256);
            onScoreboardText(line);
        } finally {
            input.release();
        }
    }

    private void handleScoreboardTeam(byte[] packetData, ProtocolVersion version) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packetData == null ? new byte[0] : packetData);
        try {
            PacketTypes.readString(input, 256); // Team name
            int action = input.readUnsignedByte();
            if (action == 0 || action == 2) {
                if (version.newerThanOrEqualTo(ProtocolVersion.v1_13)) {
                    onScoreboardText(readComponent(input, version)); // Display name
                    input.readUnsignedByte(); // Friendly fire flags
                    PacketTypes.readString(input, 64); // Name tag visibility
                    PacketTypes.readString(input, 64); // Collision rule
                    Types.VAR_INT.read(input); // Team color
                    onScoreboardText(readComponent(input, version)); // Prefix
                    onScoreboardText(readComponent(input, version)); // Suffix
                } else {
                    onScoreboardText(PikaStatsChatParser.componentToPlainText(PacketTypes.readString(input, 256)));
                    String prefix = PacketTypes.readString(input, 256);
                    String suffix = PacketTypes.readString(input, 256);
                    onScoreboardText(prefix);
                    onScoreboardText(suffix);
                    input.readUnsignedByte(); // Friendly fire flags
                    if (version.newerThanOrEqualTo(ProtocolVersion.v1_9)) {
                        PacketTypes.readString(input, 64); // Name tag visibility
                        PacketTypes.readString(input, 64); // Collision rule
                        input.readByte(); // Team color
                    }
                }
            }
            if (action == 0 || action == 3 || action == 4) {
                int count = Types.VAR_INT.read(input);
                if (count < 0 || count > 4096) {
                    return;
                }
                for (int index = 0; index < count; index++) {
                    onScoreboardText(PacketTypes.readString(input, 64));
                }
            }
        } finally {
            input.release();
        }
    }

    private void handleTitleText(byte[] packetData, ProtocolVersion version) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(packetData == null ? new byte[0] : packetData);
        try {
            onTitleText(readComponent(input, version));
        } finally {
            input.release();
        }
    }

    private void onScoreboardText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        synchronized (this) {
            if (this.scoreboardLines.size() >= 64) {
                this.scoreboardLines.clear();
            }
            this.scoreboardLines.add(normalize(text));
        }
        String objectiveTitle;
        boolean scoreboardHasBedWars;
        List<String> lines;
        synchronized (this) {
            objectiveTitle = this.currentObjectiveTitle;
            lines = new ArrayList<>(this.scoreboardLines);
            scoreboardHasBedWars = containsBedWars(objectiveTitle)
                    || lines.stream().anyMatch(PikaStatsOverlayTracker::containsBedWars);
        }
        if (!scoreboardHasBedWars) {
            return;
        }

        BedWarsMode detected = findMode(objectiveTitle);
        if (detected == null) {
            for (String line : lines) {
                detected = findMode(line);
                if (detected != null) {
                    break;
                }
            }
        }
        if (detected != null) {
            startGame(detected);
        } else if (containsAny(objectiveTitle, "map", "players", "kills", "beds")
                || lines.stream().anyMatch(line -> containsAny(line, "map:", "players:", "kills:", "beds:"))) {
            startGame(this.fallbackMode);
        }
    }

    private void onTitleText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = normalize(text);
        BedWarsMode detected = findMode(normalized);
        if (containsBedWars(normalized) && detected != null) {
            startGame(detected);
        } else if (containsBedWars(normalized) && containsAny(normalized, "starting", "victory", "defeat")) {
            startGame(this.fallbackMode);
        }
    }

    private void startGame(BedWarsMode detectedMode) {
        List<PlayerState> requests = new ArrayList<>();
        boolean changed = false;
        synchronized (this) {
            if (!this.inBedWarsGame) {
                this.inBedWarsGame = true;
                this.gameGeneration++;
                this.mode = this.modeManuallySelected ? this.mode : detectedMode;
                this.players.clear();
                for (Map.Entry<UUID, String> entry : this.tabPlayers.entrySet()) {
                    PlayerState state = getOrCreatePlayer(entry.getKey(), entry.getValue());
                    if (state != null) {
                        requests.add(state);
                    }
                }
                changed = true;
            } else if (!this.modeManuallySelected && this.mode != detectedMode) {
                this.mode = detectedMode;
                for (PlayerState player : this.players.values()) {
                    player.requestedMode = detectedMode;
                    player.requestedPeriod = this.period;
                    player.generation = this.gameGeneration;
                    player.status = player.hasStats() ? "refreshing" : "loading";
                    player.retriesUsed = 0;
                    player.inFlight = false;
                    requests.add(player);
                }
                changed = true;
            }
        }
        if (changed) {
            publishGameUpdate();
            requests.forEach(this::requestPlayerStats);
        }
    }

    private void resetRosterForNewGame(BedWarsMode detectedMode) {
        List<PlayerState> requests = new ArrayList<>();
        synchronized (this) {
            this.gameGeneration++;
            this.mode = this.modeManuallySelected ? this.mode : detectedMode;
            Set<String> currentNames = new HashSet<>();
            for (String name : this.tabPlayers.values()) {
                currentNames.add(playerKey(name));
            }
            this.players.keySet().retainAll(currentNames);
            for (Map.Entry<UUID, String> entry : this.tabPlayers.entrySet()) {
                PlayerState state = getOrCreatePlayer(entry.getKey(), entry.getValue());
                if (state == null) {
                    state = this.players.get(playerKey(entry.getValue()));
                }
                state.generation = this.gameGeneration;
                state.requestedMode = this.mode;
                state.requestedPeriod = this.period;
                state.status = state.hasStats() ? "refreshing" : "loading";
                state.retriesUsed = 0;
                state.inFlight = false;
                requests.add(state);
            }
        }
        publishGameUpdate();
        requests.forEach(this::requestPlayerStats);
    }

    private void stopGame() {
        boolean changed;
        synchronized (this) {
            changed = this.inBedWarsGame || !this.players.isEmpty() || !this.tabPlayers.isEmpty();
            this.inBedWarsGame = false;
            if (!this.modeManuallySelected) {
                this.mode = this.fallbackMode;
            }
            this.players.clear();
            this.tabPlayers.clear();
            this.scoreboardLines.clear();
            this.scoreboardObjectives.clear();
            this.activeObjective = null;
            this.currentObjectiveTitle = "";
            this.gameGeneration++;
        }
        if (changed) {
            publishGameUpdate();
        }
    }

    private void publishGameUpdate() {
        String snapshot;
        synchronized (this) {
            snapshot = buildMessage("game_update");
        }
        this.websocket.publish(snapshot, snapshot);
    }

    private void publishPlayerUpdate() {
        String snapshot;
        synchronized (this) {
            snapshot = buildMessage("player_update");
        }
        this.websocket.publish(snapshot, snapshot);
    }

    private void publishPlayerRemoved(String name) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "player_removed");
        event.addProperty("name", name);
        String snapshot;
        synchronized (this) {
            snapshot = buildMessage("game_update");
        }
        this.websocket.publish(event.toString(), snapshot);
    }

    private String buildMessage(String type) {
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        message.addProperty("active", this.inBedWarsGame);
        message.addProperty("mode", this.mode.name());
        message.addProperty("period", this.period.apiValue());
        JsonArray playerArray = new JsonArray();
        this.players.values().stream()
                .sorted(Comparator.comparing(player -> player.name.toLowerCase(Locale.ROOT)))
                .forEach(player -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("name", player.name);
                    item.addProperty("status", player.status);
                    item.addProperty("retryCount", player.retriesUsed);
                    if (player.fkdr != null && player.wlr != null && player.kdr != null) {
                        item.addProperty("fkdr", player.fkdr);
                        item.addProperty("wlr", player.wlr);
                        item.addProperty("kdr", player.kdr);
                        if (player.loadedMode != null && player.loadedPeriod != null) {
                            item.addProperty("loadedMode", player.loadedMode.name());
                            item.addProperty("loadedPeriod", player.loadedPeriod.apiValue());
                        }
                    }
                    playerArray.add(item);
                });
        message.add("players", playerArray);
        return message.toString();
    }

    private static String readComponent(ByteBuf input, ProtocolVersion version) throws Exception {
        if (version.newerThanOrEqualTo(ProtocolVersion.v1_20_3)) {
            Tag tag = Types.TEXT_COMPONENT_TAG.read(input);
            return PikaStatsChatParser.tagToPlainText(tag);
        }
        String component = PacketTypes.readString(input, MAX_COMPONENT_LENGTH);
        return PikaStatsChatParser.componentToPlainText(component);
    }

    private static void readOptionalComponent(ByteBuf input, ProtocolVersion version) throws Exception {
        if (version.newerThanOrEqualTo(ProtocolVersion.v1_20_3)) {
            if (Types.BOOLEAN.read(input)) {
                Types.TEXT_COMPONENT_TAG.read(input);
            }
        } else {
            Types.OPTIONAL_COMPONENT.read(input);
        }
    }

    private static String normalize(String text) {
        return COLOR_CODES.matcher(text).replaceAll("").toLowerCase(Locale.ROOT).strip();
    }

    private static boolean containsBedWars(String text) {
        String normalized = normalize(text);
        return normalized.contains("bedwars") || normalized.contains("bed wars") || normalized.contains("bed-wars");
    }

    private static BedWarsMode findMode(String text) {
        Matcher matcher = MODE.matcher(normalize(text));
        if (!matcher.find()) {
            return null;
        }
        return switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "solo" -> BedWarsMode.SOLO;
            case "doubles" -> BedWarsMode.DOUBLES;
            case "triples" -> BedWarsMode.TRIPLES;
            default -> BedWarsMode.QUADS;
        };
    }

    private static boolean containsAny(String text, String... values) {
        String normalized = normalize(text);
        for (String value : values) {
            if (normalized.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGameExitMessage(String text) {
        return containsAny(text, "returned to the lobby", "returning to lobby", "sent to the lobby",
                "you left the game", "game over", "match ended", "match has ended", "back to lobby")
                && containsAny(text, "bedwars", "bed wars", "game", "match", "lobby");
    }

    private static boolean isNewGameMessage(String text) {
        return containsAny(text, "starting a new game", "new game starting", "next game", "game starts in");
    }

    private static String playerKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static double parseRatio(String value) {
        try {
            return Double.parseDouble(value);
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record PlayerChange(UUID id, String name, boolean removed, Boolean listed) {
        private PlayerChange(UUID id, String name, boolean removed) {
            this(id, name, removed, null);
        }
    }

    private static final class PlayerState {
        private UUID id;
        private String name;
        private BedWarsMode requestedMode;
        private StatsPeriod requestedPeriod;
        private long generation;
        private String status = "loading";
        private int retriesUsed;
        private boolean inFlight;
        private Double fkdr;
        private Double wlr;
        private Double kdr;
        private BedWarsMode loadedMode;
        private StatsPeriod loadedPeriod;

        private PlayerState(UUID id, String name, BedWarsMode requestedMode, StatsPeriod requestedPeriod, long generation) {
            this.id = id;
            this.name = name;
            this.requestedMode = requestedMode;
            this.requestedPeriod = requestedPeriod;
            this.generation = generation;
        }

        private boolean hasStats() {
            return this.fkdr != null && this.wlr != null && this.kdr != null;
        }
    }
}

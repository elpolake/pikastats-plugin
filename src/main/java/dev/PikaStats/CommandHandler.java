package dev.PikaStats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles {@code -commands} typed by the player and lookup commands typed by others. Lookups are
 * forwarded so friends see the command, then answered in the same chat (public or party).
 */
final class CommandHandler {
    private static final Pattern PARTY_CHAT_INPUT = Pattern.compile(
            "(?i)^/(?:pc|pchat|partychat|p\\s+c|p\\s+chat|party\\s+c|party\\s+chat)\\s+(.*)$");
    private static final Pattern INCOMING_PARTY = Pattern.compile("(?i)^\\s*(?:\\[?party\\]?|p)\\s*[|¦▏│>»:]");
    /** "Party ▏ Titan Name: msg", "[VIP] Name: msg", and global "<Titan> Name <68> › msg". */
    private static final Pattern SENDER_SPLIT = Pattern.compile("^(.{1,80}?)(?::|»|>>|→|›)\\s+(.+)$");
    private static final Pattern USERNAME_TOKEN = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final long ECHO_WINDOW_MILLIS = 12_000L;

    /** Result of handling client input: whether the original message continues to the server. */
    enum Outcome { FORWARD, CONSUME }

    private final Services services;
    private final ChatIO io;
    private final PikaStatsOverlayTracker tracker;
    private final Supplier<String> ownName;
    private final Map<String, ArrayDeque<Long>> pendingEchoes = new HashMap<>();
    private final Map<String, Long> othersCooldown = new HashMap<>();
    /** Own lookups ending in "global": answered once their echo shows which chat they landed in. */
    private final Map<String, Lookup> pendingShows = new java.util.concurrent.ConcurrentHashMap<>();
    /** Whether plain messages go to party chat (learned from echoes of the player's own messages). */
    private volatile Boolean partyChatMode;
    private volatile String lastPlainKey;
    private volatile long lastPlainAt;

    CommandHandler(Services services, ChatIO io, PikaStatsOverlayTracker tracker, Supplier<String> ownName) {
        this.services = services;
        this.io = io;
        this.tracker = tracker;
        this.ownName = ownName;
    }

    // ================================================================ client input

    Outcome onClientInput(String raw) {
        if (raw == null) {
            return Outcome.FORWARD;
        }
        String input = raw.strip();
        if (!input.startsWith("/") && !input.startsWith("!")) {
            // Its echo tells us whether plain messages currently go to party or global chat.
            this.lastPlainKey = normalize(input);
            this.lastPlainAt = System.currentTimeMillis();
        }
        Reply.Channel channel = Reply.Channel.PUBLIC;
        Matcher party = PARTY_CHAT_INPUT.matcher(input);
        if (party.matches()) {
            channel = Reply.Channel.PARTY;
            input = party.group(1).strip();
        } else if (input.startsWith("/")) {
            return Outcome.FORWARD;
        }
        if (!input.startsWith("-") || input.length() < 2) {
            return Outcome.FORWARD;
        }
        List<String> tokens = Arrays.asList(input.split("\\s+"));
        String command = tokens.get(0).substring(1).toLowerCase(Locale.ROOT);
        List<String> args = tokens.subList(1, tokens.size());
        Reply privateReply = new Reply(this.io, this.services, Reply.Channel.PRIVATE);

        switch (command) {
            case "help", "ps", "pikastats", "pshelp", "commands" -> {
                if (command.equals("ps") || command.equals("pikastats")) {
                    handleAdmin(args, privateReply);
                } else {
                    sendHelp(privateReply, args.isEmpty() ? "" : args.get(0));
                }
                return Outcome.CONSUME;
            }
            case "style", "styles", "theme", "themes" -> {
                handleStyle(command, args, privateReply);
                return Outcome.CONSUME;
            }
            case "party" -> {
                handleParty(args, privateReply);
                return Outcome.CONSUME;
            }
            case "grad", "gradient", "pgrad", "rainbow", "prainbow", "fade", "pfade" -> {
                Reply.Channel target = command.startsWith("p") || channel == Reply.Channel.PARTY
                        ? Reply.Channel.PARTY
                        : Boolean.TRUE.equals(this.partyChatMode) ? Reply.Channel.CURRENT_PARTY : Reply.Channel.PUBLIC;
                handleGradient(command.startsWith("p") ? command.substring(1) : command, args,
                        new Reply(this.io, this.services, target), privateReply);
                return Outcome.CONSUME;
            }
            case "partycommands", "partycmds", "partycommand", "pcmds" -> {
                toggleSetting("party-commands", "Party commands", args, privateReply,
                        "party members can use -stats, -sw, -profile, -compare, -punishments in party chat",
                        "only you can use commands");
                return Outcome.CONSUME;
            }
            case "global", "globalcommands", "globalcmds" -> {
                toggleSetting("global-commands", "Global commands", args, privateReply,
                        "players in global chat can use lookup commands (answers without colors)",
                        "commands typed in global chat are ignored");
                return Outcome.CONSUME;
            }
            case "allow", "perms", "permissions" -> {
                handleAllow(args, privateReply);
                return Outcome.CONSUME;
            }
            case "chars", "charset" -> {
                ChatCharset charset = this.services.charset();
                privateReply.send(this.services.formatter().notice("Charset",
                        "ASCII + " + charset.extraCount() + " extra allowed characters (extra-allowed-chars in config.properties)."));
                return Outcome.CONSUME;
            }
            default -> {
                // Lookups below.
            }
        }

        if (!isLookupCommand(command)) {
            return Outcome.FORWARD;
        }
        // Your own lookups are private unless you end them with "global" (show in the chat you are in)
        // or "party" (party chat).
        Reply.Channel target = Reply.Channel.PRIVATE;
        List<String> cleanArgs = new ArrayList<>();
        for (String token : args) {
            Reply.Channel keyword = replyKeyword(token);
            if (keyword != null) {
                target = keyword;
            } else {
                cleanArgs.add(token);
            }
        }
        Lookup lookup = parseLookup(command, cleanArgs);
        if (lookup == null) {
            sendUsage(command, privateReply);
            return Outcome.CONSUME;
        }
        if (target == Reply.Channel.PRIVATE) {
            runLookup(lookup, privateReply);
            return Outcome.CONSUME;
        }
        if (target == Reply.Channel.PARTY || channel == Reply.Channel.PARTY) {
            // Typed with /p c, or ended with "party": command and answer go to party chat.
            noteEcho(input);
            this.io.sendServer(Reply.Channel.PARTY.prefix() + input);
            runLookup(lookup, new Reply(this.io, this.services, Reply.Channel.PARTY));
            return Outcome.CONSUME;
        }
        // "global": send the command as typed so it lands in whatever chat the player is in; the echo
        // then tells us where that was, and the answer follows it there (colors only in party chat).
        String key = normalize(input);
        this.pendingShows.put(key, lookup);
        java.util.concurrent.CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
            if (this.pendingShows.remove(key, lookup)) {
                privateReply.send(this.services.formatter().error("Your command never showed up in chat"
                        + " (filtered or muted?), so the answer is only shown to you:"));
                runLookup(lookup, privateReply);
            }
        });
        return Outcome.FORWARD;
    }

    /** "global"/"g" -> the chat you are in, "party"/"pc" -> party chat; anything else is not a keyword. */
    private static Reply.Channel replyKeyword(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "global", "glob", "g", "everyone", "pub", "public", "show" -> Reply.Channel.PUBLIC;
            case "party", "pc" -> Reply.Channel.PARTY;
            default -> null;
        };
    }

    private void toggleSetting(String key, String title, List<String> args, Reply reply, String onText, String offText) {
        PluginConfig config = this.services.config();
        StatsFormatter formatter = this.services.formatter();
        String argument = args.isEmpty() ? "toggle" : args.get(0).toLowerCase(Locale.ROOT);
        boolean value;
        switch (argument) {
            case "toggle" -> value = !config.bool(key);
            case "on", "true", "enable", "yes" -> value = true;
            case "off", "false", "disable", "no" -> value = false;
            case "status" -> {
                boolean current = config.bool(key);
                reply.send(formatter.notice(title, current ? "ON - " + onText : "OFF - " + offText));
                return;
            }
            default -> {
                reply.send(formatter.info("Usage: -" + (key.startsWith("party") ? "partycommands" : "global") + " on|off"));
                return;
            }
        }
        config.set(key, Boolean.toString(value));
        this.services.commandPermissions().publish();
        reply.send(value ? formatter.notice(title + " ON", onText) : formatter.notice(title + " OFF", offText));
    }

    /** -allow [party|global] [all|none|cmd...]: which lookups others may use in each chat. */
    private void handleAllow(List<String> args, Reply reply) {
        PluginConfig config = this.services.config();
        StatsFormatter formatter = this.services.formatter();
        String usage = "Usage: -allow party|global all|none|<stats sw compare profile punishments>";
        if (args.isEmpty()) {
            for (CommandPermissions.Scope scope : CommandPermissions.Scope.values()) {
                boolean on = scope == CommandPermissions.Scope.PARTY ? config.partyCommands() : config.globalCommands();
                java.util.Set<String> allowed = CommandPermissions.allowed(config, scope);
                reply.send(formatter.notice(scope == CommandPermissions.Scope.PARTY ? "Party" : "Global",
                        (on ? "ON" : "OFF") + " - " + (allowed.isEmpty() ? "no commands" : String.join(", ", allowed))));
            }
            reply.send(formatter.info(usage));
            return;
        }
        CommandPermissions.Scope scope = switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "party", "p", "pc" -> CommandPermissions.Scope.PARTY;
            case "global", "g", "public" -> CommandPermissions.Scope.GLOBAL;
            default -> null;
        };
        if (scope == null || args.size() < 2) {
            reply.send(formatter.info(usage));
            return;
        }
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (String token : args.subList(1, args.size())) {
            for (String part : token.split(",")) {
                String lower = part.toLowerCase(Locale.ROOT).replaceFirst("^-", "");
                if (lower.isEmpty() || lower.equals("none")) {
                    continue;
                }
                if (lower.equals("all")) {
                    CommandPermissions.GROUPS.forEach(group -> ids.add(group.id()));
                    continue;
                }
                String id = CommandPermissions.group(lower) != null ? lower : CommandPermissions.groupOf(lower);
                if (id == null) {
                    reply.send(formatter.error("Unknown command " + part + ". Use stats, sw, compare, profile, punishments."));
                    return;
                }
                ids.add(id);
            }
        }
        CommandPermissions.setAllowed(config, scope, ids);
        this.services.commandPermissions().publish();
        reply.send(formatter.notice((scope == CommandPermissions.Scope.PARTY ? "Party" : "Global") + " commands",
                ids.isEmpty() ? "none allowed" : CommandPermissions.join(ids)));
    }

    // ================================================================ server chat

    void onServerChat(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String plain = ChatFormat.stripCodes(text).strip();
        Matcher split = SENDER_SPLIT.matcher(plain);
        if (!split.matches()) {
            return;
        }
        String rawBody = split.group(2).strip();
        String sender = lastUsername(split.group(1));
        String own = this.ownName.get();
        boolean fromSelf = sender != null && own != null && sender.equalsIgnoreCase(own);
        boolean fromParty = INCOMING_PARTY.matcher(plain).find();
        if (fromSelf) {
            learnChatMode(rawBody, fromParty);
        }
        // Players in party-chat mode reach global chat with "!", which Pika leaves in the text.
        String body = rawBody.replaceFirst("^!+\\s*", "");
        if (!body.startsWith("-")) {
            return;
        }
        if (fromSelf || sender == null) {
            Lookup show = this.pendingShows.remove(normalize(body));
            if (show != null) {
                runLookup(show, new Reply(this.io, this.services,
                        fromParty ? Reply.Channel.CURRENT_PARTY : Reply.Channel.PUBLIC));
                return;
            }
        }
        if (consumeEcho(body) || fromSelf) {
            return;
        }
        PluginConfig config = this.services.config();
        Reply.Channel channel;
        if (fromParty) {
            if (!config.partyCommands()) {
                return;
            }
            channel = Reply.Channel.PARTY;
        } else {
            if (!config.globalCommands()) {
                return;
            }
            // Answer in global chat: "!" is needed only while plain messages go to party chat.
            channel = Boolean.FALSE.equals(this.partyChatMode) ? Reply.Channel.PUBLIC : Reply.Channel.GLOBAL;
        }
        List<String> tokens = new ArrayList<>();
        for (String token : body.split("\\s+")) {
            // Only the proxy's player decides where answers go; ignore reply keywords from others.
            if (tokens.isEmpty() || replyKeyword(token) == null) {
                tokens.add(token);
            }
        }
        String command = tokens.get(0).substring(1).toLowerCase(Locale.ROOT);
        if (command.equals("threats") || command.equals("lobby") || command.equals("who")) {
            return; // Only the player using the proxy can ask about their own lobby.
        }
        CommandPermissions.Scope scope = fromParty ? CommandPermissions.Scope.PARTY : CommandPermissions.Scope.GLOBAL;
        if (!CommandPermissions.isAllowed(config, scope, command)) {
            return;
        }
        Lookup lookup = parseLookup(command, tokens.subList(1, tokens.size()));
        if (lookup == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = config.othersCooldownSeconds() * 1000L;
        String key = normalize(body);
        Long previous = this.othersCooldown.get(key);
        if ((previous != null && now - previous < cooldown) || this.io.queuedMessages() > 6) {
            return;
        }
        this.othersCooldown.put(key, now);
        if (this.othersCooldown.size() > 200) {
            this.othersCooldown.entrySet().removeIf(entry -> now - entry.getValue() > cooldown);
        }
        // Their message just went out: our first answer waits the full delay (500 ms+) after it.
        this.io.noteOutgoing();
        runLookup(lookup.withQuiet(false), new Reply(this.io, this.services, channel));
    }

    private void learnChatMode(String rawBody, boolean fromParty) {
        String key = this.lastPlainKey;
        if (key != null && System.currentTimeMillis() - this.lastPlainAt < ECHO_WINDOW_MILLIS
                && normalize(rawBody).equals(key)) {
            this.partyChatMode = fromParty;
            this.lastPlainKey = null;
        }
    }

    private static String lastUsername(String senderPart) {
        String[] parts = senderPart.replaceAll("[\\[\\](){}<>|¦▏│✦✧✪★☆•·]", " ").trim().split("\\s+");
        for (int index = parts.length - 1; index >= 0; index--) {
            // Skip level tags like "<68>" that Pika puts after the name in global chat.
            if (USERNAME_TOKEN.matcher(parts[index]).matches() && !parts[index].matches("\\d{1,3}")) {
                return parts[index];
            }
        }
        return null;
    }

    // ================================================================ lookups

    private enum LookupType { STATS, COMPARE, PROFILE, PUNISHMENTS, THREATS }

    private record Lookup(LookupType type, StatsRequest request, String second, String mode, boolean quiet) {
        Lookup withQuiet(boolean value) {
            return new Lookup(this.type, this.request, this.second, this.mode, value);
        }
    }

    private static boolean isLookupCommand(String command) {
        return switch (command) {
            case "stats", "s", "bw", "bedwars", "sw", "skywars", "compare", "vs", "profile", "pf", "level",
                 "lvl", "punishments", "pun", "bans", "history", "threats", "lobby", "who" -> true;
            default -> false;
        };
    }

    private Lookup parseLookup(String command, List<String> args) {
        switch (command) {
            case "stats", "s", "bw", "bedwars" -> {
                StatsRequest request = StatsRequest.parseArguments(GameType.BEDWARS, args);
                return request == null ? null : new Lookup(LookupType.STATS, request, null, null, request.quiet());
            }
            case "sw", "skywars" -> {
                StatsRequest request = StatsRequest.parseArguments(GameType.SKYWARS, args);
                return request == null ? null : new Lookup(LookupType.STATS, request, null, null, request.quiet());
            }
            case "compare", "vs" -> {
                if (args.size() < 2 || !PartyStore.isValidUsername(args.get(1))) {
                    return null;
                }
                GameType game = GameType.BEDWARS;
                List<String> rest = new ArrayList<>();
                rest.add(args.get(0));
                for (String token : args.subList(2, args.size())) {
                    if (token.equalsIgnoreCase("sw") || token.equalsIgnoreCase("skywars")) {
                        game = GameType.SKYWARS;
                    } else if (!token.equalsIgnoreCase("bw")) {
                        rest.add(token);
                    }
                }
                StatsRequest request = StatsRequest.parseArguments(game, rest);
                if (request == null || request.view() != StatsView.SUMMARY) {
                    return null;
                }
                return new Lookup(LookupType.COMPARE, request, args.get(1), null, request.quiet());
            }
            case "profile", "pf", "level", "lvl" -> {
                if (args.isEmpty() || args.size() > 2 || !PartyStore.isValidUsername(args.get(0))) {
                    return null;
                }
                boolean quiet = args.size() == 2 && StatsRequest.isQuietToken(args.get(1));
                if (args.size() == 2 && !quiet) {
                    return null;
                }
                return new Lookup(LookupType.PROFILE,
                        new StatsRequest(args.get(0), StatsPeriod.ALL_TIME, BedWarsMode.ALL_MODES), null, null, quiet);
            }
            case "punishments", "pun", "bans", "history" -> {
                if (args.isEmpty() || !PartyStore.isValidUsername(args.get(0))) {
                    return null;
                }
                String mode = "default";
                boolean quiet = false;
                for (String token : args.subList(1, args.size())) {
                    if (StatsRequest.isQuietToken(token)) {
                        quiet = true;
                    } else if (token.equalsIgnoreCase("reason") || token.equalsIgnoreCase("reasons")
                            || token.equalsIgnoreCase("full")) {
                        mode = "reason";
                    } else if (token.equalsIgnoreCase("count") || token.equalsIgnoreCase("counts")) {
                        mode = "count";
                    } else {
                        return null;
                    }
                }
                return new Lookup(LookupType.PUNISHMENTS,
                        new StatsRequest(args.get(0), StatsPeriod.ALL_TIME, BedWarsMode.ALL_MODES), null, mode, quiet);
            }
            case "threats", "lobby", "who" -> {
                return new Lookup(LookupType.THREATS, null, null, null, false);
            }
            default -> {
                return null;
            }
        }
    }

    private void runLookup(Lookup lookup, Reply reply) {
        StatsFormatter formatter = this.services.formatter();
        PikaStatsApi api = this.services.api();
        switch (lookup.type()) {
            case STATS -> api.lookup(lookup.request()).whenComplete((stats, error) -> {
                if (error != null) {
                    replyError(reply, lookup.request().username(), error);
                } else {
                    reply.send(formatter.stats(lookup.request(), stats));
                }
            });
            case COMPARE -> {
                CompletableFuture<StatsResult> first = api.lookup(lookup.request());
                CompletableFuture<StatsResult> second = api.lookup(lookup.request().withUsername(lookup.second()));
                first.thenCombine(second, (a, b) -> formatter.compare(lookup.request(), a, b))
                        .whenComplete((lines, error) -> {
                            if (error != null) {
                                replyError(reply, lookup.request().username() + "/" + lookup.second(), error);
                            } else {
                                reply.send(lines);
                            }
                        });
            }
            case PROFILE -> api.lookupProfile(lookup.request().username()).whenComplete((profile, error) -> {
                if (error != null) {
                    replyError(reply, lookup.request().username(), error);
                } else {
                    reply.send(formatter.profile(profile));
                }
            });
            case PUNISHMENTS -> api.lookupPunishments(lookup.request().username())
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            if (PikaStatsApi.errorKind(error) == PikaStatsApi.ErrorKind.PLAYER_NOT_FOUND) {
                                reply.send(formatter.info("No punishment record for " + lookup.request().username()));
                            } else {
                                replyError(reply, lookup.request().username(), error);
                            }
                            return;
                        }
                        reply.send(formatter.punishments(lookup.request().username(), result,
                                "reason".equals(lookup.mode()), "count".equals(lookup.mode())));
                    });
            case THREATS -> {
                PikaStatsOverlayTracker.LobbySnapshot snapshot = this.tracker.lobbySnapshot(5);
                if (!snapshot.active()) {
                    reply.privately().send(formatter.info("No BedWars game detected yet."));
                    return;
                }
                reply.send(formatter.threats(snapshot.threats(), snapshot.scope()));
            }
        }
    }

    private void replyError(Reply reply, String subject, Throwable error) {
        PikaStatsApi.ErrorKind kind = PikaStatsApi.errorKind(error);
        System.err.println("[PikaStats] Lookup failed for " + subject + ": " + PikaStatsApi.rootMessage(error));
        StatsFormatter formatter = this.services.formatter();
        if (kind == PikaStatsApi.ErrorKind.PLAYER_NOT_FOUND) {
            reply.send(formatter.error("Player not found: " + subject));
        } else if (kind == PikaStatsApi.ErrorKind.AUTH_REQUIRED) {
            // Never post setup problems publicly.
            reply.privately().send(formatter.error("API key missing/invalid. Run /apikey, put it in "
                    + "plugins/PikaStats/config.properties, then -ps reload."));
        } else {
            reply.privately().send(formatter.error(subject + ": " + kind.message()));
        }
    }

    // ================================================================ echo suppression

    private static String normalize(String text) {
        return ChatFormat.stripCodes(text).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private synchronized void noteEcho(String text) {
        long now = System.currentTimeMillis();
        pruneEchoes(now);
        this.pendingEchoes.computeIfAbsent(normalize(text), ignored -> new ArrayDeque<>()).addLast(now);
    }

    private synchronized boolean consumeEcho(String body) {
        long now = System.currentTimeMillis();
        pruneEchoes(now);
        String key = normalize(body);
        ArrayDeque<Long> timestamps = this.pendingEchoes.get(key);
        if (timestamps == null || timestamps.isEmpty()) {
            return false;
        }
        timestamps.removeFirst();
        if (timestamps.isEmpty()) {
            this.pendingEchoes.remove(key);
        }
        return true;
    }

    private void pruneEchoes(long now) {
        this.pendingEchoes.entrySet().removeIf(entry -> {
            ArrayDeque<Long> timestamps = entry.getValue();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > ECHO_WINDOW_MILLIS) {
                timestamps.removeFirst();
            }
            return timestamps.isEmpty();
        });
    }

    // ================================================================ styles

    private void handleStyle(String command, List<String> args, Reply reply) {
        ChatTheme current = this.services.theme();
        if (args.isEmpty() || command.equals("styles") || command.equals("themes")) {
            reply.send(new ChatLine(current).icon().accent("Chat styles").sep()
                    .text("current: ").value(current.id()).muted("  (-style <name> to switch)"));
            for (ChatTheme theme : ChatTheme.all()) {
                reply.send(sampleLine(theme, theme.id().equals(current.id())));
            }
            return;
        }
        ChatTheme selected = ChatTheme.find(args.get(0));
        if (selected == null) {
            reply.send(this.services.formatter().error("Unknown style '" + args.get(0) + "'. Type -styles."));
            return;
        }
        this.services.config().set("chat-style", selected.id());
        reply.send(new ChatLine(selected).icon().accent("Style set").sep().value(selected.id())
                .muted(" - " + selected.description()));
        reply.send(sampleLine(selected, true));
        ChatLine publicPreview = sampleLine(selected, false);
        String rendered = publicPreview.render(this.services.charset(), this.services.config().colorMode(),
                this.services.config().chatMaxLength());
        reply.send(new ChatLine(selected).muted("Public form (" + rendered.length() + "/"
                + this.services.config().chatMaxLength() + " chars): ").text(ChatFormat.stripCodes(rendered)));
    }

    private static ChatLine sampleLine(ChatTheme theme, boolean current) {
        ChatLine line = new ChatLine(theme).icon().name(theme.id()).sep()
                .stat("FKDR", ChatTheme.Tier.FKDR, 4.57, "4.57").sep()
                .stat("WLR", ChatTheme.Tier.WLR, 1.46, "1.46").sep()
                .stat("KDR", ChatTheme.Tier.KDR, 8.4, "8.40").sep()
                .muted(theme.description());
        if (current) {
            line.muted("  ").good("(active)");
        }
        return line;
    }

    // ================================================================ gradients

    private void handleGradient(String command, List<String> args, Reply target, Reply privateReply) {
        int index = 0;
        List<Integer> colors = new ArrayList<>();
        while (index < args.size() && ChatFormat.normalizeHex(args.get(index)) != null) {
            colors.add(ChatFormat.rgb(args.get(index)));
            index++;
        }
        String text = String.join(" ", args.subList(index, args.size())).strip();
        if (text.isEmpty()) {
            privateReply.send(this.services.formatter().info(
                    "Usage: -grad [#from] [#to] [#more] <text>, -rainbow <text>, -fade <text>. Prefix p for party chat (-pgrad)."));
            return;
        }
        int[] palette;
        if (command.equals("rainbow")) {
            palette = new int[]{0xff5f6d, 0xffc371, 0xf9f871, 0x5ef38c, 0x4fc3f7, 0x9575cd, 0xf06292};
        } else if (colors.size() >= 2) {
            palette = colors.stream().mapToInt(Integer::intValue).toArray();
        } else if (colors.size() == 1) {
            palette = new int[]{colors.get(0), ChatFormat.lerp(colors.get(0), 0x000000, 0.75)};
        } else {
            palette = this.services.theme().gradient();
        }
        String safe = this.services.charset().sanitize(ChatFormat.stripCodes(text));
        ChatFormat.ColorMode mode = target.colorMode();
        if (mode == ChatFormat.ColorMode.NONE) {
            privateReply.send(this.services.formatter().error(
                    "Colors only work in party chat here. Use -pgrad / -prainbow / -pfade."));
            return;
        }
        String formatted = ChatFormat.gradient(safe, palette, mode, target.budget());
        target.sendRaw(formatted);
    }

    // ================================================================ party list

    private void handleParty(List<String> args, Reply reply) {
        StatsFormatter formatter = this.services.formatter();
        PartyStore store = this.services.partyStore();
        if (args.isEmpty()) {
            reply.send(formatter.info("Usage: -party list | add <names> | remove <name> | clear"));
            return;
        }
        try {
            switch (args.get(0).toLowerCase(Locale.ROOT)) {
                case "list", "ls" -> {
                    List<String> members = store.members();
                    reply.send(formatter.notice("Party list (" + members.size() + ")",
                            members.isEmpty() ? "empty" : String.join(", ", members)));
                }
                case "add" -> {
                    PartyStore.AddResult result = store.add(args.subList(1, args.size()));
                    if (!result.added().isEmpty()) {
                        publishParty();
                        reply.send(formatter.notice("Added", String.join(", ", result.added())));
                    }
                    if (result.alreadyPresent() > 0) {
                        reply.send(formatter.info(result.alreadyPresent() + " already in the list."));
                    }
                    if (result.invalid() > 0 || args.size() == 1) {
                        reply.send(formatter.error("Skipped " + result.invalid() + " invalid name(s)."));
                    }
                }
                case "remove", "rm", "del" -> {
                    if (args.size() < 2) {
                        reply.send(formatter.info("Usage: -party remove <name>"));
                        return;
                    }
                    List<String> removed = new ArrayList<>();
                    for (String name : args.subList(1, args.size())) {
                        if (store.remove(name)) {
                            removed.add(name);
                        }
                    }
                    if (removed.isEmpty()) {
                        reply.send(formatter.error("Not in the party list."));
                    } else {
                        publishParty();
                        reply.send(formatter.notice("Removed", String.join(", ", removed)));
                    }
                }
                case "clear" -> {
                    int removed = store.clear();
                    if (removed > 0) {
                        publishParty();
                    }
                    reply.send(formatter.info(removed == 0 ? "Party list is already empty." : "Party list cleared."));
                }
                default -> reply.send(formatter.info("Usage: -party list | add <names> | remove <name> | clear"));
            }
        } catch (java.io.IOException error) {
            System.err.println("[PikaStats] Could not save party.json: " + error.getMessage());
            reply.send(formatter.error("Could not save the party list."));
        }
    }

    private void publishParty() {
        this.services.websocket().publishParty(PartyStore.apiMessage(this.services.partyStore().members()));
    }


    // ================================================================ admin & help

    private void handleAdmin(List<String> args, Reply reply) {
        StatsFormatter formatter = this.services.formatter();
        String sub = args.isEmpty() ? "help" : args.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                this.services.reload();
                reply.send(formatter.notice("Reloaded", "config, API key and allowed characters"));
            }
            case "keys", "key", "apikeys" -> {
                ApiKeyPool pool = this.services.keyPool();
                if (pool.keys().isEmpty()) {
                    reply.send(formatter.error("No API keys. Add them in ViaProxy's PikaStats tab or config.properties."));
                    return;
                }
                reply.send(formatter.notice("API keys", pool.keys().size() + " key(s), " + pool.totalRemaining() + "/"
                        + pool.totalLimit() + " requests left this minute, queue " + pool.queued()));
                long now = System.currentTimeMillis();
                int index = 1;
                for (ApiKeyPool.Key key : pool.keys()) {
                    reply.send(formatter.info("#" + index++ + " " + key.masked() + "  " + key.state() + "  "
                            + key.remaining(now) + "/" + key.limit + " left, " + key.used.get() + " sent, "
                            + key.rateLimited.get() + " rate limited"));
                }
            }
            case "clear", "cache" -> {
                this.services.api().clearCaches();
                reply.send(formatter.notice("Cache", "cleared"));
            }
            case "status", "info", "version" -> {
                PluginConfig config = this.services.config();
                reply.send(formatter.notice("PikaStats " + PikaStatsPlugin.VERSION,
                        "style " + config.chatTheme().id() + ", colors " + config.colorMode().name().toLowerCase(Locale.ROOT)
                                + ", limit " + config.chatMaxLength() + ", API keys " + this.services.keyPool().keys().size()
                                + " (" + this.services.keyPool().totalLimit() + "/min), requests " + this.services.api().requestsSent()
                                + ", cache hits " + this.services.api().cacheHits()));
                String overlay = this.services.websocket().clientCount() + " client(s) on 127.0.0.1:" + config.websocketPort();
                reply.send(formatter.notice("Overlay", overlay));
            }
            case "partycommands", "party" -> toggleSetting("party-commands", "Party commands",
                    args.subList(1, args.size()), reply, "party members can use lookup commands", "only you can use commands");
            case "global" -> toggleSetting("global-commands", "Global commands", args.subList(1, args.size()), reply,
                    "everyone can use lookup commands", "other players' commands outside your party are ignored");
            case "colors" -> {
                String mode = args.size() > 1 ? args.get(1) : "";
                ChatFormat.ColorMode parsed = ChatFormat.ColorMode.parse(mode, null);
                if (parsed == null) {
                    reply.send(formatter.info("Usage: -ps colors hex|legacy|none"));
                    return;
                }
                this.services.config().set("color-mode", parsed.name().toLowerCase(Locale.ROOT));
                reply.send(formatter.notice("Color mode", parsed.name().toLowerCase(Locale.ROOT)));
            }
            default -> sendHelp(reply, "");
        }
    }

    private void sendUsage(String command, Reply reply) {
        String usage = switch (command) {
            case "sw", "skywars" -> "-sw <player> [solo|doubles] [w|m|y] [wins|kdr|full|...] [q]";
            case "compare", "vs" -> "-compare <player1> <player2> [mode] [period] [sw] [q]";
            case "profile", "pf", "level", "lvl" -> "-profile <player> [q]";
            case "punishments", "pun", "bans", "history" -> "-punishments <player> [count|reason] [q]";
            default -> "-stats <player> [solo|2s|3s|4s] [w|m|y] [fkdr|wins|full|lb|combat|...] [q]";
        };
        reply.send(this.services.formatter().info("Usage: " + usage));
    }

    private void sendHelp(Reply reply, String topic) {
        ChatTheme theme = this.services.theme();
        String[][] rows = {
                {"-stats <p> [mode] [period] [view]", "BedWars stats. Views: full, lb, combat, fkdr, wins, ws, wr..."},
                {"-sw <p> [solo|doubles] [period]", "SkyWars stats"},
                {"-compare <p1> <p2> [mode]", "Head to head (also -vs)"},
                {"-profile <p>", "Network level, rank, clan, last seen"},
                {"-punishments <p> [count|reason]", "Ban/mute history"},
                {"-threats", "Strongest players in your current game"},
                {"lookups only show to you", "end with 'global' to show them in the chat you are in, or 'party'"},
                {"-partycommands on|off", "Let party members use lookups in party chat"},
                {"-global on|off", "Let players in global chat use lookups (answers without colors)"},
                {"-allow party|global <cmds>", "Which lookups others may use there (also in the overlay)"},
                {"-style [name] / -styles", "Pick a chat color style"},
                {"-grad [#a] [#b] <text>", "Send gradient text; -rainbow, -fade, -pgrad for party"},
                {"-party list|add|remove|clear", "Saved party list (PARTY badges in the overlay)"},
                {"-ps status|keys|reload|colors|clear", "Settings"},
        };
        String title = "PikaStats " + PikaStatsPlugin.VERSION;
        reply.send(new ChatLine(theme).icon().accent(title).sep()
                .muted("modes: solo 2s 3s 4s  periods: w m y"));
        for (String[] row : rows) {
            reply.send(new ChatLine(theme).muted(" ").value(row[0]).sep().text(row[1]));
        }
    }
}

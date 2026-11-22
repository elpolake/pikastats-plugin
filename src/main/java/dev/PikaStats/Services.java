package dev.PikaStats;

import java.io.File;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** Plugin-wide state shared by every proxied connection. Reloadable with {@code -ps reload}. */
final class Services {
    private final File dataFolder;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final PartyStore partyStore;
    private final LocalWebSocketServer websocket;
    private final ApiKeyPool keyPool;
    private final PikaStatsApi api;
    private final CommandPermissions commandPermissions;
    private volatile PluginConfig config;
    private volatile ChatCharset charset;

    Services(File dataFolder, ExecutorService executor, PluginConfig config, PartyStore partyStore,
             LocalWebSocketServer websocket) {
        this.dataFolder = dataFolder;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.partyStore = partyStore;
        this.websocket = websocket;
        // One pool and cache for the plugin's lifetime, so reloads keep quotas and cached stats.
        this.keyPool = new ApiKeyPool(this.httpClient);
        this.api = new PikaStatsApi(this.httpClient, executor, this.keyPool);
        apply(config);
        this.commandPermissions = new CommandPermissions(this);
    }

    CommandPermissions commandPermissions() {
        return this.commandPermissions;
    }

    private void apply(PluginConfig config) {
        this.config = config;
        this.keyPool.setKeys(config.apiKeys());
        File pluginsFolder = this.dataFolder.getParentFile();
        List<Path> charsetFiles = new java.util.ArrayList<>(List.of(new File(this.dataFolder, "allowed-chars.txt").toPath()));
        this.charset = ChatCharset.load(charsetFiles, config.extraAllowedChars());
        if (this.keyPool.hasKeys()) {
            System.out.println("[PikaStats] " + this.keyPool.keys().size() + " API key(s) loaded, "
                    + this.keyPool.totalLimit() + " requests/minute.");
        } else {
            System.err.println("[PikaStats] No API key set. Run /apikey in game and put it in "
                    + new File(this.dataFolder, "config.properties") + " (api-key=...).");
        }
    }

    void reload() {
        apply(PluginConfig.load(this.dataFolder));
        this.commandPermissions.publish();
    }

    PluginConfig config() {
        return this.config;
    }

    ApiKeyPool keyPool() {
        return this.keyPool;
    }

    /** Saves a new key list and starts using it immediately. */
    void updateApiKeys(List<String> keys) {
        this.config.setApiKeys(keys);
        this.keyPool.setKeys(this.config.apiKeys());
    }

    void close() {
        this.keyPool.close();
    }

    PikaStatsApi api() {
        return this.api;
    }

    ChatCharset charset() {
        return this.charset;
    }

    PartyStore partyStore() {
        return this.partyStore;
    }

    LocalWebSocketServer websocket() {
        return this.websocket;
    }


    ChatTheme theme() {
        return this.config.chatTheme();
    }

    StatsFormatter formatter() {
        return new StatsFormatter(theme());
    }
}

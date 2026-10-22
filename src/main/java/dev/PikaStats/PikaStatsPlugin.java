package dev.PikaStats;

import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;

import java.net.http.HttpClient;
import java.time.Duration;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class PikaStatsPlugin extends ViaProxyPlugin {

    private ExecutorService executor;
    private PikaStatsListener listener;
    private LocalWebSocketServer websocket;

    @Override
    public void onEnable() {
        this.executor = Executors.newFixedThreadPool(2, new DaemonThreadFactory());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(this.executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        OverlaySettings settings = OverlaySettings.load(getDataFolder());
        PikaStatsApi api = new PikaStatsApi(
                httpClient, this.executor, new PikaStatsCache(Duration.ofSeconds(45)), settings.apiKey());
        if (settings.apiKey().isBlank()) {
            System.err.println("[PikaStats] PikaNetwork now requires an API key. Run /apikey in game and set api-key in plugins/PikaStats/overlay.properties.");
        }
        this.websocket = new LocalWebSocketServer(settings.websocketPort(), initialOverlayMessage());
        try {
            this.websocket.start();
            System.out.println("[PikaStats] Local overlay WebSocket listening on 127.0.0.1:" + settings.websocketPort());
        } catch (Exception error) {
            System.err.println("[PikaStats] Could not start the overlay WebSocket: " + error.getMessage());
        }
        this.listener = new PikaStatsListener(api, this.websocket, settings.fallbackMode());
        ViaProxy.EVENT_MANAGER.register(this.listener);
        System.out.println("[PikaStats] Enabled for ViaProxy 3.4.14");
    }

    @Override
    public void onDisable() {
        if (this.listener != null) {
            ViaProxy.EVENT_MANAGER.unregister(this.listener);
            this.listener = null;
        }
        if (this.websocket != null) {
            this.websocket.close();
            this.websocket = null;
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
        System.out.println("[PikaStats] Disabled");
    }

    private static String initialOverlayMessage() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "game_update");
        message.addProperty("active", false);
        message.addProperty("mode", BedWarsMode.ALL_MODES.name());
        message.addProperty("period", StatsPeriod.ALL_TIME.apiValue());
        message.add("players", new JsonArray());
        return message.toString();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger nextThread = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "PikaStats-HTTP-" + this.nextThread.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

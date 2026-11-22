package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class PikaStatsPlugin extends ViaProxyPlugin {
    static final String VERSION = "2.3.4";

    private ExecutorService executor;
    private PikaStatsListener listener;
    private LocalWebSocketServer websocket;
    private Services services;

    @Override
    public void onEnable() {
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "PikaStats-HTTP-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        PluginConfig config = PluginConfig.load(getDataFolder());
        PartyStore partyStore = new PartyStore(getDataFolder());
        this.websocket = new LocalWebSocketServer(config.websocketPort(), initialOverlayMessage(),
                PartyStore.apiMessage(partyStore.members()));
        try {
            this.websocket.start();
            System.out.println("[PikaStats] Overlay WebSocket listening on 127.0.0.1:" + config.websocketPort());
        } catch (Exception error) {
            System.err.println("[PikaStats] Could not start the overlay WebSocket: " + error.getMessage());
        }
        this.services = new Services(getDataFolder(), this.executor, config, partyStore, this.websocket);
        this.listener = new PikaStatsListener(this.services);
        ViaProxy.EVENT_MANAGER.register(this.listener);
        KeysTab.installWhenReady(this.services);
        String build = "";
        System.out.println("[PikaStats] " + VERSION + build + " enabled. Style: " + config.chatTheme().id()
                + ", type -help in game.");
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
        if (this.services != null) {
            this.services.close();
            this.services = null;
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
}

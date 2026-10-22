package dev.PikaStats;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

record OverlaySettings(int websocketPort, BedWarsMode fallbackMode, String apiKey) {
    private static final int DEFAULT_PORT = 8765;

    static OverlaySettings load(File dataFolder) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            System.err.println("[PikaStats] Could not create plugin data folder: " + dataFolder);
        }

        File file = new File(dataFolder, "overlay.properties");
        Properties properties = new Properties();
        boolean saveProperties = !file.isFile();
        if (file.isFile()) {
            try (InputStream input = Files.newInputStream(file.toPath())) {
                properties.load(input);
            } catch (IOException error) {
                System.err.println("[PikaStats] Could not read overlay.properties: " + error.getMessage());
            }
        }
        if (!file.isFile()) {
            properties.setProperty("websocket-port", Integer.toString(DEFAULT_PORT));
            properties.setProperty("fallback-mode", "QUADS");
        }
        if (!properties.containsKey("api-key")) {
            properties.setProperty("api-key", "");
            saveProperties = true;
        }
        if (saveProperties) {
            try (OutputStream output = Files.newOutputStream(file.toPath())) {
                properties.store(output, "PikaStats settings. Get an API key in game with /apikey.");
            } catch (IOException error) {
                System.err.println("[PikaStats] Could not create overlay.properties: " + error.getMessage());
            }
        }

        int port = parsePort(properties.getProperty("websocket-port"));
        BedWarsMode fallback = BedWarsMode.fromName(properties.getProperty("fallback-mode", "QUADS"));
        if (fallback == null || fallback == BedWarsMode.ALL_MODES) {
            fallback = BedWarsMode.QUADS;
        }
        String environmentApiKey = System.getenv("PIKA_STATS_API_KEY");
        String apiKey = environmentApiKey == null || environmentApiKey.isBlank()
                ? properties.getProperty("api-key", "").trim()
                : environmentApiKey.trim();
        return new OverlaySettings(port, fallback, apiKey);
    }

    private static int parsePort(String rawPort) {
        try {
            int port = Integer.parseInt(rawPort);
            return port >= 1024 && port <= 65535 ? port : DEFAULT_PORT;
        } catch (RuntimeException ignored) {
            return DEFAULT_PORT;
        }
    }
}

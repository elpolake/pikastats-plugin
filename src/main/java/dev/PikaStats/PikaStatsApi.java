package dev.PikaStats;

import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class PikaStatsApi {
    private static final String API_ROOT = "https://stats.pika-network.net/api/profile/";
    private static final String PUNISHMENTS_ROOT = "https://pika-network.net/bans/search/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String PLAYER_NAME = "[A-Za-z0-9_]{1,16}";

    private final HttpClient client;
    private final Executor executor;
    private final PikaStatsCache cache;
    private final String apiKey;

    PikaStatsApi(HttpClient client, Executor executor, PikaStatsCache cache) {
        this(client, executor, cache, System.getenv("PIKA_STATS_API_KEY"));
    }

    PikaStatsApi(HttpClient client, Executor executor, PikaStatsCache cache, String apiKey) {
        this.client = client;
        this.executor = executor;
        this.cache = cache;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    CompletableFuture<StatsResult> lookup(StatsRequest request) {
        return this.cache.canonicalName(request.username(), () -> resolveCanonicalName(request.username()))
                .thenCompose(canonicalName -> this.cache.stats(canonicalName, request.period(), request.mode(),
                        () -> fetchStats(canonicalName, request.period(), request.mode())));
    }

    CompletableFuture<StatsResult> refresh(StatsRequest request) {
        return this.cache.canonicalName(request.username(), () -> resolveCanonicalName(request.username()))
                .thenCompose(canonicalName -> this.cache.refreshStats(canonicalName, request.period(), request.mode(),
                        () -> fetchStats(canonicalName, request.period(), request.mode())));
    }

    CompletableFuture<PunishmentsResult> lookupPunishments(String username) {
        if (username == null || !username.matches(PLAYER_NAME)) {
            return CompletableFuture.failedFuture(new PikaStatsException(ErrorKind.PLAYER_NOT_FOUND, 0));
        }

        URI uri = URI.create(PUNISHMENTS_ROOT + username + "/");
        return getPublicHtml(uri).thenApply(response -> {
            requireSuccess(response);
            try {
                return PikaStatsPunishmentsParser.parse(response.body());
            } catch (PikaStatsPunishmentsParser.PlayerNotFoundException exception) {
                throw new PikaStatsException(ErrorKind.PLAYER_NOT_FOUND, response.statusCode(), exception);
            } catch (RuntimeException exception) {
                throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode(), exception);
            }
        });
    }

    private CompletableFuture<String> resolveCanonicalName(String requestedName) {
        if (!requestedName.matches(PLAYER_NAME)) {
            return CompletableFuture.failedFuture(new PikaStatsException(ErrorKind.PLAYER_NOT_FOUND, 0));
        }

        URI uri = URI.create(API_ROOT + requestedName);
        return get(uri).thenApply(response -> {
            requireSuccess(response);
            JsonElement root;
            try {
                root = JsonParser.parseString(response.body());
            } catch (RuntimeException exception) {
                throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode(), exception);
            }
            if (!root.isJsonObject()) {
                throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode());
            }
            JsonObject profile = root.getAsJsonObject();
            JsonElement name = profile.get("username");
            if (name == null || !name.isJsonPrimitive()) {
                throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode());
            }
            String canonicalName = name.getAsString();
            if (!canonicalName.matches(PLAYER_NAME)) {
                throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode());
            }
            return canonicalName;
        });
    }

    private CompletableFuture<StatsResult> fetchStats(String canonicalName, StatsPeriod period, BedWarsMode mode) {
        String uri = API_ROOT + canonicalName + "/leaderboard?type=bedwars&interval="
                + period.apiValue() + "&mode=" + mode.apiValue();
        return get(URI.create(uri)).thenApply(response -> {
            requireSuccess(response);
            try {
                return PikaStatsParser.parse(canonicalName, response.body());
            } catch (RuntimeException exception) {
                throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode(), exception);
            }
        });
    }

    private CompletableFuture<HttpResponse<String>> get(URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "ViaProxy-PikaStats/1.0")
                .GET();
        if (!this.apiKey.isEmpty()) {
            request.header("X-Api-Key", this.apiKey);
        }
        return this.client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> response, this.executor);
    }

    private CompletableFuture<HttpResponse<String>> getPublicHtml(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html")
                .header("User-Agent", "ViaProxy-PikaStats/1.0")
                .GET()
                .build();
        return this.client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> response, this.executor);
    }

    private static void requireSuccess(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 404) {
            throw new PikaStatsException(ErrorKind.PLAYER_NOT_FOUND, status);
        }
        if (status == 401) {
            throw new PikaStatsException(ErrorKind.AUTH_REQUIRED, status);
        }
        if (status == 429) {
            throw new PikaStatsException(ErrorKind.RATE_LIMITED, status);
        }
        if (status == 400) {
            throw new PikaStatsException(ErrorKind.BAD_REQUEST, status);
        }
        if (status >= 500) {
            throw new PikaStatsException(ErrorKind.SERVER_ERROR, status);
        }
        throw new PikaStatsException(ErrorKind.API_UNAVAILABLE, status);
    }

    static ErrorKind errorKind(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PikaStatsException apiException) {
                return apiException.kind;
            }
            current = current.getCause();
        }
        return ErrorKind.NETWORK_ERROR;
    }

    enum ErrorKind {
        PLAYER_NOT_FOUND,
        AUTH_REQUIRED,
        RATE_LIMITED,
        BAD_REQUEST,
        SERVER_ERROR,
        API_UNAVAILABLE,
        MALFORMED_RESPONSE,
        NETWORK_ERROR
    }

    private static final class PikaStatsException extends RuntimeException {
        private final ErrorKind kind;

        private PikaStatsException(ErrorKind kind, int statusCode) {
            super(statusCode > 0 ? "PikaNetwork API returned HTTP " + statusCode : kind.name());
            this.kind = kind;
        }

        private PikaStatsException(ErrorKind kind, int statusCode, Throwable cause) {
            super(statusCode > 0 ? "PikaNetwork API returned HTTP " + statusCode : kind.name(), cause);
            this.kind = kind;
        }
    }
}

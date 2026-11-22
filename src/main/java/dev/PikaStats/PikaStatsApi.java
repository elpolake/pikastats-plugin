package dev.PikaStats;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * PikaNetwork stats, profile and punishments client. JSON API calls go through {@link ApiKeyPool}
 * (key rotation, quotas, priorities); results are cached per scope and served stale when the API
 * is rate limiting or down.
 */
final class PikaStatsApi {
    private static final String API_ROOT = "https://stats.pika-network.net/api/profile/";
    private static final String PUNISHMENTS_ROOT = "https://pika-network.net/bans/search/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "ViaProxy-PikaStats/" + PikaStatsPlugin.VERSION;
    static final ApiKeyPool.Priority DEFAULT_PRIORITY = ApiKeyPool.Priority.HIGH;

    private final HttpClient client;
    private final Executor executor;
    private final ApiKeyPool pool;
    private final PikaStatsCache<String, StatsResult> statsCache = new PikaStatsCache<>(Duration.ofMinutes(10));
    private final PikaStatsCache<String, ProfileResult> profileCache = new PikaStatsCache<>(Duration.ofMinutes(30));
    private final PikaStatsCache<String, PunishmentsResult> punishmentsCache =
            new PikaStatsCache<>(Duration.ofMinutes(15));
    /** Overlay rows only need the first page (it holds the newest, i.e. any active, punishments). */
    private final PikaStatsCache<String, PunishmentsResult> quickPunishmentsCache =
            new PikaStatsCache<>(Duration.ofMinutes(20));
    /** The bans website rate limits bursts: one page at a time, a short gap, and a pause after a 429. */
    private final Object htmlLock = new Object();
    private CompletableFuture<?> htmlTail = CompletableFuture.completedFuture(null);
    private volatile long htmlPausedUntil;

    PikaStatsApi(HttpClient client, Executor executor, ApiKeyPool pool) {
        this.client = client;
        this.executor = executor;
        this.pool = pool;
    }

    ApiKeyPool pool() {
        return this.pool;
    }

    boolean hasApiKey() {
        return this.pool.hasKeys();
    }

    long requestsSent() {
        return this.pool.sent();
    }

    long cacheHits() {
        return this.statsCache.hits() + this.profileCache.hits() + this.punishmentsCache.hits();
    }

    long cacheMisses() {
        return this.statsCache.misses() + this.profileCache.misses() + this.punishmentsCache.misses();
    }

    long staleServed() {
        return this.statsCache.staleServed() + this.profileCache.staleServed();
    }

    void clearCaches() {
        this.statsCache.clear();
        this.profileCache.clear();
        this.punishmentsCache.clear();
        this.quickPunishmentsCache.clear();
    }

    /** Lifetime numbers barely move during a session; short periods change faster. */
    static Duration statsTtl(StatsPeriod period) {
        return switch (period) {
            case ALL_TIME, YEARLY -> Duration.ofMinutes(10);
            case MONTHLY -> Duration.ofMinutes(5);
            case WEEKLY -> Duration.ofMinutes(3);
        };
    }

    CompletableFuture<StatsResult> lookup(StatsRequest request) {
        return lookup(request, DEFAULT_PRIORITY);
    }

    CompletableFuture<StatsResult> lookup(StatsRequest request, ApiKeyPool.Priority priority) {
        if (!PartyStore.isValidUsername(request.username())) {
            return notFound();
        }
        return this.statsCache.get(request.cacheKey(), statsTtl(request.period()), () -> fetchStats(request, priority));
    }

    CompletableFuture<StatsResult> refresh(StatsRequest request, ApiKeyPool.Priority priority) {
        if (!PartyStore.isValidUsername(request.username())) {
            return notFound();
        }
        return this.statsCache.refresh(request.cacheKey(), statsTtl(request.period()), () -> fetchStats(request, priority));
    }

    CompletableFuture<ProfileResult> lookupProfile(String username) {
        return lookupProfile(username, DEFAULT_PRIORITY);
    }

    CompletableFuture<ProfileResult> lookupProfile(String username, ApiKeyPool.Priority priority) {
        if (!PartyStore.isValidUsername(username)) {
            return notFound();
        }
        return this.profileCache.get(username.toLowerCase(Locale.ROOT), () -> fetchProfile(username, priority, null));
    }

    CompletableFuture<PunishmentsResult> lookupPunishments(String username) {
        if (!PartyStore.isValidUsername(username)) {
            return notFound();
        }
        return this.punishmentsCache.get(username.toLowerCase(Locale.ROOT), () -> fetchPunishments(username));
    }

    /** Only the first bans page: enough for active punishments in the overlay, 1 request instead of 4. */
    CompletableFuture<PunishmentsResult> lookupActivePunishments(String username) {
        if (!PartyStore.isValidUsername(username)) {
            return notFound();
        }
        return this.quickPunishmentsCache.get(username.toLowerCase(Locale.ROOT), () ->
                getHtml(URI.create(PUNISHMENTS_ROOT + encode(username) + "/")).thenApplyAsync(response -> {
                    requireSuccess(response);
                    try {
                        return PikaStatsPunishmentsParser.parse(response.body());
                    } catch (PikaStatsPunishmentsParser.PlayerNotFoundException exception) {
                        throw new PikaStatsException(ErrorKind.PLAYER_NOT_FOUND, response.statusCode(), exception);
                    } catch (RuntimeException exception) {
                        throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode(), exception);
                    }
                }, this.executor));
    }

    /** Checks one key with a profile request; used by the GUI's Test button. */
    CompletableFuture<ProfileResult> testKey(ApiKeyPool.Key key, String username) {
        return fetchProfile(username, ApiKeyPool.Priority.HIGH, key);
    }

    private static <T> CompletableFuture<T> notFound() {
        return CompletableFuture.failedFuture(new PikaStatsException(ErrorKind.PLAYER_NOT_FOUND, 0));
    }

    private CompletableFuture<StatsResult> fetchStats(StatsRequest request, ApiKeyPool.Priority priority) {
        String uri = API_ROOT + encode(request.username()) + "/leaderboard?type=" + request.game().apiValue()
                + "&interval=" + request.period().apiValue() + "&mode=" + request.mode().apiValue();
        return this.pool.submit(jsonRequest(URI.create(uri)), priority).thenApplyAsync(response -> {
            requireStatsSuccess(response);
            try {
                return StatsResult.parse(request.username(), request.game(), response.body());
            } catch (RuntimeException exception) {
                throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode(), exception);
            }
        }, this.executor);
    }

    private CompletableFuture<ProfileResult> fetchProfile(String username, ApiKeyPool.Priority priority,
                                                          ApiKeyPool.Key fixedKey) {
        return this.pool.submit(jsonRequest(URI.create(API_ROOT + encode(username))), priority, fixedKey)
                .thenApplyAsync(response -> {
                    requireStatsSuccess(response);
                    try {
                        return ProfileResult.parse(username, response.body());
                    } catch (RuntimeException exception) {
                        throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, response.statusCode(), exception);
                    }
                }, this.executor);
    }

    private CompletableFuture<PunishmentsResult> fetchPunishments(String username) {
        String root = PUNISHMENTS_ROOT + encode(username) + "/";
        CompletableFuture<HttpResponse<String>> all = getHtml(URI.create(root));
        CompletableFuture<HttpResponse<String>> bans = getHtml(URI.create(root + "?filter=bans"));
        CompletableFuture<HttpResponse<String>> mutes = getHtml(URI.create(root + "?filter=mutes"));
        CompletableFuture<HttpResponse<String>> warnings = getHtml(URI.create(root + "?filter=warnings"));
        return CompletableFuture.allOf(all, bans, mutes, warnings).thenApplyAsync(ignored -> {
            HttpResponse<String> allResponse = all.join();
            requireSuccess(allResponse);
            requireSuccess(bans.join());
            requireSuccess(mutes.join());
            requireSuccess(warnings.join());
            try {
                return PunishmentsResult.combine(
                        PikaStatsPunishmentsParser.parse(allResponse.body()),
                        PikaStatsPunishmentsParser.parse(bans.join().body()),
                        PikaStatsPunishmentsParser.parse(mutes.join().body()),
                        PikaStatsPunishmentsParser.parse(warnings.join().body()));
            } catch (PikaStatsPunishmentsParser.PlayerNotFoundException exception) {
                throw new PikaStatsException(ErrorKind.PLAYER_NOT_FOUND, allResponse.statusCode(), exception);
            } catch (RuntimeException exception) {
                throw new PikaStatsException(ErrorKind.MALFORMED_RESPONSE, allResponse.statusCode(), exception);
            }
        }, this.executor);
    }

    private static HttpRequest.Builder jsonRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET();
    }

    private CompletableFuture<HttpResponse<String>> getHtml(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        synchronized (this.htmlLock) {
            CompletableFuture<HttpResponse<String>> result = this.htmlTail.handle((ignored, error) -> null)
                    .thenCompose(ignored -> {
                        long wait = Math.max(150, this.htmlPausedUntil - System.currentTimeMillis());
                        return CompletableFuture.supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(wait, java.util.concurrent.TimeUnit.MILLISECONDS, this.executor));
                    })
                    .thenCompose(ignored -> this.client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                    .thenApply(response -> {
                        if (response.statusCode() == 429) {
                            this.htmlPausedUntil = System.currentTimeMillis() + 15_000;
                        }
                        return response;
                    });
            this.htmlTail = result;
            return result;
        }
    }

    private static String encode(String username) {
        return URLEncoder.encode(username, StandardCharsets.UTF_8);
    }

    private static void requireStatsSuccess(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 400 || status == 404) {
            throw new PikaStatsException(ErrorKind.PLAYER_NOT_FOUND, status);
        }
        requireSuccess(response);
    }

    private static void requireSuccess(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        throw new PikaStatsException(switch (status) {
            case 404 -> ErrorKind.PLAYER_NOT_FOUND;
            case 401, 403 -> ErrorKind.AUTH_REQUIRED;
            case 429 -> ErrorKind.RATE_LIMITED;
            case 400 -> ErrorKind.BAD_REQUEST;
            default -> status >= 500 ? ErrorKind.SERVER_ERROR : ErrorKind.API_UNAVAILABLE;
        }, status);
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

    static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    enum ErrorKind {
        PLAYER_NOT_FOUND("Player not found"),
        AUTH_REQUIRED("API key missing or invalid"),
        RATE_LIMITED("Rate limited, try again soon"),
        BAD_REQUEST("Query rejected"),
        SERVER_ERROR("PikaNetwork API error"),
        API_UNAVAILABLE("API unavailable"),
        MALFORMED_RESPONSE("Unexpected API response"),
        NETWORK_ERROR("Network error");

        private final String message;

        ErrorKind(String message) {
            this.message = message;
        }

        String message() {
            return this.message;
        }
    }

    static final class PikaStatsException extends RuntimeException {
        private final ErrorKind kind;

        PikaStatsException(ErrorKind kind, int statusCode) {
            super(statusCode > 0 ? "PikaNetwork returned HTTP " + statusCode : kind.name());
            this.kind = kind;
        }

        PikaStatsException(ErrorKind kind, int statusCode, Throwable cause) {
            super(statusCode > 0 ? "PikaNetwork returned HTTP " + statusCode : kind.name(), cause);
            this.kind = kind;
        }
    }
}

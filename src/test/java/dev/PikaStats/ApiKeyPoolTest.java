package dev.PikaStats;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the pool against a local server that enforces a per-key quota like the real API. */
class ApiKeyPoolTest {
    private static final int LIMIT = 10;
    private HttpServer server;
    private final Map<String, AtomicInteger> usage = new ConcurrentHashMap<>();
    private final List<String> order = new CopyOnWriteArrayList<>();
    private ApiKeyPool pool;

    @BeforeEach
    void start() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("X-Api-Key");
            this.order.add(exchange.getRequestURI().getPath());
            int status;
            int used = 0;
            if ("bad-key-000".equals(key)) {
                status = 401;
            } else {
                used = this.usage.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                status = used > LIMIT ? 429 : 200;
            }
            exchange.getResponseHeaders().add("x-ratelimit-limit", Integer.toString(LIMIT));
            exchange.getResponseHeaders().add("x-ratelimit-remaining", Integer.toString(Math.max(0, LIMIT - used)));
            exchange.getResponseHeaders().add("x-ratelimit-reset", "30");
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        this.server.start();
        this.pool = new ApiKeyPool(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    @AfterEach
    void stop() {
        this.pool.close();
        this.server.stop(0);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + path)).GET();
    }

    private static int status(CompletableFuture<HttpResponse<String>> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS).statusCode();
    }

    @Test
    void spreadsRequestsOverKeysWithoutEverHittingTheLimit() throws Exception {
        this.pool.setKeys(List.of("key-aaaa-1111", "key-bbbb-2222"));
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int index = 0; index < 2 * LIMIT; index++) {
            futures.add(this.pool.submit(request("/r" + index), ApiKeyPool.Priority.HIGH));
        }
        for (CompletableFuture<HttpResponse<String>> future : futures) {
            assertEquals(200, status(future));
        }
        assertEquals(LIMIT, this.usage.get("key-aaaa-1111").get());
        assertEquals(LIMIT, this.usage.get("key-bbbb-2222").get());
        assertEquals(0, this.pool.totalRemaining());
        // A 21st request must wait for the window instead of being sent and rate limited.
        CompletableFuture<HttpResponse<String>> extra = this.pool.submit(request("/extra"), ApiKeyPool.Priority.HIGH);
        Thread.sleep(400);
        assertFalse(extra.isDone());
        assertTrue(this.usage.values().stream().allMatch(count -> count.get() <= LIMIT));
    }

    @Test
    void backgroundRequestsLeaveAReserveForCommands() throws Exception {
        this.pool.setKeys(List.of("key-cccc-3333"));
        List<CompletableFuture<HttpResponse<String>>> background = new ArrayList<>();
        for (int index = 0; index < LIMIT; index++) {
            background.add(this.pool.submit(request("/low" + index), ApiKeyPool.Priority.LOW));
        }
        Thread.sleep(500);
        long sentLow = background.stream().filter(CompletableFuture::isDone).count();
        // LOW keeps 4 of 10 requests free; with requests in flight it may hold back one more, never fewer.
        assertTrue(sentLow >= LIMIT - 5 && sentLow <= LIMIT - 4, "sent " + sentLow + " background requests");
        // A command still goes through immediately.
        assertEquals(200, status(this.pool.submit(request("/command"), ApiKeyPool.Priority.HIGH)));
    }

    @Test
    void highPriorityOvertakesQueuedBackgroundWork() throws Exception {
        this.pool.setKeys(List.of("key-dddd-4444"));
        for (int index = 0; index < 20; index++) {
            this.pool.submit(request("/low" + index), ApiKeyPool.Priority.LOW);
        }
        CompletableFuture<HttpResponse<String>> command = this.pool.submit(request("/cmd"), ApiKeyPool.Priority.HIGH);
        assertEquals(200, status(command));
        assertTrue(this.order.indexOf("/cmd") < this.order.size(), this.order.toString());
        assertTrue(this.order.indexOf("/cmd") <= 8, "command should not wait behind the whole lobby: " + this.order);
    }

    @Test
    void invalidKeysAreSkipped() throws Exception {
        this.pool.setKeys(List.of("bad-key-000", "key-eeee-5555"));
        for (int index = 0; index < 4; index++) {
            status(this.pool.submit(request("/x" + index), ApiKeyPool.Priority.HIGH));
        }
        ApiKeyPool.Key bad = this.pool.keys().get(0);
        assertTrue(bad.invalid);
        assertEquals("INVALID", bad.state());
        assertTrue(this.usage.get("key-eeee-5555").get() >= 3);
    }

    @Test
    void cacheServesStaleValueWhenRefreshFails() throws Exception {
        PikaStatsCache<String, String> cache = new PikaStatsCache<>(Duration.ofMillis(50));
        assertEquals("old", cache.get("k", () -> CompletableFuture.completedFuture("old")).get());
        Thread.sleep(80);
        String value = cache.get("k", () -> CompletableFuture.failedFuture(
                new PikaStatsApi.PikaStatsException(PikaStatsApi.ErrorKind.RATE_LIMITED, 429))).get();
        assertEquals("old", value);
        assertEquals(1, cache.staleServed());
        // Failures without an old value are not cached.
        assertTrue(cache.get("new", () -> CompletableFuture.failedFuture(new RuntimeException("x")))
                .isCompletedExceptionally());
        assertEquals("fresh", cache.get("new", () -> CompletableFuture.completedFuture("fresh")).get());
    }
}

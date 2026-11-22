package dev.PikaStats;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spreads PikaNetwork API requests over several API keys and never sends a request a key has no
 * quota for. Quotas come from the {@code x-ratelimit-*} headers of every response. Requests wait in
 * a priority queue: your own chat commands first, overlay clicks next, background overlay loading
 * last. Background requests also leave a reserve of each key's quota untouched so a command you
 * type is answered at once even while a full lobby is loading.
 */
final class ApiKeyPool implements AutoCloseable {
    enum Priority {
        /** A command the player typed. */
        HIGH,
        /** The player clicked something in the overlay. */
        NORMAL,
        /** Background loading (lobby roster, profiles, refreshes). */
        LOW
    }

    static final int DEFAULT_LIMIT = 120;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_IN_FLIGHT = 6;

    private final HttpClient client;
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final Thread dispatcher;
    private volatile List<Key> keys = List.of();
    private volatile boolean running = true;

    ApiKeyPool(HttpClient client) {
        this.client = client;
        this.dispatcher = new Thread(this::dispatchLoop, "PikaStats-API-Dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    // ---------------------------------------------------------------- keys

    /** Replaces the key list, keeping usage counters of keys that stay. */
    synchronized void setKeys(List<String> values) {
        List<Key> updated = new ArrayList<>();
        for (String value : new LinkedHashSet<>(values)) {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Key existing = this.keys.stream().filter(key -> key.value.equals(trimmed)).findFirst().orElse(null);
            updated.add(existing != null ? existing : new Key(trimmed));
        }
        this.keys = List.copyOf(updated);
    }

    List<Key> keys() {
        return this.keys;
    }

    boolean hasKeys() {
        return !this.keys.isEmpty();
    }

    int queued() {
        return this.queue.size();
    }

    long sent() {
        return this.sent.get();
    }

    /** Requests per minute all usable keys allow together. */
    int totalLimit() {
        return this.keys.stream().filter(key -> !key.invalid).mapToInt(key -> key.limit).sum();
    }

    int totalRemaining() {
        long now = System.currentTimeMillis();
        return this.keys.stream().filter(key -> key.usable(now)).mapToInt(key -> key.remaining(now)).sum();
    }

    // ---------------------------------------------------------------- requests

    /** Queues a request; the API key header is added when a key with quota is free. */
    CompletableFuture<HttpResponse<String>> submit(HttpRequest.Builder request, Priority priority) {
        return submit(request, priority, null);
    }

    /** Sends with one specific key (used by the "Test" button). */
    CompletableFuture<HttpResponse<String>> submit(HttpRequest.Builder request, Priority priority, Key fixedKey) {
        Job job = new Job(request, priority, this.sequence.incrementAndGet(), fixedKey);
        if (fixedKey == null && this.keys.isEmpty()) {
            job.future.completeExceptionally(
                    new PikaStatsApi.PikaStatsException(PikaStatsApi.ErrorKind.AUTH_REQUIRED, 0));
            return job.future;
        }
        this.queue.add(job);
        return job.future;
    }

    private void dispatchLoop() {
        while (this.running) {
            Job job;
            try {
                job = this.queue.take();
            } catch (InterruptedException interrupted) {
                return;
            }
            Key key = job.fixedKey != null ? job.fixedKey.reserveForced() : reserve(job.priority);
            if (key == null) {
                if (this.keys.isEmpty() || this.keys.stream().allMatch(item -> item.invalid)) {
                    job.future.completeExceptionally(
                            new PikaStatsApi.PikaStatsException(PikaStatsApi.ErrorKind.AUTH_REQUIRED, 0));
                    continue;
                }
                // Put it back and wait until some key has quota (higher priorities can overtake).
                this.queue.add(job);
                sleep(Math.max(50, Math.min(1_000, waitMillis(job.priority))));
                continue;
            }
            try {
                this.inFlight.acquire();
            } catch (InterruptedException interrupted) {
                return;
            }
            this.sent.incrementAndGet();
            key.used.incrementAndGet();
            HttpRequest request = job.request.header("X-Api-Key", key.value).build();
            this.client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
                this.inFlight.release();
                if (error != null) {
                    key.refund();
                    job.future.completeExceptionally(error);
                    return;
                }
                key.update(response);
                job.future.complete(response);
            });
        }
    }

    private synchronized Key reserve(Priority priority) {
        long now = System.currentTimeMillis();
        Key best = null;
        int bestRemaining = 0;
        for (Key key : this.keys) {
            if (!key.usable(now)) {
                continue;
            }
            int remaining = key.remaining(now);
            int reserve = reserveFor(priority, key);
            if (remaining - reserve > bestRemaining) {
                best = key;
                bestRemaining = remaining - reserve;
            }
        }
        if (best != null) {
            best.reserve(now);
        }
        return best;
    }

    /** Part of each key's quota a priority may not touch, kept free for more important requests. */
    private static int reserveFor(Priority priority, Key key) {
        return switch (priority) {
            case HIGH -> 0;
            case NORMAL -> Math.max(1, key.limit / 20);
            case LOW -> Math.max(4, key.limit / 6);
        };
    }

    /** How long until any key can take a request of this priority. */
    long waitMillis(Priority priority) {
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        for (Key key : this.keys) {
            if (key.invalid) {
                continue;
            }
            long ready;
            if (now < key.coolUntil) {
                ready = key.coolUntil;
            } else if (key.remaining(now) > reserveFor(priority, key)) {
                ready = now;
            } else {
                ready = key.windowEnd;
            }
            best = Math.min(best, Math.max(0, ready - now));
        }
        return best == Long.MAX_VALUE ? 1_000 : best;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        this.running = false;
        this.dispatcher.interrupt();
        Job job;
        while ((job = this.queue.poll()) != null) {
            job.future.cancel(false);
        }
    }

    // ---------------------------------------------------------------- types

    private static final class Job implements Comparable<Job> {
        private final HttpRequest.Builder request;
        private final Priority priority;
        private final long order;
        private final Key fixedKey;
        private final CompletableFuture<HttpResponse<String>> future = new CompletableFuture<>();

        private Job(HttpRequest.Builder request, Priority priority, long order, Key fixedKey) {
            this.request = request;
            this.priority = priority;
            this.order = order;
            this.fixedKey = fixedKey;
        }

        @Override
        public int compareTo(Job other) {
            int byPriority = Integer.compare(this.priority.ordinal(), other.priority.ordinal());
            return byPriority != 0 ? byPriority : Long.compare(this.order, other.order);
        }
    }

    /** One API key and what we know about its quota. */
    static final class Key {
        final String value;
        final AtomicLong used = new AtomicLong();
        final AtomicLong rateLimited = new AtomicLong();
        volatile int limit = DEFAULT_LIMIT;
        volatile boolean invalid;
        volatile boolean verified;
        volatile String lastStatus = "untested";
        /** False until a response told us this key's real limit; until then only probe gently. */
        private volatile boolean limitKnown;
        private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        private volatile int remaining = DEFAULT_LIMIT;
        /** Requests this process sent with the key in the current window (the ground truth for us). */
        private int sentInWindow;
        private volatile long windowEnd;
        private volatile long coolUntil;

        private Key(String value) {
            this.value = value;
        }

        String masked() {
            return this.value.length() <= 8 ? "****" : this.value.substring(0, 4) + "••••••" + this.value.substring(this.value.length() - 4);
        }

        boolean usable(long now) {
            return !this.invalid && now >= this.coolUntil && (this.limitKnown || this.inFlight.get() < 2);
        }

        int remaining(long now) {
            return now >= this.windowEnd ? this.limit : this.remaining;
        }

        long resetsInMillis() {
            return Math.max(0, Math.max(this.windowEnd, this.coolUntil) - System.currentTimeMillis());
        }

        String state() {
            long now = System.currentTimeMillis();
            if (this.invalid) {
                return "INVALID";
            }
            if (now < this.coolUntil) {
                return "COOLING";
            }
            if (remaining(now) <= 0) {
                return "EMPTY";
            }
            return this.verified ? "OK" : "READY";
        }

        private synchronized void reserve(long now) {
            if (now >= this.windowEnd) {
                this.windowEnd = now + WINDOW_MILLIS;
                this.remaining = this.limit;
                this.sentInWindow = 0;
            }
            this.sentInWindow++;
            this.remaining = Math.max(0, Math.min(this.remaining - 1, this.limit - this.sentInWindow));
            this.inFlight.incrementAndGet();
        }

        private synchronized Key reserveForced() {
            reserve(System.currentTimeMillis());
            return this;
        }

        private synchronized void refund() {
            this.sentInWindow = Math.max(0, this.sentInWindow - 1);
            this.remaining = Math.min(this.limit - this.sentInWindow, this.remaining + 1);
            this.inFlight.decrementAndGet();
        }

        private synchronized void update(HttpResponse<?> response) {
            int status = response.statusCode();
            long now = System.currentTimeMillis();
            this.inFlight.decrementAndGet();
            header(response, "x-ratelimit-limit").ifPresent(value -> {
                this.limit = (int) Math.max(1, value);
                this.limitKnown = true;
            });
            Optional<Long> remainingHeader = header(response, "x-ratelimit-remaining");
            Optional<Long> resetHeader = header(response, "x-ratelimit-reset");
            // The server's count wins, but never forget requests we reserved that are still in flight.
            // Two upper bounds, take the smaller: the limit minus everything we sent this window (exact for
            // us, includes requests still in flight) and the server's count (which also sees other programs
            // using the same key). Never adopt a server count that is higher than ours.
            int ours = Math.max(0, this.limit - this.sentInWindow);
            this.remaining = remainingHeader.map(value -> (int) Math.min(ours, Math.max(0, value))).orElse(Math.min(this.remaining, ours));
            resetHeader.ifPresent(seconds -> this.windowEnd = now + TimeUnit.SECONDS.toMillis(Math.max(1, seconds)));
            if (status < 500) {
                this.limitKnown = true; // No header means the documented default applies.
            }
            if (status == 429) {
                this.rateLimited.incrementAndGet();
                long wait = header(response, "retry-after").orElse(resetHeader.orElse(30L));
                this.coolUntil = now + TimeUnit.SECONDS.toMillis(Math.max(1, wait));
                this.remaining = 0;
                this.lastStatus = "rate limited";
            } else if (status == 401 || status == 403) {
                this.invalid = true;
                this.lastStatus = "rejected (HTTP " + status + ")";
            } else if (status < 500) {
                this.verified = true;
                this.invalid = false;
                this.lastStatus = "OK";
            } else {
                this.lastStatus = "server error " + status;
            }
        }

        private static Optional<Long> header(HttpResponse<?> response, String name) {
            try {
                return response.headers().firstValue(name).map(String::trim).map(Long::parseLong);
            } catch (NumberFormatException error) {
                return Optional.empty();
            }
        }
    }
}

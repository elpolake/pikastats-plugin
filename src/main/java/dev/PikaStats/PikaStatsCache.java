package dev.PikaStats;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Async cache. Concurrent requests for the same key share one HTTP call, finished values live for
 * their TTL, and when a refresh fails (rate limit, network) the last good value is served instead
 * of an error for up to {@link #STALE_LIMIT}.
 */
final class PikaStatsCache<K, V> {
    private static final int MAX_ENTRIES = 3_000;
    private static final long STALE_LIMIT = Duration.ofHours(2).toNanos();

    private final long defaultTtlNanos;
    private final ConcurrentMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong staleServed = new AtomicLong();

    PikaStatsCache(Duration ttl) {
        this.defaultTtlNanos = ttl.toNanos();
    }

    CompletableFuture<V> get(K key, Supplier<CompletableFuture<V>> loader) {
        return get(key, this.defaultTtlNanos, loader);
    }

    CompletableFuture<V> get(K key, Duration ttl, Supplier<CompletableFuture<V>> loader) {
        return get(key, ttl.toNanos(), loader);
    }

    private CompletableFuture<V> get(K key, long ttlNanos, Supplier<CompletableFuture<V>> loader) {
        prune();
        boolean[] hit = {true};
        Entry<V> entry = this.entries.compute(key, (ignored, existing) -> {
            if (existing != null && (!existing.future.isDone() || existing.expiresAtNanos > System.nanoTime())) {
                return existing;
            }
            hit[0] = false;
            return load(key, ttlNanos, loader, existing);
        });
        (hit[0] ? this.hits : this.misses).incrementAndGet();
        return entry.future;
    }

    /** Ignores a finished cached value, but still joins a request already in flight. */
    CompletableFuture<V> refresh(K key, Duration ttl, Supplier<CompletableFuture<V>> loader) {
        Entry<V> entry = this.entries.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.future.isDone()) {
                return existing;
            }
            return load(key, ttl.toNanos(), loader, existing);
        });
        this.misses.incrementAndGet();
        return entry.future;
    }

    void clear() {
        this.entries.clear();
    }

    long hits() {
        return this.hits.get();
    }

    long misses() {
        return this.misses.get();
    }

    long staleServed() {
        return this.staleServed.get();
    }

    private Entry<V> load(K key, long ttlNanos, Supplier<CompletableFuture<V>> loader, Entry<V> previous) {
        V stale = previous == null ? null : previous.lastValue;
        long staleAt = previous == null ? 0 : previous.lastValueAt;
        CompletableFuture<V> loaded;
        try {
            loaded = loader.get();
        } catch (Throwable error) {
            loaded = CompletableFuture.failedFuture(error);
        }
        CompletableFuture<V> withFallback = loaded.handle((value, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(value);
            }
            boolean usable = stale != null && System.nanoTime() - staleAt < STALE_LIMIT
                    && PikaStatsApi.errorKind(error) != PikaStatsApi.ErrorKind.PLAYER_NOT_FOUND;
            if (usable) {
                this.staleServed.incrementAndGet();
                return CompletableFuture.completedFuture(stale);
            }
            return CompletableFuture.<V>failedFuture(error);
        }).thenCompose(future -> future);
        Entry<V> entry = new Entry<>(withFallback, stale, staleAt);
        loaded.whenComplete((value, error) -> {
            if (error != null) {
                if (stale != null) {
                    // Retry soon, but keep serving the old value until then.
                    entry.expiresAtNanos = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                } else {
                    // Expire immediately (this can run before the entry is even stored) and drop it.
                    entry.expiresAtNanos = 0;
                    this.entries.remove(key, entry);
                }
            } else {
                entry.lastValue = value;
                entry.lastValueAt = System.nanoTime();
                entry.expiresAtNanos = System.nanoTime() + ttlNanos;
            }
        });
        return entry;
    }

    private void prune() {
        if (this.entries.size() < MAX_ENTRIES) {
            return;
        }
        long now = System.nanoTime();
        this.entries.entrySet().removeIf(item -> item.getValue().future.isDone()
                && now - item.getValue().lastValueAt > STALE_LIMIT);
    }

    private static final class Entry<V> {
        private final CompletableFuture<V> future;
        private volatile long expiresAtNanos = Long.MAX_VALUE;
        private volatile V lastValue;
        private volatile long lastValueAt;

        private Entry(CompletableFuture<V> future, V lastValue, long lastValueAt) {
            this.future = future;
            this.lastValue = lastValue;
            this.lastValueAt = lastValueAt;
        }
    }
}

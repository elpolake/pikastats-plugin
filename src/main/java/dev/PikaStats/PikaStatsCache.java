package dev.PikaStats;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

final class PikaStatsCache {
    private final long ttlNanos;
    private final ConcurrentMap<String, CacheEntry<String>> canonicalNames = new ConcurrentHashMap<>();
    private final ConcurrentMap<StatsKey, CacheEntry<StatsResult>> stats = new ConcurrentHashMap<>();

    PikaStatsCache(Duration ttl) {
        this.ttlNanos = ttl.toNanos();
    }

    CompletableFuture<String> canonicalName(String requestedName, Supplier<CompletableFuture<String>> loader) {
        String key = requestedName.toLowerCase(Locale.ROOT);
        return getOrLoad(this.canonicalNames, key, loader);
    }

    CompletableFuture<StatsResult> stats(String canonicalName, StatsPeriod period, BedWarsMode mode,
                                         Supplier<CompletableFuture<StatsResult>> loader) {
        StatsKey key = new StatsKey(canonicalName, period, mode);
        return getOrLoad(this.stats, key, loader);
    }

    CompletableFuture<StatsResult> refreshStats(String canonicalName, StatsPeriod period, BedWarsMode mode,
                                                 Supplier<CompletableFuture<StatsResult>> loader) {
        StatsKey key = new StatsKey(canonicalName, period, mode);
        CacheEntry<StatsResult> entry = this.stats.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.future.isDone()) {
                return existing;
            }

            CompletableFuture<StatsResult> future;
            try {
                future = loader.get();
            } catch (Throwable error) {
                future = CompletableFuture.failedFuture(error);
            }
            return new CacheEntry<>(future, System.nanoTime() + this.ttlNanos);
        });
        entry.future.whenComplete((value, error) -> {
            if (error != null) {
                this.stats.remove(key, entry);
            }
        });
        return entry.future;
    }

    private <K, V> CompletableFuture<V> getOrLoad(ConcurrentMap<K, CacheEntry<V>> cache, K key,
                                                   Supplier<CompletableFuture<V>> loader) {
        CacheEntry<V> entry = cache.compute(key, (ignored, existing) -> {
            long now = System.nanoTime();
            if (existing != null && existing.expiresAtNanos > now) {
                return existing;
            }

            CompletableFuture<V> future;
            try {
                future = loader.get();
            } catch (Throwable error) {
                future = CompletableFuture.failedFuture(error);
            }
            return new CacheEntry<>(future, now + this.ttlNanos);
        });
        entry.future.whenComplete((value, error) -> {
            if (error != null) {
                cache.remove(key, entry);
            }
        });
        return entry.future;
    }

    private record CacheEntry<V>(CompletableFuture<V> future, long expiresAtNanos) {
    }

    private record StatsKey(String canonicalName, StatsPeriod period, BedWarsMode mode) {
    }
}

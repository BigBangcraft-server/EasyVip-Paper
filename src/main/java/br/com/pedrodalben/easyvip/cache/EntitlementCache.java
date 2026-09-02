package br.com.pedrodalben.easyvip.cache;

import br.com.pedrodalben.easyvip.api.EffectiveEntitlementView;
import br.com.pedrodalben.easyvip.api.ScopeContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Bounded, TTL, thread-safe cache for already-resolved entitlement views. */
public final class EntitlementCache {
    private final Cache<CacheKey, Entry> cache;
    private final Cache<UUID, Long> observedVersions;
    private final Cache<UUID, Long> invalidationGenerations;
    private final AtomicLong generationSequence = new AtomicLong();
    private final AtomicLong allInvalidationEpoch = new AtomicLong();

    public EntitlementCache(long maximumEntries, Duration ttl) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("ttl must be positive");
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumEntries)
                .expireAfterWrite(ttl)
                .recordStats()
                .build();
        this.observedVersions = Caffeine.newBuilder().maximumSize(maximumEntries).build();
        this.invalidationGenerations = Caffeine.newBuilder().maximumSize(maximumEntries).build();
    }

    public EffectiveEntitlementView get(UUID playerUuid, ScopeContext context, Supplier<EffectiveEntitlementView> loader) {
        CacheKey key = new CacheKey(playerUuid, context);
        Entry entry = cache.getIfPresent(key);
        Long observedVersion = observedVersions.getIfPresent(playerUuid);
        long observed = observedVersion == null ? 0L : observedVersion;
        Long generationValue = invalidationGenerations.getIfPresent(playerUuid);
        long generation = generationValue == null ? 0L : generationValue;
        long epoch = allInvalidationEpoch.get();
        if (entry != null && entry.epoch() == epoch && entry.generation() == generation
                && entry.version() >= observed) {
            return entry.view();
        }
        EffectiveEntitlementView loaded = Objects.requireNonNull(loader.get(), "loader result");
        long currentGeneration = generationValue(playerUuid);
        if (currentGeneration == generation && allInvalidationEpoch.get() == epoch) {
            cache.put(key, new Entry(loaded, observed, epoch, generation));
        }
        return loaded;
    }

    public void put(EffectiveEntitlementView view, long aggregateVersion) {
        Objects.requireNonNull(view, "view");
        if (aggregateVersion < 0) throw new IllegalArgumentException("aggregateVersion cannot be negative");
        Long previous = observedVersions.getIfPresent(view.playerUuid());
        long version = previous == null ? aggregateVersion : Math.max(previous, aggregateVersion);
        observedVersions.put(view.playerUuid(), version);
        long generation = nextGeneration(view.playerUuid());
        cache.put(new CacheKey(view.playerUuid(), view.context()),
                new Entry(view, version, allInvalidationEpoch.get(), generation));
    }

    /** Invalidate only entries older than a newer authoritative event. Version zero means unknown. */
    public void invalidate(UUID playerUuid, long aggregateVersion) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (aggregateVersion < 0) throw new IllegalArgumentException("aggregateVersion cannot be negative");
        Long previous = observedVersions.getIfPresent(playerUuid);
        if (aggregateVersion > 0 && previous != null && aggregateVersion <= previous) return;
        if (aggregateVersion > 0) observedVersions.put(playerUuid, aggregateVersion);
        nextGeneration(playerUuid);
        cache.asMap().keySet().removeIf(key -> key.playerUuid().equals(playerUuid));
    }

    public void invalidateAll() {
        allInvalidationEpoch.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1L : previous + 1L);
        cache.invalidateAll();
        observedVersions.invalidateAll();
        invalidationGenerations.invalidateAll();
    }

    private long generationValue(UUID playerUuid) {
        Long generation = invalidationGenerations.getIfPresent(playerUuid);
        return generation == null ? 0L : generation;
    }

    private long nextGeneration(UUID playerUuid) {
        long next = generationSequence.updateAndGet(previous -> previous == Long.MAX_VALUE ? 1L : previous + 1L);
        invalidationGenerations.put(playerUuid, next);
        return next;
    }

    public long estimatedSize() { return cache.estimatedSize(); }
    public CacheStats stats() { return cache.stats(); }

    public record CacheKey(UUID playerUuid, ScopeContext context) {
        public CacheKey {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(context, "context");
        }
    }

    private record Entry(EffectiveEntitlementView view, long version, long epoch, long generation) { }
}

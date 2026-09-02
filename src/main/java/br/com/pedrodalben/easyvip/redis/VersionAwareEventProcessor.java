package br.com.pedrodalben.easyvip.redis;

import br.com.pedrodalben.easyvip.api.DomainEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Drops duplicate and stale aggregate events before they can invalidate newer state. */
public final class VersionAwareEventProcessor {
    private final Cache<UUID, Long> aggregateVersions;
    private final Cache<UUID, Boolean> eventIds;
    private final Consumer<DomainEvent> consumer;
    private final RedisMetrics metrics;

    public VersionAwareEventProcessor(long maximumAggregates, long maximumEvents,
                                      Consumer<DomainEvent> consumer, RedisMetrics metrics) {
        if (maximumAggregates < 1 || maximumEvents < 1) throw new IllegalArgumentException("event cache sizes must be positive");
        this.aggregateVersions = Caffeine.newBuilder().maximumSize(maximumAggregates).build();
        this.eventIds = Caffeine.newBuilder().maximumSize(maximumEvents).build();
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public synchronized boolean accept(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        if (eventIds.getIfPresent(event.eventId()) != null) {
            metrics.ignoredEvent();
            return false;
        }
        Long current = aggregateVersions.getIfPresent(event.aggregateId());
        if (event.aggregateVersion() > 0 && current != null && event.aggregateVersion() <= current) {
            eventIds.put(event.eventId(), Boolean.TRUE);
            metrics.ignoredEvent();
            return false;
        }
        eventIds.put(event.eventId(), Boolean.TRUE);
        if (event.aggregateVersion() > 0) aggregateVersions.put(event.aggregateId(), event.aggregateVersion());
        consumer.accept(event);
        return true;
    }
}

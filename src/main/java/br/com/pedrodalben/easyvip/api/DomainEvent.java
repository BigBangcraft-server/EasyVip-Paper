package br.com.pedrodalben.easyvip.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Versioned event envelope; delivery and deduplication belong to adapters. */
public record DomainEvent(
        UUID eventId,
        DomainEventType type,
        int schemaVersion,
        UUID aggregateId,
        String originatingNode,
        Instant occurredAt,
        Map<String, String> attributes
) {
    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (originatingNode == null || originatingNode.isBlank()) {
            throw new IllegalArgumentException("originatingNode cannot be blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}

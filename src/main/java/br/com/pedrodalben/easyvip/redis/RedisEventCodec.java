package br.com.pedrodalben.easyvip.redis;

import br.com.pedrodalben.easyvip.api.DomainEvent;
import br.com.pedrodalben.easyvip.api.DomainEventType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small canonical, bounded wire format for Redis Pub/Sub events. */
public final class RedisEventCodec {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    public String encode(DomainEvent event) {
        if (event == null) throw new IllegalArgumentException("event cannot be null");
        List<String> fields = new ArrayList<>(List.of(
                "ev1", b64(event.eventId().toString()), event.type().name(), Integer.toString(event.schemaVersion()),
                b64(event.aggregateId().toString()), Long.toString(event.aggregateVersion()), b64(event.originatingNode()),
                b64(event.occurredAt().toString()), encodeAttributes(event.attributes())));
        String encoded = String.join("|", fields);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Redis event exceeds 64 KiB");
        }
        return encoded;
    }

    public DomainEvent decode(String payload) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Redis event payload is missing or too large");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 9 || !"ev1".equals(fields[0])) {
            throw new IllegalArgumentException("Unsupported Redis event envelope");
        }
        try {
            UUID eventId = UUID.fromString(decodeField(fields[1]));
            DomainEventType type = DomainEventType.valueOf(fields[2]);
            int schemaVersion = Integer.parseInt(fields[3]);
            UUID aggregateId = UUID.fromString(decodeField(fields[4]));
            long aggregateVersion = Long.parseLong(fields[5]);
            String node = decodeField(fields[6]);
            Instant occurredAt = Instant.parse(decodeField(fields[7]));
            Map<String, String> attributes = decodeAttributes(fields[8]);
            if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported event schema " + schemaVersion);
            return new DomainEvent(eventId, type, schemaVersion, aggregateId, aggregateVersion, node, occurredAt, attributes);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Redis event envelope", exception);
        }
    }

    private static String encodeAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return "";
        return attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> b64(entry.getKey()) + ":" + b64(entry.getValue() == null ? "" : entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static Map<String, String> decodeAttributes(String value) {
        if (value.isEmpty()) return Map.of();
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String pair : value.split(",", -1)) {
            String[] fields = pair.split(":", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Invalid event attribute");
            String key = decodeField(fields[0]);
            if (key.isBlank() || attributes.put(key, decodeField(fields[1])) != null) {
                throw new IllegalArgumentException("Duplicate or blank event attribute");
            }
        }
        return attributes;
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid base64 event field", exception);
        }
    }
}

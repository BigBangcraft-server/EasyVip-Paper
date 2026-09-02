package br.com.pedrodalben.easyvip.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** One temporal grant of a catalog entitlement to one player. */
public record Grant(
        UUID playerUuid,
        String grantId,
        String entitlementId,
        Instant startsAt,
        Instant expiresAt,
        Status status,
        String source,
        String sourceReference,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    public enum Status { ACTIVE, REVOKED, EXPIRED, PENDING }

    public Grant {
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        grantId = required(grantId, "grantId");
        entitlementId = required(entitlementId, "entitlementId").toLowerCase(Locale.ROOT);
        startsAt = Objects.requireNonNull(startsAt, "startsAt");
        status = Objects.requireNonNull(status, "status");
        source = required(source, "source");
        sourceReference = sourceReference == null ? "" : sourceReference;
        createdBy = createdBy == null ? "system" : createdBy;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return status == Status.ACTIVE
                && !instant.isBefore(startsAt)
                && (expiresAt == null || instant.isBefore(expiresAt));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}

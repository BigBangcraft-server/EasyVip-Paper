package br.com.pedrodalben.easyvip.delivery;

import java.util.Objects;
import java.util.UUID;

/** Immutable request for one durable, idempotent benefit delivery. */
public record DeliveryRequest(UUID playerUuid, String grantId, String benefitId,
                              String scopeType, String scopeValue,
                              String idempotencyKey, DeliveryPolicy policy) {
    public DeliveryRequest {
        Objects.requireNonNull(playerUuid, "playerUuid");
        grantId = optional(grantId, "grantId", 36);
        benefitId = required(benefitId, "benefitId", 255);
        scopeType = required(scopeType, "scopeType", 32);
        scopeValue = required(scopeValue, "scopeValue", 255);
        idempotencyKey = required(idempotencyKey, "idempotencyKey", 255);
        Objects.requireNonNull(policy, "policy");
    }

    private static String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        return trimmed;
    }

    private static String optional(String value, String name, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        return trimmed.isEmpty() ? null : trimmed;
    }
}

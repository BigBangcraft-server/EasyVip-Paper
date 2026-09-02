package br.com.pedrodalben.easyvip.api;

import java.util.Locale;
import java.util.Objects;

/** A capability value granted to a player for a deployment scope. */
public record CapabilityGrant(
        String grantId,
        String capability,
        CapabilityValue value,
        Scope scope,
        MergeStrategy mergeStrategy,
        int priority
) {

    public CapabilityGrant {
        grantId = required(grantId, "grantId");
        capability = required(capability, "capability").toLowerCase(Locale.ROOT);
        value = Objects.requireNonNull(value, "value");
        scope = Objects.requireNonNull(scope, "scope");
        mergeStrategy = Objects.requireNonNull(mergeStrategy, "mergeStrategy");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}

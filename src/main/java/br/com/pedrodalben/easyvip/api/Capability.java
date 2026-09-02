package br.com.pedrodalben.easyvip.api;

import java.util.Locale;
import java.util.Objects;

/** A typed capability value, independent from any VIP tier name. */
public record Capability(String name, CapabilityValue value) {
    public Capability {
        name = required(name, "name").toLowerCase(Locale.ROOT);
        value = Objects.requireNonNull(value, "value");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}

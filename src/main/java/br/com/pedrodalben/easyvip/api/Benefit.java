package br.com.pedrodalben.easyvip.api;

import java.util.Locale;
import java.util.Objects;

/** A lifecycle-managed product benefit which grants one capability. */
public record Benefit(
        String id,
        Capability capability,
        BenefitClassification classification,
        Scope scope,
        MergeStrategy mergeStrategy,
        int priority
) {
    public Benefit {
        id = required(id, "id");
        capability = Objects.requireNonNull(capability, "capability");
        classification = Objects.requireNonNull(classification, "classification");
        scope = Objects.requireNonNull(scope, "scope");
        mergeStrategy = Objects.requireNonNull(mergeStrategy, "mergeStrategy");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

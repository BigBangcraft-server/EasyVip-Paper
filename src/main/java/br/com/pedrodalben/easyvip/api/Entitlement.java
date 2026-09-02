package br.com.pedrodalben.easyvip.api;

import java.util.List;
import java.util.Locale;

/** Named catalog entry whose benefits are granted together. */
public record Entitlement(String id, String displayName, List<Benefit> benefits) {
    public Entitlement {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        id = id.trim().toLowerCase(Locale.ROOT);
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        benefits = List.copyOf(benefits == null ? List.of() : benefits);
    }
}

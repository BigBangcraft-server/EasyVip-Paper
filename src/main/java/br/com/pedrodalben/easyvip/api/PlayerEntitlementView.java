package br.com.pedrodalben.easyvip.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;

/** Immutable, already-resolved capability view for one player and node context. */
public final class PlayerEntitlementView {
    private final Map<String, CapabilityValue> capabilities;

    public PlayerEntitlementView(Map<String, CapabilityValue> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        this.capabilities = Map.copyOf(capabilities);
    }

    public Map<String, CapabilityValue> capabilities() {
        return capabilities;
    }

    public boolean has(String capability) {
        CapabilityValue value = capabilities.get(normalize(capability));
        return value != null && (value.kind() != CapabilityValue.Kind.BOOLEAN || value.asBoolean());
    }

    public Optional<CapabilityValue> get(String capability) {
        return Optional.ofNullable(capabilities.get(normalize(capability)));
    }

    public boolean getBoolean(String capability, boolean defaultValue) {
        return get(capability).map(CapabilityValue::asBoolean).orElse(defaultValue);
    }

    public int getInt(String capability, int defaultValue) {
        return get(capability).map(CapabilityValue::asInt).orElse(defaultValue);
    }

    public BigDecimal getDecimal(String capability, BigDecimal defaultValue) {
        return get(capability).map(CapabilityValue::asDecimal).orElse(defaultValue);
    }

    public String getString(String capability, String defaultValue) {
        return get(capability).map(CapabilityValue::asString).orElse(defaultValue);
    }

    public List<String> getStrings(String capability, List<String> defaultValue) {
        return get(capability).map(CapabilityValue::asStrings).orElse(defaultValue);
    }

    private static String normalize(String capability) {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability cannot be blank");
        }
        return capability.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

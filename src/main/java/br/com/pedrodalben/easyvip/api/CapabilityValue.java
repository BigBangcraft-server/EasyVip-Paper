package br.com.pedrodalben.easyvip.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Typed value exposed by the public capability API. */
public record CapabilityValue(Kind kind, Object rawValue) {

    public enum Kind {
        BOOLEAN,
        INTEGER,
        DECIMAL,
        STRING,
        STRING_LIST
    }

    public CapabilityValue {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(rawValue, "rawValue");
        rawValue = switch (kind) {
            case BOOLEAN -> requireType(rawValue, Boolean.class);
            case INTEGER -> requireType(rawValue, Integer.class);
            case DECIMAL -> requireType(rawValue, BigDecimal.class);
            case STRING -> requireType(rawValue, String.class);
            case STRING_LIST -> requireStringList(rawValue);
        };
    }

    public static CapabilityValue of(boolean value) {
        return new CapabilityValue(Kind.BOOLEAN, value);
    }

    public static CapabilityValue of(int value) {
        return new CapabilityValue(Kind.INTEGER, value);
    }

    public static CapabilityValue of(BigDecimal value) {
        return new CapabilityValue(Kind.DECIMAL, value);
    }

    public static CapabilityValue of(String value) {
        return new CapabilityValue(Kind.STRING, Objects.requireNonNull(value, "value"));
    }

    public static CapabilityValue ofStrings(List<String> values) {
        return new CapabilityValue(Kind.STRING_LIST, values);
    }

    public boolean asBoolean() {
        return (Boolean) requireKind(Kind.BOOLEAN);
    }

    public int asInt() {
        return (Integer) requireKind(Kind.INTEGER);
    }

    public BigDecimal asDecimal() {
        return (BigDecimal) requireKind(Kind.DECIMAL);
    }

    public String asString() {
        return (String) requireKind(Kind.STRING);
    }

    @SuppressWarnings("unchecked")
    public List<String> asStrings() {
        return (List<String>) requireKind(Kind.STRING_LIST);
    }

    private Object requireKind(Kind expected) {
        if (kind != expected) {
            throw new IllegalStateException("Capability value is " + kind + ", not " + expected);
        }
        return rawValue;
    }

    private static <T> T requireType(Object value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Expected " + type.getSimpleName() + " capability value");
        }
        return type.cast(value);
    }

    private static List<String> requireStringList(Object value) {
        List<?> values = requireType(value, List.class);
        if (values.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("Expected String list capability value");
        }
        return values.stream().map(String.class::cast).toList();
    }
}

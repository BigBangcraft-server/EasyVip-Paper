package br.com.pedrodalben.easyvip.api;

import java.util.Locale;
import java.util.Objects;

/** Immutable scope selector. NETWORK uses the literal value {@code network}. */
public record Scope(ScopeType type, String value) {

    public Scope {
        Objects.requireNonNull(type, "type");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("scope value cannot be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (type == ScopeType.NETWORK && !"network".equals(value)) {
            throw new IllegalArgumentException("NETWORK scope must use value 'network'");
        }
    }

    public static Scope network() {
        return new Scope(ScopeType.NETWORK, "network");
    }

    public boolean appliesTo(ScopeContext context) {
        Objects.requireNonNull(context, "context");
        NetworkNodeIdentity node = context.node();
        return switch (type) {
            case NETWORK -> true;
            case GROUP -> value.equals(node.group());
            case NODE -> value.equals(node.nodeId());
            case TAG -> node.tags().contains(value);
            case ENVIRONMENT -> value.equals(node.environment());
        };
    }
}

package br.com.pedrodalben.easyvip.api;

import java.util.Objects;
import java.util.Set;

/** Runtime context used to evaluate a capability grant. */
public record ScopeContext(NetworkNodeIdentity node) {

    public ScopeContext {
        Objects.requireNonNull(node, "node");
    }

    public static ScopeContext network() {
        return new ScopeContext(new NetworkNodeIdentity("network", "network", "production", Set.of()));
    }
}

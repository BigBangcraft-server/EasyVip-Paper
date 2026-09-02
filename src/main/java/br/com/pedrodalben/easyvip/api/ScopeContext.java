package br.com.pedrodalben.easyvip.api;

import java.util.Objects;

/** Runtime context used to evaluate a capability grant. */
public record ScopeContext(NetworkNodeIdentity node) {

    public ScopeContext {
        Objects.requireNonNull(node, "node");
    }
}

package br.com.pedrodalben.easyvip.api;

import java.util.UUID;

/** Resolves authoritative grants into a node-specific player view. */
@FunctionalInterface
public interface CapabilityResolver {
    PlayerEntitlementView resolve(UUID playerUuid, ScopeContext context);
}

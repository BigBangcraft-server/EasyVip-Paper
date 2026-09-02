package br.com.pedrodalben.easyvip.api;

import java.util.UUID;

/** Read-only entitlement boundary consumed by platform adapters. */
@FunctionalInterface
public interface EntitlementService {
    PlayerEntitlementView player(UUID playerUuid, ScopeContext context);
}

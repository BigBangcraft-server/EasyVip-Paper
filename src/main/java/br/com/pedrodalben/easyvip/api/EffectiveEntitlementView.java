package br.com.pedrodalben.easyvip.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit-friendly view of active grants and resolved capabilities. */
public final class EffectiveEntitlementView {
    private final UUID playerUuid;
    private final ScopeContext context;
    private final List<Grant> grants;
    private final PlayerEntitlementView capabilities;

    public EffectiveEntitlementView(UUID playerUuid, ScopeContext context, List<Grant> grants,
                                    PlayerEntitlementView capabilities) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.context = Objects.requireNonNull(context, "context");
        this.grants = List.copyOf(grants == null ? List.of() : grants);
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    public UUID playerUuid() { return playerUuid; }
    public ScopeContext context() { return context; }
    public List<Grant> grants() { return grants; }
    public PlayerEntitlementView capabilities() { return capabilities; }
    public boolean has(String capability) { return capabilities.has(capability); }
    public CapabilityValue get(String capability) { return capabilities.get(capability).orElse(null); }
}

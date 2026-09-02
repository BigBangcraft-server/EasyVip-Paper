package br.com.pedrodalben.easyvip.core;

import br.com.pedrodalben.easyvip.api.Benefit;
import br.com.pedrodalben.easyvip.api.BenefitService;
import br.com.pedrodalben.easyvip.api.CapabilityGrant;
import br.com.pedrodalben.easyvip.api.DefaultCapabilityResolver;
import br.com.pedrodalben.easyvip.api.EasyVipApi;
import br.com.pedrodalben.easyvip.api.Entitlement;
import br.com.pedrodalben.easyvip.api.EntitlementService;
import br.com.pedrodalben.easyvip.api.EffectiveEntitlementView;
import br.com.pedrodalben.easyvip.api.Grant;
import br.com.pedrodalben.easyvip.api.PlayerEntitlementView;
import br.com.pedrodalben.easyvip.api.ScopeContext;

import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Pure-JDK entitlement evaluator shared by Paper and future network adapters. */
public final class ConfiguredEntitlementService implements EasyVipApi, EntitlementService, BenefitService {
    private final Supplier<Map<String, Entitlement>> catalogSource;
    private final Function<UUID, ? extends Collection<Grant>> grantSource;
    private final Clock clock;

    public ConfiguredEntitlementService(Map<String, Entitlement> catalog,
                                        Function<UUID, ? extends Collection<Grant>> grantSource,
                                        Clock clock) {
        this(() -> catalog, grantSource, clock);
    }

    public ConfiguredEntitlementService(Supplier<Map<String, Entitlement>> catalogSource,
                                        Function<UUID, ? extends Collection<Grant>> grantSource,
                                        Clock clock) {
        this.catalogSource = Objects.requireNonNull(catalogSource, "catalogSource");
        this.grantSource = Objects.requireNonNull(grantSource, "grantSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EntitlementService entitlements() {
        return this;
    }

    @Override
    public BenefitService benefits() {
        return this;
    }

    @Override
    public PlayerEntitlementView player(UUID playerUuid, ScopeContext context) {
        return effective(playerUuid, context).capabilities();
    }

    @Override
    public List<String> getStringListCapability(UUID playerUuid, String capability, List<String> defaultValue, ScopeContext context) {
        return player(playerUuid, context).getStrings(capability, defaultValue);
    }

    @Override
    public boolean hasCapability(UUID playerUuid, String capability, ScopeContext context) {
        return player(playerUuid, context).has(capability);
    }

    @Override
    public int getIntCapability(UUID playerUuid, String capability, int defaultValue, ScopeContext context) {
        return player(playerUuid, context).getInt(capability, defaultValue);
    }

    @Override
    public BigDecimal getDecimalCapability(UUID playerUuid, String capability, BigDecimal defaultValue, ScopeContext context) {
        return player(playerUuid, context).getDecimal(capability, defaultValue);
    }

    public EffectiveEntitlementView effective(UUID playerUuid, ScopeContext context) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(context, "context");
        Instant now = clock.instant();
        Map<String, Entitlement> catalog = Objects.requireNonNull(catalogSource.get(), "catalog");
        Collection<Grant> sourceGrants = grantSource.apply(playerUuid);
        List<Grant> activeGrants = sourceGrants == null ? new ArrayList<>() : sourceGrants.stream()
                .filter(Objects::nonNull)
                .filter(grant -> grant.playerUuid().equals(playerUuid) && grant.activeAt(now))
                .sorted(Comparator.comparing(Grant::grantId))
                .toList();

        List<CapabilityGrant> capabilityGrants = new ArrayList<>();
        for (Grant grant : activeGrants) {
            Entitlement entitlement = catalog.get(grant.entitlementId());
            if (entitlement == null) {
                continue;
            }
            for (Benefit benefit : entitlement.benefits()) {
                capabilityGrants.add(new CapabilityGrant(
                        grant.grantId() + "/" + benefit.id(),
                        benefit.capability().name(),
                        benefit.capability().value(),
                        benefit.scope(),
                        benefit.mergeStrategy(),
                        benefit.priority()));
            }
        }

        PlayerEntitlementView resolved = new DefaultCapabilityResolver(capabilityGrants).resolve(playerUuid, context);
        return new EffectiveEntitlementView(playerUuid, context, activeGrants, resolved);
    }

}

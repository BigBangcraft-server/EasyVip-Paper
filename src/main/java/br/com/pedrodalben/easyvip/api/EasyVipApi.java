package br.com.pedrodalben.easyvip.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Stable, platform-neutral entry point for future Paper and Velocity adapters. */
public interface EasyVipApi {
    String API_VERSION = "1.1";

    EntitlementService entitlements();

    BenefitService benefits();

    default PlayerEntitlementView player(UUID playerUuid, ScopeContext context) {
        return entitlements().player(playerUuid, context);
    }

    default EffectiveEntitlementView effective(UUID playerUuid, ScopeContext context) {
        return new EffectiveEntitlementView(playerUuid, context, List.of(), player(playerUuid, context));
    }

    default PlayerEntitlementView player(UUID playerUuid) {
        return player(playerUuid, ScopeContext.network());
    }

    default boolean hasCapability(UUID playerUuid, String capability, ScopeContext context) {
        return benefits().hasCapability(playerUuid, capability, context);
    }

    default int getIntCapability(UUID playerUuid, String capability, int defaultValue, ScopeContext context) {
        return benefits().getIntCapability(playerUuid, capability, defaultValue, context);
    }

    default BigDecimal getDecimalCapability(UUID playerUuid, String capability, BigDecimal defaultValue, ScopeContext context) {
        return benefits().getDecimalCapability(playerUuid, capability, defaultValue, context);
    }

    default List<String> getStringListCapability(UUID playerUuid, String capability, List<String> defaultValue, ScopeContext context) {
        return benefits().getStringListCapability(playerUuid, capability, defaultValue, context);
    }
}

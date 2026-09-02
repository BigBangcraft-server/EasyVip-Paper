package br.com.pedrodalben.easyvip.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Convenience capability queries; tier names are intentionally absent. */
public interface BenefitService {
    PlayerEntitlementView player(UUID playerUuid, ScopeContext context);

    default boolean hasCapability(UUID playerUuid, String capability, ScopeContext context) {
        return player(playerUuid, context).has(capability);
    }

    default int getIntCapability(UUID playerUuid, String capability, int defaultValue, ScopeContext context) {
        return player(playerUuid, context).getInt(capability, defaultValue);
    }

    default BigDecimal getDecimalCapability(UUID playerUuid, String capability, BigDecimal defaultValue, ScopeContext context) {
        return player(playerUuid, context).getDecimal(capability, defaultValue);
    }

    default List<String> getStringListCapability(UUID playerUuid, String capability, List<String> defaultValue, ScopeContext context) {
        return player(playerUuid, context).getStrings(capability, defaultValue);
    }
}

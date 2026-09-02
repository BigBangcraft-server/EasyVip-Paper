package br.com.pedrodalben.easyvip.network;

import br.com.pedrodalben.easyvip.api.Benefit;
import br.com.pedrodalben.easyvip.api.BenefitClassification;
import br.com.pedrodalben.easyvip.api.Capability;
import br.com.pedrodalben.easyvip.api.CapabilityValue;
import br.com.pedrodalben.easyvip.api.EasyVipApi;
import br.com.pedrodalben.easyvip.api.Entitlement;
import br.com.pedrodalben.easyvip.api.Grant;
import br.com.pedrodalben.easyvip.api.MergeStrategy;
import br.com.pedrodalben.easyvip.api.Scope;
import br.com.pedrodalben.easyvip.api.ScopeType;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.core.ConfiguredEntitlementService;
import br.com.pedrodalben.easyvip.model.PlayerVipRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRegistry;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Snapshot adapter keeping the existing tier/activation model compatible. */
public final class LegacyVipCapabilityBridge {
    private LegacyVipCapabilityBridge() {
    }

    public static EasyVipApi create(Supplier<Map<String, EasyVipConfig.VipTierDefinition>> tierSource,
                                    Function<UUID, PlayerVipRegistry> registrySource,
                                    Clock clock) {
        return new ConfiguredEntitlementService(
                () -> catalog(tierSource.get()),
                uuid -> grants(uuid, registrySource.apply(uuid)),
                clock);
    }

    private static Map<String, Entitlement> catalog(Map<String, EasyVipConfig.VipTierDefinition> tiers) {
        Map<String, Entitlement> catalog = new LinkedHashMap<>();
        if (tiers == null) return catalog;
        for (EasyVipConfig.VipTierDefinition tier : tiers.values()) {
            if (tier == null || tier.id == null || tier.id.isBlank()) continue;
            List<Benefit> benefits = new ArrayList<>();
            if (tier.benefits != null) {
                for (EasyVipConfig.VipBenefitDefinition definition : tier.benefits.values()) {
                    if (definition == null) continue;
                    benefits.add(toBenefit(definition, tier.priority));
                }
            }
            catalog.put(tier.id.trim().toLowerCase(Locale.ROOT),
                    new Entitlement(tier.id, tier.displayName, benefits));
        }
        return catalog;
    }

    private static Collection<Grant> grants(UUID uuid, PlayerVipRegistry registry) {
        if (registry == null || registry.getVips() == null) return List.of();
        List<Grant> grants = new ArrayList<>();
        for (PlayerVipRecord record : registry.getVips().values()) {
            if (record == null || record.getTierId() == null || record.getTierId().isBlank()) continue;
            Instant startsAt = Instant.ofEpochMilli(Math.max(0L, record.getStartTime()));
            Instant expiresAt = record.getExpiryTime() < 0 ? null : Instant.ofEpochMilli(record.getExpiryTime());
            Grant.Status status = record.isExpired() ? Grant.Status.EXPIRED : Grant.Status.ACTIVE;
            String grantId = "legacy:" + uuid + ":" + record.getTierId().trim().toLowerCase(Locale.ROOT) + ":" + record.getStartTime();
            Instant now = Instant.now();
            grants.add(new Grant(uuid, grantId, record.getTierId(), startsAt, expiresAt, status,
                    "legacy", record.getTierId(), "legacy-adapter", now, now, 0));
        }
        return grants;
    }

    private static Benefit toBenefit(EasyVipConfig.VipBenefitDefinition definition, int tierPriority) {
        String type = required(definition.type, "benefit type").toUpperCase(Locale.ROOT);
        CapabilityValue value = switch (type) {
            case "BOOLEAN" -> CapabilityValue.of((Boolean) definition.value);
            case "INTEGER" -> CapabilityValue.of(((Number) definition.value).intValue());
            case "DECIMAL" -> CapabilityValue.of(toDecimal(definition.value));
            case "STRING" -> CapabilityValue.of(String.valueOf(definition.value));
            case "STRING_LIST" -> CapabilityValue.ofStrings(toStringList(definition.value));
            default -> throw new IllegalArgumentException("Unsupported benefit type: " + type);
        };
        return new Benefit(definition.id, new Capability(definition.capability, value),
                BenefitClassification.valueOf(required(definition.classification, "classification").toUpperCase(Locale.ROOT)),
                parseScope(definition.scope),
                MergeStrategy.valueOf(required(definition.merge, "merge").toUpperCase(Locale.ROOT)),
                safePriority(tierPriority, definition.priority));
    }

    private static Scope parseScope(String raw) {
        String scope = required(raw, "scope").toLowerCase(Locale.ROOT);
        if ("network".equals(scope)) return Scope.network();
        int separator = scope.indexOf(':');
        if (separator <= 0 || separator == scope.length() - 1) {
            throw new IllegalArgumentException("Invalid benefit scope: " + raw);
        }
        ScopeType type = switch (scope.substring(0, separator)) {
            case "group" -> ScopeType.GROUP;
            case "node" -> ScopeType.NODE;
            case "tag" -> ScopeType.TAG;
            case "environment" -> ScopeType.ENVIRONMENT;
            default -> throw new IllegalArgumentException("Invalid benefit scope: " + raw);
        };
        return new Scope(type, scope.substring(separator + 1));
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return new BigDecimal(String.valueOf(value));
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("STRING_LIST benefit requires a list of strings");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static int safePriority(int tierPriority, int benefitPriority) {
        long combined = (long) tierPriority * 1000L + benefitPriority;
        return combined > Integer.MAX_VALUE ? Integer.MAX_VALUE : combined < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) combined;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value.trim();
    }
}

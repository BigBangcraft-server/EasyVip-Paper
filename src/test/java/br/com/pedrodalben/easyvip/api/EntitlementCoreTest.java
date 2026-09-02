package br.com.pedrodalben.easyvip.api;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.config.TomlParser;
import br.com.pedrodalben.easyvip.config.TomlWriter;
import br.com.pedrodalben.easyvip.core.ConfiguredEntitlementService;
import br.com.pedrodalben.easyvip.model.PlayerVipRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRegistry;
import br.com.pedrodalben.easyvip.network.LegacyVipCapabilityBridge;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntitlementCoreTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final ScopeContext CONTEXT = new ScopeContext(
            new NetworkNodeIdentity("bedwars-03", "bedwars", "production", Set.of("competitive")));

    @Test
    void activeGrantsResolveTypedCapabilitiesAndIgnoreExpiredOrRevoked() {
        Entitlement entitlement = new Entitlement("diamond", "VIP Diamond", List.of(
                new Benefit("private", new Capability("minigame.private_match", CapabilityValue.of(true)),
                        BenefitClassification.CONVENIENCE, Scope.network(), MergeStrategy.OR, 0),
                new Benefit("priority", new Capability("queue.priority", CapabilityValue.of(30)),
                        BenefitClassification.CONVENIENCE, Scope.network(), MergeStrategy.MAX, 0),
                new Benefit("color", new Capability("chat.color", CapabilityValue.of("aqua")),
                        BenefitClassification.COSMETIC, new Scope(ScopeType.GROUP, "bedwars"), MergeStrategy.HIGHEST_PRIORITY, 10),
                new Benefit("tags", new Capability("chat.tags", CapabilityValue.ofStrings(List.of("diamond", "cosmetic"))),
                        BenefitClassification.COSMETIC, Scope.network(), MergeStrategy.HIGHEST_PRIORITY, 0)));
        Grant active = grant("active", "diamond", Grant.Status.ACTIVE, NOW.minusSeconds(30), NOW.plusSeconds(30));
        Grant expired = grant("expired", "diamond", Grant.Status.ACTIVE, NOW.minusSeconds(60), NOW.minusSeconds(1));
        Grant revoked = grant("revoked", "diamond", Grant.Status.REVOKED, NOW.minusSeconds(60), null);

        ConfiguredEntitlementService service = new ConfiguredEntitlementService(
                Map.of("diamond", entitlement), uuid -> List.of(active, expired, revoked),
                Clock.fixed(NOW, ZoneOffset.UTC));
        EffectiveEntitlementView view = service.effective(PLAYER, CONTEXT);

        assertTrue(view.has("minigame.private_match"));
        assertEquals(30, view.capabilities().getInt("queue.priority", 0));
        assertEquals("aqua", view.capabilities().getString("chat.color", "missing"));
        assertEquals(List.of("diamond", "cosmetic"), view.capabilities().getStrings("chat.tags", List.of()));
        assertEquals(List.of(active), view.grants());
        assertEquals(BenefitClassification.COSMETIC, entitlement.benefits().get(2).classification());
    }

    @Test
    void legacyTierBridgeExposesCapabilitiesWithoutChangingTierStorage() {
        EasyVipConfig.VipTierDefinition tier = new EasyVipConfig.VipTierDefinition();
        tier.id = "vip_diamond";
        tier.displayName = "VIP Diamond";
        tier.priority = 20;
        EasyVipConfig.VipBenefitDefinition benefit = new EasyVipConfig.VipBenefitDefinition();
        benefit.id = "queue";
        benefit.capability = "queue.priority";
        benefit.type = "INTEGER";
        benefit.value = 50L;
        benefit.merge = "MAX";
        benefit.classification = "CONVENIENCE";
        tier.benefits.put(benefit.id, benefit);

        PlayerVipRegistry registry = new PlayerVipRegistry(PLAYER);
        registry.getVips().put(tier.id, new PlayerVipRecord(tier.id, NOW.toEpochMilli(), -1L, false, false));
        EasyVipApi api = LegacyVipCapabilityBridge.create(
                () -> Map.of(tier.id, tier), uuid -> registry, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(50, api.getIntCapability(PLAYER, "queue.priority", 0, CONTEXT));
        assertFalse(api.player(PLAYER).capabilities().isEmpty());
    }

    @Test
    void mixedMergeStrategiesAreRejectedInsteadOfDependingOnCollectionOrder() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultCapabilityResolver(List.of(
                new CapabilityGrant("a", "flag", CapabilityValue.of(true), Scope.network(), MergeStrategy.OR, 0),
                new CapabilityGrant("b", "flag", CapabilityValue.of(true), Scope.network(), MergeStrategy.HIGHEST_PRIORITY, 5)
        )).resolve(PLAYER, ScopeContext.network()));
    }

    @Test
    void benefitTomlRoundTripPreservesTypedConfiguration() {
        Map<String, Object> benefit = Map.of(
                "capability", "queue.priority",
                "type", "INTEGER",
                "value", 50,
                "classification", "CONVENIENCE",
                "scope", "network",
                "merge", "MAX");
        String toml = TomlWriter.write(Map.of("vips", Map.of("diamond", Map.of("benefits", Map.of("priority", benefit)))));
        Map<String, Object> parsed = TomlParser.parse(toml);
        Map<?, ?> value = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) parsed.get("vips")).get("diamond")).get("benefits");
        Map<?, ?> priority = (Map<?, ?>) value.get("priority");
        assertEquals("queue.priority", priority.get("capability"));
        assertEquals("INTEGER", priority.get("type"));
        assertEquals(50L, priority.get("value"));
    }

    private static Grant grant(String id, String entitlementId, Grant.Status status, Instant startsAt, Instant expiresAt) {
        return new Grant(PLAYER, id, entitlementId, startsAt, expiresAt, status,
                "test", id, "test", NOW, NOW, 0);
    }
}

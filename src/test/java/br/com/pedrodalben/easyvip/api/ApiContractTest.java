package br.com.pedrodalben.easyvip.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApiContractTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final ScopeContext BEDWARS = new ScopeContext(
            new NetworkNodeIdentity("bedwars-03", "bedwars", "production", Set.of("minigame", "competitive")));

    @Test
    void scopeResolutionMatchesNetworkGroupNodeTagAndEnvironment() {
        assertTrue(Scope.network().appliesTo(BEDWARS));
        assertTrue(new Scope(ScopeType.GROUP, "bedwars").appliesTo(BEDWARS));
        assertTrue(new Scope(ScopeType.NODE, "bedwars-03").appliesTo(BEDWARS));
        assertTrue(new Scope(ScopeType.TAG, "competitive").appliesTo(BEDWARS));
        assertTrue(new Scope(ScopeType.ENVIRONMENT, "production").appliesTo(BEDWARS));
        assertFalse(new Scope(ScopeType.GROUP, "survival").appliesTo(BEDWARS));
        assertThrows(IllegalArgumentException.class, () -> new Scope(ScopeType.NETWORK, "bedwars"));
    }

    @Test
    void resolverMergesTypedCapabilitiesWithoutTierNames() {
        DefaultCapabilityResolver resolver = new DefaultCapabilityResolver(List.of(
                new CapabilityGrant("network-priority", "queue.priority", CapabilityValue.of(30), Scope.network(), MergeStrategy.MAX, 0),
                new CapabilityGrant("bedwars-priority", "queue.priority", CapabilityValue.of(50), new Scope(ScopeType.GROUP, "bedwars"), MergeStrategy.MAX, 0),
                new CapabilityGrant("cosmetic", "minigame.private_match", CapabilityValue.of(true), Scope.network(), MergeStrategy.OR, 0),
                new CapabilityGrant("label", "bedwars.victory_effect", CapabilityValue.of("diamond"), new Scope(ScopeType.NODE, "bedwars-03"), MergeStrategy.HIGHEST_PRIORITY, 5),
                new CapabilityGrant("fallback-label", "bedwars.victory_effect", CapabilityValue.of("default"), Scope.network(), MergeStrategy.HIGHEST_PRIORITY, 0),
                new CapabilityGrant("decimal", "economy.multiplier", CapabilityValue.of(new BigDecimal("1.25")), Scope.network(), MergeStrategy.MAX, 0)
        ));

        PlayerEntitlementView view = resolver.resolve(PLAYER, BEDWARS);

        assertEquals(50, view.getInt("queue.priority", 0));
        assertTrue(view.has("minigame.private_match"));
        assertEquals("diamond", view.getString("bedwars.victory_effect", "missing"));
        assertEquals(new BigDecimal("1.25"), view.getDecimal("economy.multiplier", BigDecimal.ZERO));
        assertThrows(UnsupportedOperationException.class, () -> view.capabilities().put("x", CapabilityValue.of(true)));
    }

    @Test
    void nodeIdentityIsNormalizedAndImmutable() {
        NetworkNodeIdentity identity = new NetworkNodeIdentity(" BEDWARS-03 ", "BedWars", "PRODUCTION", Set.of("Competitive"));
        assertEquals("bedwars-03", identity.nodeId());
        assertEquals("bedwars", identity.group());
        assertEquals(Set.of("competitive"), identity.tags());
        assertThrows(UnsupportedOperationException.class, () -> identity.tags().add("other"));
    }
}

package br.com.pedrodalben.easyvip.integration;

import br.com.pedrodalben.easyvip.api.CapabilityValue;
import br.com.pedrodalben.easyvip.api.PlayerEntitlementView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedWarsCapabilityGateTest {
    @Test
    void consumesCapabilitiesWithoutTierCoupling() {
        PlayerEntitlementView view = new PlayerEntitlementView(Map.of(
                "minigame.private_match", CapabilityValue.of(true),
                "minigame.map_votes", CapabilityValue.of(3),
                "bedwars.victory_effect", CapabilityValue.of("diamond")));
        BedWarsCapabilityGate gate = new BedWarsCapabilityGate();

        assertTrue(gate.canCreatePrivateMatch(view));
        assertEquals(3, gate.mapVotes(view));
        assertEquals("diamond", gate.victoryEffect(view));
    }
}

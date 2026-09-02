package br.com.pedrodalben.easyvip.integration;

import br.com.pedrodalben.easyvip.api.PlayerEntitlementView;

import java.util.Objects;

/** Reference consumer for a minigame: it asks capabilities, never VIP tier names. */
public final class BedWarsCapabilityGate {
    public boolean canCreatePrivateMatch(PlayerEntitlementView capabilities) {
        return Objects.requireNonNull(capabilities, "capabilities").has("minigame.private_match");
    }

    public int mapVotes(PlayerEntitlementView capabilities) {
        PlayerEntitlementView view = Objects.requireNonNull(capabilities, "capabilities");
        return Math.max(1, view.getInt("minigame.map_votes", view.getInt("minigame.map_vote", 1)));
    }

    public String victoryEffect(PlayerEntitlementView capabilities) {
        return Objects.requireNonNull(capabilities, "capabilities")
                .getString("bedwars.victory_effect", "default");
    }
}

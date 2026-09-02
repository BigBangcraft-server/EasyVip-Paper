# Minigame integration (GOAL 05)

Minigames depend on capabilities, not commercial tier names. A BedWars-style
consumer can use the shared API/core directly:

```java
PlayerEntitlementView capabilities = easyVip.player(playerUuid,
        ScopeContext.group("bedwars"));

if (capabilities.has("minigame.private_match")) {
    privateMatches.open(playerUuid);
}
int mapVotes = capabilities.getInt("minigame.map_vote", 1);
```

`BedWarsCapabilityGate` is a small reference fixture in
`br.com.pedrodalben.easyvip.integration`; it contains no Gold/Diamond branch.
The capability catalog remains configured by EasyVip and can vary by network,
group, node, tag, or environment scope.

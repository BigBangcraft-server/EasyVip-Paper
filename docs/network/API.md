# API contract

`EasyVipApi` is the stable adapter boundary. Consumers ask for
`PlayerEntitlementView` capabilities and `ScopeContext`, not VIP tier names:

```java
PlayerEntitlementView view = api.player(uuid, ScopeContext.group("bedwars"));
if (view.has("minigame.private_match")) { /* allow */ }
int votes = view.getInt("minigame.map_vote", 1);
```

`easyvip-api` domain types are JDK-only; Paper, Velocity, SQL, Redis, LuckPerms,
and Vault remain adapter concerns. `EasyVipApi.API_VERSION` is the compatibility
marker. Cold reads can use `CachedEntitlementApi.playerAsync`; hot reads use the
bounded local cache.

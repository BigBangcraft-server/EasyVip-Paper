# Plugin API

`br.com.pedrodalben.easyvip.api` is the stable, platform-neutral boundary.
It imports only the JDK, so a minigame can compile against the API without
Paper, Bukkit, SQL, or Redis.

Paper exposes it after `onEnable`:

```java
EasyVipApi api = EasyVipPaperPlugin.getInstance().getEasyVipApi();
ScopeContext node = new ScopeContext(new NetworkNodeIdentity(
        "bedwars-03", "bedwars", "production", Set.of("competitive")));

PlayerEntitlementView view = api.player(player.getUniqueId(), node);
if (view.has("minigame.private_match")) {
    // enable the minigame capability, without knowing a tier name
}
int priority = api.getIntCapability(player.getUniqueId(), "queue.priority", 0, node);
List<String> tags = view.getStrings("chat.tags", List.of());
```

`api.player(uuid)` is a network-default convenience query. Scoped servers
should pass their `ScopeContext`. Typed accessors return the supplied default
when a capability is absent; a present value with the wrong type is a
configuration/programming error and is reported rather than silently coerced.

The API version is `1.1`. Consumers should depend on capability names and
typed accessors. They should not call `VipService`, inspect `PlayerVipRecord`,
or infer permissions from `active` tier state. Those classes remain the
compatibility adapter until later goals migrate storage and delivery.

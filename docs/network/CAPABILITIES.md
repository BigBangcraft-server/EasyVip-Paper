# Capability contract

Capabilities are normalized lower-case names with a typed value:

| Type | Java value | Example | Allowed merge rules |
| --- | --- | --- | --- |
| `BOOLEAN` | `boolean` | `minigame.private_match` | `OR`, `HIGHEST_PRIORITY` |
| `INTEGER` | `int` | `queue.priority` | `MAX`, `HIGHEST_PRIORITY` |
| `DECIMAL` | `BigDecimal` | `economy.multiplier` | `MAX`, `HIGHEST_PRIORITY` |
| `STRING` | `String` | `bedwars.victory_effect` | `HIGHEST_PRIORITY` |
| `STRING_LIST` | `List<String>` | `chat.tags` | `HIGHEST_PRIORITY` |

Resolution is deterministic: applicable grants are sorted by descending
benefit priority and then by stable grant id. `OR` requires booleans and
returns true if any value is true. `MAX` requires numeric values and promotes
mixed integer/decimal inputs to `BigDecimal`. `HIGHEST_PRIORITY` returns the
first value. Mixing merge strategies for one capability is rejected rather
than depending on database or collection order.

Every benefit has a lifecycle classification. `COSMETIC` covers visual or
social effects; `CONVENIENCE` covers queue, limits, cooldowns, or access
quality. Commands and Bukkit actions remain last-mile effects, not capability
definitions.

Scopes are `network`, `group:<id>`, `node:<id>`, `tag:<tag>`, and
`environment:<name>`. A consumer supplies a `ScopeContext` with the current
node identity so the same player can receive different effective views on
different servers.

## Node examples

* Lobby checks `queue.priority` and `chat.tags`.
* BedWars checks `bedwars.victory_effect` and `minigame.private_match`.
* SkyWars checks `skywars.map_vote` or `skywars.extra_kits`.
* Survival checks `survival.home_limit` and `survival.keep_inventory`.

These checks continue to work if the commercial catalog changes from Diamond
to a bundle, campaign, or staff grant.

# Entitlements and grants

GOAL 03 separates a player's durable entitlement from the old display tier.
`Entitlement` is a catalog entry (`vip_diamond`, for example) containing
typed `Benefit` definitions. A `Grant` is the temporal assignment of that
entitlement to one UUID and carries source, audit timestamps, status, and
optional expiration.

The compatibility adapter projects the existing `PlayerVipRegistry` into
active grants. It deliberately includes every unexpired owned tier; the old
`active` flag only selects the legacy display/permission tier and does not
remove capability benefits. Existing `/vip`, activation, expiration, JSON, and
SQL behavior is unchanged.

Grant lifecycle:

* `PENDING` is reserved for a staged fulfillment that is not effective yet.
* `ACTIVE` is effective when `starts_at <= now < expires_at` (or permanent).
* `EXPIRED` is never effective after its end instant.
* `REVOKED` is never effective, even if its dates are valid.

Paper and Velocity adapters read the legacy persistence snapshot through the
version-aware local cache. Redis only invalidates snapshots; it must not become
authoritative over SQL.

## Config example

```toml
[vips.diamond.benefits.queue_priority]
capability = "queue.priority"
type = "INTEGER"
value = 50
classification = "CONVENIENCE"
scope = "network"
merge = "MAX"

[vips.diamond.benefits.victory_color]
capability = "bedwars.victory_effect"
type = "STRING"
value = "diamond"
classification = "COSMETIC"
scope = "group:bedwars"
merge = "HIGHEST_PRIORITY"
priority = 10
```

Tier names remain a configuration and migration concern only. New gameplay
code consumes `queue.priority` or `bedwars.victory_effect`, never
`vip_diamond`.

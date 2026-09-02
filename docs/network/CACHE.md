# Local entitlement cache

`EntitlementCache` stores resolved `EffectiveEntitlementView` values keyed by
player UUID and `ScopeContext`. Caffeine provides thread-safe access,
frequency-aware bounded eviction, `maximumSize`, `expireAfterWrite`, and
hit/miss statistics. Defaults are 10,000 entries and a 30-second TTL; both are
configurable in `network.toml`.

On a hit, capability checks are memory-only. On a miss, the SQL-backed legacy
adapter resolves a fresh view. `CachedEntitlementApi.playerAsync` exists for
cold-cache calls so a Paper/Folia event thread can keep database work off its
critical path. Existing synchronous calls remain compatible for callers that
already run off-thread or have a warm cache.

Invalidation sources:

* local VIP activation/expiration events invalidate immediately;
* Redis events invalidate by aggregate version;
* version `0` means unknown and conservatively invalidates;
* TTL bounds staleness if a node misses Pub/Sub or restarts.

An older event cannot invalidate a view known to be newer. The version and
event-id maps are bounded with the same configured maximum; eviction can cause
a conservative reload, never a grant or permission escalation.

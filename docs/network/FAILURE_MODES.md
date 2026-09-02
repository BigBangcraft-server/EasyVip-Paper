# GOAL 04 failure modes

| Failure | Behavior | Safety property |
| --- | --- | --- |
| Redis unavailable at startup | plugin logs a sanitized warning and continues | SQL remains authoritative |
| Redis outage while running | publish/ping fail asynchronously; subscription retries | no entitlement mutation is lost |
| Redis reconnect | subscription opens a fresh connection | duplicate events are idempotent |
| duplicate event | event-id guard drops it | no repeated invalidation work |
| out-of-order event | aggregate-version guard drops stale version | newer cache is not replaced by older state |
| missed invalidation | bounded TTL forces a DB reload | staleness is time-bounded |
| malformed payload | codec rejects and counts it | untrusted data cannot affect grants |
| node crash | heartbeat hash expires | visibility is advisory only |
| cache pressure | Caffeine evicts by bounded maximum | no unbounded player state |
| Redis pool exhaustion | bounded wait fails the async operation | no blocked Minecraft main thread |

Capability reads on a cold cache can use `playerAsync`; a caller must handle
the failed future and choose its own fail-closed UI/gameplay behavior. No code
grants a VIP because Redis, SQL, LuckPerms, or another dependency failed.

GOAL 04 does not claim durable event replay. Pub/Sub is suitable for low-latency
invalidation, while SQL reads and TTL provide convergence. A future Streams or
outbox design belongs to delivery/event durability work and must preserve SQL
authority.

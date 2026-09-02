# Redis transport (GOAL 04-06)

Redis is an optional transport for cache invalidation, lifecycle events, and
ephemeral node visibility. MySQL/SQL remains the authoritative entitlement
source. A Redis restart cannot delete or mutate a VIP grant.

The implementation uses Jedis `7.1.0` with a bounded connection pool, a bounded
I/O work queue, and a dedicated daemon subscription worker. Publish, ping, node
heartbeat, and visibility calls run on the Redis executor; no Paper event
handler waits for a Redis round trip. The pool config has bounded wait,
socket/connect timeout, idle health checks, and no credential logging. `redis://` and `rediss://` are
accepted; production deployments should use `rediss://` plus ACLs.

The channel is configurable (`network.redis_channel`) and the key namespace
is constrained to alphanumeric characters plus `:`, `_`, and `-`. Pub/Sub is
at-least-once: a reconnect can duplicate an event, so consumers use event-id
and aggregate-version guards before invalidating cache state.

Redis is disabled by default for compatibility. Enable it in the generated
`network.toml` only after network credentials/TLS and a reachable endpoint are
ready. If startup, ping, publish, or subscribe fails, SQL-backed capability
checks continue and the local TTL cache remains safe.

CI executes the Pub/Sub and node-heartbeat integration tests against Redis 7.4.
On 2026-09-02, a disposable Redis 7.4 container was restarted and the
integration suite passed before and after restart. Production ACL/TLS and
failover drills remain deployment gates.

On 2026-09-02, the same integration suite also passed over `rediss://` against
a disposable Redis 7.4 instance using a generated CA. The TLS endpoint was
restarted and the suite passed again; the CA and server key were temporary lab
files, not production credentials.

## Node heartbeat

`RedisNodeRegistry` stores an expiring hash per node and a sorted heartbeat
index. It records node id, group, environment, tags, plugin version, API
version, and heartbeat timestamp. Visibility is advisory and expires after the
configured TTL; it must never authorize an entitlement or be used as durable
state.

# Redis transport (GOAL 04)

Redis is an optional transport for cache invalidation, lifecycle events, and
ephemeral node visibility. MySQL/SQL remains the authoritative entitlement
source. A Redis restart cannot delete or mutate a VIP grant.

The implementation uses Jedis `7.1.0` with a bounded connection pool and a
dedicated daemon subscription worker. Publish, ping, node heartbeat, and
visibility calls run on the Redis executor; no Paper event handler waits for a
Redis round trip. The pool config has bounded wait, socket/connect timeout,
idle health checks, and no credential logging. `redis://` and `rediss://` are
accepted; production deployments should use `rediss://` plus ACLs.

The channel is configurable (`network.redis_channel`) and the key namespace
is constrained to alphanumeric characters plus `:`, `_`, and `-`. Pub/Sub is
at-least-once: a reconnect can duplicate an event, so consumers use event-id
and aggregate-version guards before invalidating cache state.

Redis is disabled by default for compatibility. Enable it in the generated
`network.toml` only after network credentials/TLS and a reachable endpoint are
ready. If startup, ping, publish, or subscribe fails, SQL-backed capability
checks continue and the local TTL cache remains safe.

## Node heartbeat

`RedisNodeRegistry` stores an expiring hash per node and a sorted heartbeat
index. It records node id, group, environment, tags, plugin version, API
version, and heartbeat timestamp. Visibility is advisory and expires after the
configured TTL; it must never authorize an entitlement or be used as durable
state.

# Operations (GOAL 06)

## Diagnostics

Paper administrators can run `/easyvip network status` (or the legacy alias
`/easyvip admin network status`) and the narrower `cache`, `redis`, `database`,
`deliveries`, and `nodes` views. `doctor` returns a safe PASS/WARN summary, and
`reconcile <player>` refreshes only the EasyVip-managed LuckPerms namespace.
Diagnostics and reconciliation run off the server thread and expose only node,
pool, cache, event, delivery, and reconciliation counters. Velocity provides
`/easyvip network` and `/easyvip network nodes`, restricted to `easyvip.admin`.

## Health interpretation

* `sql=healthy` means the Hikari pool answered a validation query; SQL remains
  authoritative.
* `redis=disabled|stopped` is a safe degraded mode. Capability reads use the
  local cache/SQL path and event invalidation catches up after reconnect.
* `deliveries=claimed` should fall after leases expire or workers complete;
  repeated `failed` rows require action/configuration review.

Never paste diagnostics containing connection URLs or credentials into tickets.

The public capability API, join/expiration persistence, VIP add/remove/active
mutations, key redemption, and package claims use bounded asynchronous
executors. Bukkit effects are scheduled back to the owning player/global
scheduler; SQL/file claims, administrative key inspection/cleanup and variant operations, and
completion stay off the server thread. Key-code tab completion intentionally
does not query SQL synchronously.
Legacy action scripts that invoke `give_package` still use the synchronous
compatibility API and should be migrated before latency-sensitive production use.

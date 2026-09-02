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
mutations, and diagnostics use bounded asynchronous executors. Legacy key and
package mutations still run through the existing synchronous command path;
schedule those from automation during low-traffic windows until their adapter
is fully asynchronous.

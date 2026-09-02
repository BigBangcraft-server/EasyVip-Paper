# Operations (GOAL 06)

## Diagnostics

Paper administrators can run `/easyvip admin network status` (or the narrower
`cache`, `redis`, `database`, `deliveries`, and `nodes` views). Diagnostics run
off the server thread and expose only node, pool, cache, event, and delivery
counters. Velocity provides `/easyvip network` and `/easyvip network nodes`.

## Health interpretation

* `sql=healthy` means the Hikari pool answered a validation query; SQL remains
  authoritative.
* `redis=disabled|stopped` is a safe degraded mode. Capability reads use the
  local cache/SQL path and event invalidation catches up after reconnect.
* `deliveries=claimed` should fall after leases expire or workers complete;
  repeated `failed` rows require action/configuration review.

Never paste diagnostics containing connection URLs or credentials into tickets.

The public capability API and diagnostics use asynchronous executors. Legacy
administrative mutations still run through the existing synchronous command
path; schedule those from automation during low-traffic windows until the
command adapter is fully asynchronous.

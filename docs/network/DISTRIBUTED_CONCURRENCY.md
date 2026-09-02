# Distributed concurrency contract

The database, not a JVM lock, elects the winner. Each Paper instance uses the
same SQL constraints and transaction boundaries.

| Race | Database control | Loser behavior |
| --- | --- | --- |
| Two players redeem one-use key | `SELECT ... FOR UPDATE` on key, active-claim count, atomic usage update | `NO_USES_LEFT` |
| Same physical key instance | unique `(code, physical_instance_id)` plus consumed-instance update | `ALREADY_USED` |
| Same package | unique `claim_key` (`once:<player>:<package>` for non-repeatable) | `ALREADY_CLAIMED` |
| Repeatable package cooldown | completed-claim timestamp checked in the claim transaction | `COOLDOWN` |
| Two writers update a player | `easyvip_players.version` compare-and-set | `ConcurrentModificationException` |
| Multiple nodes expire one grant | conditional `active` to `expired` update | exactly one `true` transition |

Claims carry a lease. A node that crashes after reservation does not consume a
key/package forever; a later claim can reclaim an expired or failed row. Action
execution happens after reservation and before completion, with explicit
release on failure.

## Failure policy

SQL errors fail closed: no key/package success is reported without a committed
claim, and no stale VIP snapshot is silently overwritten. Redis, proxy, and
delivery transport are not required for this goal and cannot become an
alternate source of truth.

## Test evidence

`SqlConcurrencyTest` runs two-thread races over separate pooled connections
and asserts one winner for key usage, package claims, and expiration, plus a
stale-snapshot CAS failure. H2 is a fast supplemental lab; release acceptance
still requires the same suite against the supported MySQL/MariaDB versions.

# Migration Plan

This plan keeps existing Paper 26.2 behavior available while extracting the
network foundation one bounded goal at a time.

## Completed in GOAL 01

* Audited package ownership, static state, Paper coupling, SQL access, action
  execution, WebStore, integrations, events, scheduling, and tests.
* Added a platform-neutral API seam for capability views, scopes, node identity,
  deterministic merge rules, and versioned domain-event envelopes.
* Added independent resolver/scope tests and documented the before/after graph.
* Kept the existing tier and command paths intact for compatibility.

## GOAL 02 — storage safety (implemented, release gate still open)

1. Introduced migration ledger and a real HikariCP `DataSource`.
2. Added normalized entitlement/grant, key-redemption, package-claim, delivery,
   node, and preference tables without deleting JSON/blob data.
3. Added legacy-to-V2 materialization and V2-first reads with legacy mirroring.
4. Replaced SQL read/modify/write races with transactions, unique keys,
   compare-and-set, leases, and atomic expiration transitions.
5. Added pooled-connection concurrency tests; H2 and MySQL 8.4 are covered,
   while the MariaDB lab remains a compatibility gate.

## GOAL 03 — entitlement engine (implemented)

* Map legacy tiers and player records to temporal grants and typed benefits.
* Add the pure-JDK `ConfiguredEntitlementService` implementation of `EasyVipApi`.
* Define TOML capability configuration, scopes, deterministic merge rules, and cosmetic/convenience classification.
* Add the Paper getter and compatibility projection without changing tier commands or activation.
* Add API/core/bridge tests and plugin usage documentation.

## GOAL 04 — events/cache (implemented)

* Add optional Jedis Pub/Sub transport with bounded pool, timeouts, reconnecting subscription, and sanitized failure handling.
* Add a canonical versioned event codec, duplicate/stale-event processor, bounded Caffeine TTL cache, and metrics.
* Add ephemeral node heartbeat/visibility with no entitlement authority.
* Keep SQL fallback safe when Redis is unavailable and expose asynchronous cold-cache reads.

## GOAL 05 — delivery/Velocity (implemented)

* Add durable SQL delivery ledger with explicit policies, unique idempotency,
  owner leases, crash recovery, and at-least-once external-effect semantics.
* Add `EasyVip-Velocity-1.2.0.jar` and generic `/easyvip`/`/vip` capability commands.
* Make LuckPerms a namespaced projection with asynchronous Paper reconciliation.
* Add a BedWars capability-only consumer fixture and adversarial ledger tests.

## GOAL 06 — hardening

* Run threat-model, load, outage, and distributed race labs.
* Add operational diagnostics, CI gates, migration verification, and final
  deployment/troubleshooting documentation.

## Exit criteria for the next goal

GOAL 05 is complete when the full Paper suite, ledger adversarial tests, and
Velocity compilation/artifact gates are green. Do not call the network ready
until GOAL 06 adds authoritative outage, security, operations, and production
evidence across all acceptance criteria in the program brief.

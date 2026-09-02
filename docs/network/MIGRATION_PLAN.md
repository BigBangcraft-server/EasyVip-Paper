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
5. Added pooled-connection concurrency tests; H2 is supplemental and the
   MySQL/MariaDB lab remains a release gate.

## GOAL 03 — entitlement engine

* Map legacy tiers to grants and benefits.
* Add a core service implementation of `EasyVipApi` backed by storage.
* Define capability configuration and classification (cosmetic/convenience/etc.).
* Add compatibility projection for current tier behavior.

## GOAL 04 — events/cache

* Add Redis as optional invalidation/event transport only.
* Add bounded version-aware local cache and node heartbeat.
* Keep SQL fallback safe when Redis is unavailable.

## GOAL 05 — delivery/Velocity

* Add durable delivery ledger and lease/retry semantics.
* Add Velocity adapter consuming the same API.
* Make LuckPerms a namespaced projection with reconciliation.

## GOAL 06 — hardening

* Run threat-model, load, outage, and distributed race labs.
* Add operational diagnostics, CI gates, migration verification, and final
  deployment/troubleshooting documentation.

## Exit criteria for the next goal

Do not start GOAL 03 until the GOAL 02 focused and full suites are green and a
supported MySQL/MariaDB run is recorded. Do not call the network ready until
the acceptance criteria in the program brief have authoritative concurrency,
outage, and migration evidence.

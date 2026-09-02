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

## GOAL 02 — storage safety (next, not started)

1. Introduce explicit migrations and a real HikariCP `DataSource`.
2. Add normalized entitlement/grant, key-redemption, package-claim, and audit
   tables without deleting the 1.2.0 JSON/blob data.
3. Add reconciliation and verification before switching reads/writes.
4. Replace JVM locks/read-modify-write with SQL transactions, unique keys,
   compare-and-set, and atomic expiration transitions.
5. Add concurrent MySQL/MariaDB-compatible tests; treat H2 as supplemental.

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

Do not start GOAL 02 until the current API tests and the existing full suite are
green. Do not call the network ready until the acceptance criteria in the
program brief have authoritative concurrency, outage, and migration evidence.

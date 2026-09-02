# Migration Plan

This plan keeps existing Paper 26.2 behavior available while extracting the
network foundation one bounded goal at a time. GOAL 06 hardening is currently
in progress; it must close its live outage and production evidence before a
network-ready verdict.

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
5. Added pooled-connection concurrency tests; H2, MySQL 8.4, and MariaDB 11.4
   are covered, including disposable restart labs.

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

Status: implementation in progress.

* Added TLS-safe transport defaults/validation, bounded signed responses,
  fail-closed command dispatch, durable expiry-effect claims, async diagnostics,
  asynchronous join/expiration persistence, architecture/security tests, CI,
  and operations/deployment documentation.
* Disposable MySQL 8.4, MariaDB 11.4, and Redis 7.4 restart labs passed on
  2026-09-02; CI also runs the database compatibility matrix.
* Remaining: production certificate/trust review, proxy/backend trust review,
  performance measurements, and production loader smoke tests.

## Exit criteria for the next goal

GOAL 06 is complete only after authoritative outage, security, operations, and
production evidence covers the acceptance criteria in the program brief. The
current implementation is not a network-ready verdict yet.

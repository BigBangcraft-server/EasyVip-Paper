# Threat Model — GOAL 06 Boundary

This is the foundation audit, not a production-readiness claim. Threats whose
controls belong to GOAL 06 remain explicitly open.

## Assets

* VIP entitlement and duration state;
* single-use/multi-use key state and physical-key instance IDs;
* package claims and WebStore fulfillment records;
* HMAC secrets, WebStore tokens, SQL credentials, and player UUIDs;
* admin-only commands and executable action definitions.

## Trust boundaries

1. Player/command input to command and key services.
2. TOML configuration to action execution.
3. WebStore HTTP payload/signature to fulfillment staging.
4. SQL storage and external LuckPerms/Vault APIs.
5. Future Redis messages and future proxy/backend connections.

## Controls already present

* `CommandAllowlist`, `KeySecurity`, secure random key generation, and command
  permission checks constrain key/action abuse.
* WebStore fulfillment validates HMAC/replay inputs and stages records in SQL.
* Audit details sanitize key-like secrets before persistence.
* Prepared statements are used for the current SQL value writes.
* API value objects reject blank IDs, invalid scopes, and wrong capability types;
  immutable views prevent consumer mutation.

## Risks verified in the current code

* JVM-local locks do not coordinate multiple Paper instances.
* SQL credentials are held by a static manager; lifecycle and secret rotation
  remain operational concerns even though SQL connections now use HikariCP.
* JSON mode still has mutable blob/read-modify-write paths; SQL mode uses the
  normalized V2 tables and CAS/claim transactions.
* Existing Bukkit events are local process events, not durable network events.
* Expiration is scheduled independently on each node; the SQL delivery ledger
  leases the external effect before V2 elects the database transition winner.
* Static bridges and global configuration increase test isolation and lifecycle
  coupling.
* Redis payloads are untrusted transport data; Pub/Sub can duplicate, delay,
  reorder, or disappear during an outage.
* JSON mode remains a compatibility fallback with JVM-local blob locking; SQL
  mode is required for distributed authority. Paper key/package claims now use
  a bounded executor and scheduler-marshalled last-mile actions. Key
  reward/custom actions also keep nested `give_package` claims asynchronous;
  legacy direct action/VIP compatibility calls still use the synchronous API.

## Required mitigations before network use

* SQL atomic claim/version transitions and normalized history tables;
* durable idempotency for key/package/expiration/delivery effects;
* bounded cache and authenticated/versioned Redis envelopes;
* explicit node/proxy trust configuration and secret redaction;
* outage tests proving fail-closed entitlement decisions and SQL authority.

GOAL 06 adds TLS-safe defaults/validation, bounded WebStore responses,
fail-closed command dispatch, asynchronous diagnostics, and durable expiry
effect claims. Disposable database/Redis restart labs now pass; production
certificate trust, proxy trust, and full operational audit remain unproven.

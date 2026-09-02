# Threat Model — GOAL 02 Boundary

This is the foundation audit, not a claim that distributed storage is already
safe. Threats whose controls belong to GOAL 02+ remain explicitly open.

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
* Expiration is scheduled independently on each node; V2 elects one database
  transition winner before local actions.
* Static bridges and global configuration increase test isolation and lifecycle
  coupling.

## Required mitigations before network use

* SQL atomic claim/version transitions and normalized history tables;
* durable idempotency for key/package/expiration/delivery effects;
* bounded cache and authenticated/versioned Redis envelopes;
* explicit node/proxy trust configuration and secret redaction;
* outage tests proving fail-closed entitlement decisions and SQL authority.

No new network or privilege surface is enabled by GOAL 02: SQL remains the
authority, while leases and CAS make cross-node races explicit. Redis, proxy,
delivery, and production failover risks remain open for later goals.

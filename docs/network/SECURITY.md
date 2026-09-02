# Security hardening (GOAL 06)

## Controls

* SQL uses HikariCP; the plugin no longer forces `useSSL=false` or public-key
  retrieval. Production remote MySQL/MariaDB configuration is rejected unless
  it uses `sslMode=VERIFY_IDENTITY` in the JDBC URL and a managed trust store.
* Production Redis endpoints must use `rediss://` unless they are loopback.
  Redis payloads are bounded, versioned, base64-delimited, schema-checked, and
  never treated as entitlement truth.
* WebStore fulfillment validates HMAC responses with constant-time comparison,
  nonce binding, timestamp tolerance, bounded response size, server identity, and
  exact JSON fields. Remote WebStore URLs must use HTTPS.
* Console/player commands are normalized and checked by the configured allowlist;
  failed dispatch now returns failure instead of granting success.
* WebStore/SQL failures log stable exception classes or digests rather than raw
  response bodies, JDBC messages, URLs, or stack traces that could carry secrets.
* Keys use `SecureRandom`, are masked/fingerprinted in audit output, and are
  redeemed through SQL claims and unique constraints.
* LuckPerms reconciliation touches only the `easyvip.managed.*` namespace.

## Deliberate boundaries

Arbitrary configured commands, economy calls, item drops, and LuckPerms writes
are external non-transactional effects. The delivery ledger therefore promises
durable idempotency and at-least-once retry, not magical exactly-once execution.
Keep `command_allowlist_enabled = true`, use environment-provided secrets, and
do not expose backend SQL/Redis ports to untrusted clients.

## Unproven production gates

This repository still needs production certificate/trust-store validation,
proxy/backend trust review, production failover exercises, and a production
canary loader smoke test before the network can be called production-ready.
A disposable Paper `26.2-121` loader smoke passed locally on 2026-09-02;
it used JSON defaults and therefore does not prove production wiring.
Disposable MySQL/MariaDB/Redis restart labs are recorded in the database and
Redis runbooks.

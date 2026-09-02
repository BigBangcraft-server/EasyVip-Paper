# Security hardening (GOAL 06)

## Controls

* SQL uses HikariCP; the plugin no longer forces `useSSL=false` or public-key
  retrieval. Remote MySQL deployments must use `sslMode=VERIFY_IDENTITY` in the
  JDBC URL and a managed trust store.
* Production Redis endpoints must use `rediss://` unless they are loopback.
  Redis payloads are bounded, versioned, base64-delimited, schema-checked, and
  never treated as entitlement truth.
* WebStore fulfillment validates HMAC responses with constant-time comparison,
  nonce binding, timestamp tolerance, bounded response size, server identity, and
  exact JSON fields. Remote WebStore URLs must use HTTPS.
* Console/player commands are normalized and checked by the configured allowlist;
  failed dispatch now returns failure instead of granting success.
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

This repository still needs live certificate/trust-store validation, proxy/backend
trust review, MariaDB failover, Redis restart testing, and a server-loader smoke
test before the network can be called production-ready.

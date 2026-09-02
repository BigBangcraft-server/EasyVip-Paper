# Storage V2

Status: implemented in the Paper 26.2 adapter; SQL remains the authority when
`integrations.sql_enabled = true`.

## Runtime contract

`SqlDatabaseManager` creates one HikariCP pool per plugin lifecycle. Pool size,
minimum idle, connection timeout, idle timeout, max lifetime, and optional leak
detection are read from `integrations.toml`. Every operation borrows and closes
a pooled JDBC connection; no operation opens a `DriverManager` connection.

The schema migration is additive. Version `1` (`storage-v2-foundation`) is
recorded in `easyvip_schema_migrations`; legacy tables remain for rollback and
reconciliation until a later cleanup goal.

## Normalized authority

| Concern | V2 table | Winner rule |
| --- | --- | --- |
| Player snapshot | `easyvip_players` | optimistic `version` CAS |
| Entitlements | `easyvip_entitlement_grants` | deterministic `grant_id`, status transition |
| Active selection | `easyvip_player_preferences` | transaction with player snapshot |
| Key redemption | `easyvip_key_redemptions` | key row lock + unique idempotency/physical key |
| Package claims | `easyvip_package_claims` | unique `claim_key` + lease |
| Deliveries | `easyvip_deliveries` | unique idempotency key and lease fields |
| Node heartbeat | `easyvip_network_nodes` | node primary key, heartbeat update |

VIP writes update the normalized player/grant/preference rows in one
transaction and then mirror the legacy JSON row. Reads prefer V2 and fall back
to the legacy row only when migration has not materialized that player.

## Compatibility boundary

JSON mode is unchanged and remains single-process compatibility storage. SQL
mode routes key and package claims through the V2 ledgers; action execution is
outside the SQL transaction, so a failed action releases its claim and a
crashed node is recovered by lease expiry.

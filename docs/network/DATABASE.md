# Database operations (GOAL 06)

MySQL/MariaDB is the durable source of truth. Normal SQL access goes through the
HikariCP pool; JDBC connections are not opened with `DriverManager`.

SQL credentials should be supplied through `EASYVIP_SQL_USERNAME` and
`EASYVIP_SQL_PASSWORD`; those environment values take precedence over the
legacy `sql_username`/`sql_password` TOML fields. Keep the legacy fields empty
for new deployments. Values are passed to HikariCP and are never logged.

The additive schema creates V2 player/grant/preference tables, key/package claim
tables, the delivery ledger, WebStore ledgers, and node visibility records.
Schema version `1` records the storage foundation and version `2` records the
delivery policy column. Startup migrations are additive and retain legacy rows;
`verifyLegacyVipMigration()` reports player/grant reconciliation.

Important indexes/constraints are unique idempotency keys, key physical-instance
claims, package claim keys, and player/grant lookup indexes. Mutation paths use
transactions, row locks, compare-and-set versions, or affected-row transitions.

Before production migration: take a backup, run the plugin against a staging
copy, verify legacy counts, then exercise key/package/expiration races on the
actual MySQL/MariaDB version.

CI runs the SQL concurrency suite against MySQL 8.4 and MariaDB 11.4 with TLS
required, in addition to the fast H2 suite. This is compatibility evidence, not
a substitute for production failover drills. On 2026-09-02, disposable MySQL
8.4 and MariaDB 11.4 containers were each restarted and the full
`SqlConcurrencyTest` suite passed before and after restart.

The same suite also passed against a disposable MySQL 8.4 server configured
with a generated CA, `sslMode=VERIFY_IDENTITY`, and a JKS trust store; the
server exposed `TLS_AES_128_GCM_SHA256` and passed again after restart. The CA,
key, and trust store were temporary lab files and are not deployment secrets.

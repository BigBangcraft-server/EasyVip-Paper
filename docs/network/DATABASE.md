# Database operations (GOAL 06)

MySQL/MariaDB is the durable source of truth. Normal SQL access goes through the
HikariCP pool; JDBC connections are not opened with `DriverManager`.

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
a substitute for production failover drills.

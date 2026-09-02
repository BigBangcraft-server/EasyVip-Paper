# V1 to V2 migration

The migration is online and additive. It runs during SQL initialization before
the plugin exposes services.

1. Create `easyvip_schema_migrations` and the V2 tables/indexes with
   `CREATE TABLE IF NOT EXISTS`.
2. Add missing compatibility columns (for example `claim_id` on pending
   variants) without dropping or rewriting legacy data.
3. For every row in `easyvip_vips`, create the player row, deterministic grant
   rows, and active-selection preference. Duplicate grant inserts are ignored,
   so restarting migration is safe.
4. Record migration version `1` only after the tables are available.
5. During normal writes, update V2 first and mirror `easyvip_vips` for
   rollback/reconciliation tooling.

The migration never invents entitlements: malformed UUID/JSON or a failed SQL
operation aborts that row and emits an operator-visible log. The legacy row is
kept until the hardening goal provides a backup, reconciliation report, and an
explicit cleanup decision.

## Rollback

Rollback is operational, not a destructive `down` migration: disable SQL mode,
use the still-synchronized JSON tables, and keep the V2 tables for inspection.
Do not blanket-delete V2 grants or reset versions; doing so can erase a claim
or entitlement created after migration.

## Verification

After startup, verify `easyvip_schema_migrations.version = 1`, compare player
and grant counts with `easyvip_vips`, and inspect the audit log for failed
actions. The concurrency suite covers idempotent claims, key-use races, CAS
stale snapshots, and single-winner expiration transitions against H2; MySQL or
MariaDB remains the release-environment gate.

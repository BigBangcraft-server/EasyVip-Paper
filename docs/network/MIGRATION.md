# Migration and rollback

The Paper 26.2 plugin keeps JSON mode and legacy tables for compatibility. SQL
mode adds V2 tables and records migrations without deleting legacy history.

Migration procedure:

1. Back up the plugin data directory and database.
2. Validate TOML and run `SqlDatabaseManager.verifyLegacyVipMigration()` in
   staging.
3. Enable SQL on one canary node, check `/easyvip admin network database`, and
   compare player/grant counts.
4. Roll out to the remaining Paper/Folia nodes with unique `network.node_id`
   values and a matching Velocity node identity.

Rollback means stopping the node and restoring the prior artifact/config only
   after preserving new SQL rows. Do not blindly downgrade or delete V2 tables;
   incompatible data requires an explicit reconciliation plan.

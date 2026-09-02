# LuckPerms projection (GOAL 05)

EasyVip is the authority; LuckPerms is a repairable projection. Capability
nodes managed by EasyVip use the reserved prefix `easyvip.managed.`. The
reconciler computes the desired set from the resolved `PlayerEntitlementView`,
adds missing managed nodes, and removes only stale nodes inside that prefix.
Unrelated LuckPerms permissions/groups are never selected for removal.

Paper runs reconciliation asynchronously after a player joins. A missing or
temporarily unavailable LuckPerms user fails the projection operation without
changing entitlement state. Existing legacy tier-group actions remain
compatible while consumers migrate to capability nodes.

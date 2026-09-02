# Delivery ledger (GOAL 05)

`easyvip_deliveries` is the durable idempotency boundary for effects that
must be attempted across nodes. A request records the player, optional grant,
benefit, scope, explicit `DeliveryPolicy`, unique idempotency key, owner node,
lease, attempt count, and final status.

The protocol is claim -> execute -> complete. The SQL unique constraint elects
one idempotency winner; an active lease returns `IN_PROGRESS`; an expired lease
can be claimed by another node. Completion is owner- and lease-checked and is
idempotent after `DELIVERED`. A failure releases the lease as `FAILED`, allowing
an explicit retry.

Supported policies are `ONCE`, `ONCE_PER_GRANT`, `ONCE_PER_DAY`,
`ONCE_PER_PERIOD`, `ON_JOIN`, `ON_GROUP_JOIN`, and `MANUAL_CLAIM`. The caller
must choose a policy whose key/window semantics are unambiguous; the ledger
does not claim impossible distributed exactly-once execution.

Arbitrary console commands, item grants, and economy calls are external side
effects. If a node crashes after the side effect and before completion, the
lease retry is at-least-once. Integrations should use idempotent downstream
operations or a provider-specific reconciliation step. Package SQL flows now
use the ledger around their action execution; legacy JSON mode remains local.

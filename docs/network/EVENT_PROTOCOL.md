# Distributed event protocol

`DomainEvent` is the canonical envelope:

| Field | Meaning |
| --- | --- |
| `eventId` | globally unique idempotency key |
| `type` | allow-listed `DomainEventType` |
| `schemaVersion` | currently `1` |
| `aggregateId` | player UUID for entitlement events |
| `aggregateVersion` | SQL optimistic version; `0` means unknown/local legacy event |
| `originatingNode` | normalized node id |
| `occurredAt` | UTC timestamp |
| `attributes` | bounded string metadata, no secrets |

Redis payloads use a canonical `ev1` envelope with URL-safe Base64 fields and
lexicographically sorted attributes. The codec rejects unknown schema versions,
malformed UUIDs/timestamps, duplicate attributes, and payloads over 64 KiB.

`VersionAwareEventProcessor` first deduplicates `eventId`, then drops an event
whose aggregate version is less than or equal to the newest observed version.
Only accepted events reach cache invalidation. Duplicate, delayed, and
out-of-order delivery therefore converges without trusting Redis as state.

Current event names include entitlement grant/update/revoke/expire,
capabilities changed, package claimed, and key redeemed. Future producers must
write the SQL mutation first and publish only the resulting event; a failed
publish is an observability/recovery concern, not a reason to roll back SQL.

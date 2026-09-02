# EasyVip Network Architecture

Status: GOAL 05 delivery ledger, Velocity adapter, and projections; Paper API `26.2.build.121-stable`, Java 25.

## Audit of the current HEAD

The repository is one Gradle Java project (`br.com.pedrodalben.easyvip`) with
42 production classes and 24 test classes. The shaded Paper plugin is the only
runtime artifact. The current commit audited for this document includes the
Storage V2 transition on top of the Paper 26.2 upgrade.

### Runtime flow

1. `EasyVipPaperPlugin#onEnable` loads static TOML configuration, initializes
   persistence, installs static platform/economy bridges, starts web-store
   services, registers commands/listeners, and starts expiration.
2. `PlayerListener` routes joins and physical-key interactions to static
   services.
3. `EasyVipCommandHandler` routes all player/admin commands to `VipService`,
   `KeyService`, `PackageService`, `PersistenceManager`, and web-store code.
4. `ActionExecutor` performs last-mile Bukkit actions and emits Bukkit events.

### Package map

| Package | Responsibility | Platform coupling |
| --- | --- | --- |
| `api` | New stable capability, scope, node, and event contracts | none |
| `cache` | Bounded local resolved-view cache | Caffeine; no authority |
| `model` | Mutable JSON/SQL persistence DTOs | none |
| `config` | TOML parser/writer and global config objects | none |
| `service` | VIP, key, package, and expiration workflows | Bukkit in key/package/VIP paths |
| `persistence` | JSON cache/files and SQL access | JDBC/Gson; static state |
| `action` | Action scripting, placeholders, random pools | Bukkit and global bridges |
| `platform` | Paper, Vault, LuckPerms, text adapters | Bukkit and external APIs |
| `event` | Bukkit event wrappers | Bukkit |
| `listener`, `command`, `paper` | Paper adapter lifecycle and UI | Bukkit/Paper |
| `webstore` | HTTP sync and fulfillment ledger | HTTP, persistence, Bukkit via actions |
| `redis` | Pub/Sub event transport and ephemeral node registry | Jedis; optional |
| `delivery` | Durable claim/lease/complete contract | SQL-backed; at-least-once effects |
| `projection` | Namespaced external permission projection | platform adapter; no authority |
| `integration` | Reference capability consumers | API only |

### State and concurrency findings

* `EasyVipConfig`, `PersistenceManager`, `SqlDatabaseManager`, the bridges,
  `ExpirationService`, and `EasyVipPaperPlugin` use process-wide static state.
* JSON state is held in `HashMap`/`ArrayList` fields protected by one JVM-local
  `ReentrantReadWriteLock` and flushed by a single executor.
* SQL mode uses one HikariCP pool per lifecycle and borrows a connection for
  each operation; JSON mode retains its JVM-local compatibility lock.
* SQL VIP state is normalized into player, grant, and preference rows with an
  optimistic version token; the legacy JSON row is mirrored for rollback.
* SQL key and package claims use row locks, unique keys, and lease/status
  transitions. JVM locks remain only around legacy CRUD paths.
* Expiration schedules on every Paper node, but the V2 conditional transition
  elects one database winner before local lifecycle actions run.
* Web-store fulfillment already has transactional staging/claim fields and
  HMAC/replay checks; it remains an adapter until storage V2.
* `ConfiguredEntitlementService` resolves active `Grant` records into typed
  capabilities; `LegacyVipCapabilityBridge` projects current tiers without
  changing activation semantics.
* `CachedEntitlementApi` fronts those views with bounded Caffeine TTL storage;
  `RedisEventBus` and `RedisNodeRegistry` are optional invalidation/visibility
  adapters and never authoritative state.
* `SqlDeliveryLedger` uses unique idempotency keys and expiring node leases;
  `EasyVip-Velocity` consumes the same API/core/storage artifact and exposes
  generic capability commands.

### Dependency direction after this goal

The new `api` package is a platform-neutral seam. It imports only JDK types.
The existing packages remain compatible adapters around it; no existing Paper
behavior was moved in this foundation change.

```text
                 easyvip-api (JDK only)
                         ^
                 easyvip-core
                    ^          ^
         easyvip-storage-sql  easyvip-messaging-redis
                    ^                    ^
       easyvip-paper       easyvip-velocity
```

The root project remains the compatibility build, with a separate `velocity`
source set and `velocityJar` artifact. A full Gradle-project split is still
deferred until the contracts have independent consumers and migration tests.

## Target state

* `easyvip-api` owns the public interfaces and immutable value types.
* `easyvip-core` owns entitlement/benefit decisions and domain events without
  Bukkit, Paper, Velocity, SQL, or Redis imports.
* `easyvip-storage-sql` owns durable transactional repositories.
* `easyvip-paper` and `easyvip-velocity` own platform adapters and scheduling.
* Redis is an event/cache adapter, never authoritative persistence.

The current API contracts provide the extraction seam: callers ask for a
capability in a `ScopeContext`, not for a tier name. The resolver and temporal
grant evaluation are deterministic and testable without a server runtime.

## 26.2 compatibility

Paper API 26.2 requires Java 25. The plugin manifests advertise API 26.2 and the
Gradle toolchain is Java 25. Existing command, persistence, WebStore, Vault,
LuckPerms, Folia, and Bukkit event paths remain in the original adapter module.

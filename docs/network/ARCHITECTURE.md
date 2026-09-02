# EasyVip Network Architecture

Status: GOAL 01 foundation, Paper API `26.2.build.121-stable`, Java 25.

## Audit of the current HEAD

The repository is one Gradle Java project (`br.com.pedrodalben.easyvip`) with
42 production classes and 23 test classes. The shaded Paper plugin is the only
runtime artifact. The current commit audited for this document is the 26.2
upgrade commit.

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
| `model` | Mutable JSON/SQL persistence DTOs | none |
| `config` | TOML parser/writer and global config objects | none |
| `service` | VIP, key, package, and expiration workflows | Bukkit in key/package/VIP paths |
| `persistence` | JSON cache/files and SQL access | JDBC/Gson; static state |
| `action` | Action scripting, placeholders, random pools | Bukkit and global bridges |
| `platform` | Paper, Vault, LuckPerms, text adapters | Bukkit and external APIs |
| `event` | Bukkit event wrappers | Bukkit |
| `listener`, `command`, `paper` | Paper adapter lifecycle and UI | Bukkit/Paper |
| `webstore` | HTTP sync and fulfillment ledger | HTTP, persistence, Bukkit via actions |

### State and concurrency findings

* `EasyVipConfig`, `PersistenceManager`, `SqlDatabaseManager`, the bridges,
  `ExpirationService`, and `EasyVipPaperPlugin` use process-wide static state.
* JSON state is held in `HashMap`/`ArrayList` fields protected by one JVM-local
  `ReentrantReadWriteLock` and flushed by a single executor.
* SQL access opens connections with `DriverManager` for every operation. HikariCP
  is declared but no `HikariDataSource` is constructed in the current code.
* SQL VIP state is one `vips_data MEDIUMTEXT` row per player and is updated as a
  read/modify/write blob. Package usage has the same whole-map replacement
  shape.
* Key redemption uses `ConcurrentHashMap` plus `synchronized` per code. This
  serializes one JVM only; SQL is not currently the cross-node winner election.
* Expiration schedules on every Paper node and invokes local lifecycle actions.
  There is no distributed transition/lease in this goal.
* Web-store fulfillment already has transactional staging/claim fields and
  HMAC/replay checks; it remains an adapter until storage V2.

### Dependency direction after this goal

The new `api` package is a platform-neutral seam. It imports only JDK types.
The existing packages remain compatible adapters around it; no existing Paper
behavior was moved in this foundation change.

```text
                 easyvip-api (JDK only)
                         ^
                 easyvip-core (next extraction)
                    ^          ^
         easyvip-storage-sql  easyvip-messaging-redis (future)
                    ^
       easyvip-paper       easyvip-velocity (future adapters)
```

The physical Gradle split is intentionally deferred until the contracts have a
consumer and storage V2 is designed. This avoids a compatibility-only module
shuffle while the existing plugin remains production-facing.

## Target state

* `easyvip-api` owns the public interfaces and immutable value types.
* `easyvip-core` owns entitlement/benefit decisions and domain events without
  Bukkit, Paper, Velocity, SQL, or Redis imports.
* `easyvip-storage-sql` owns durable transactional repositories.
* `easyvip-paper` and `easyvip-velocity` own platform adapters and scheduling.
* Redis is an event/cache adapter, never authoritative persistence.

The current API contracts provide the first extraction seam: callers ask for a
capability in a `ScopeContext`, not for a tier name. The resolver is deterministic
and testable without a server runtime.

## 26.2 compatibility

Paper API 26.2 requires Java 25. The plugin manifests advertise API 26.2 and the
Gradle toolchain is Java 25. Existing command, persistence, WebStore, Vault,
LuckPerms, Folia, and Bukkit event paths remain in the original adapter module.

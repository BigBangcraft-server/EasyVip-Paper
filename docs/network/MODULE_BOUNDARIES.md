# Module Boundaries

## Public contracts

`br.com.pedrodalben.easyvip.api` is the only package intended for external
plugin consumers. It contains:

* `EasyVipApi`, `EntitlementService`, and `BenefitService` interfaces;
* immutable `PlayerEntitlementView`, `EffectiveEntitlementView`, `Capability`,
  `Benefit`, `Entitlement`, `Grant`, `CapabilityValue`, `CapabilityGrant`, and
  `DefaultCapabilityResolver` domain types;
* `Scope`, `ScopeType`, `ScopeContext`, and `NetworkNodeIdentity`;
* versioned `DomainEvent`/`DomainEventType` envelopes.

This package has no Bukkit/Paper/Folia/Velocity/SQL/Redis dependency. Capability
names are strings (`queue.priority`, `minigame.map_vote`), so minigames do not
need to know whether a player owns Gold or Diamond.

## Intended modules

| Module | May depend on | Must not expose |
| --- | --- | --- |
| `easyvip-api` | JDK | platform/storage classes |
| `easyvip-core` | API, JDK | Bukkit/Paper/Velocity/JDBC |
| `easyvip-storage-sql` | API/core, JDBC/Hikari | Bukkit event/player types |
| `easyvip-messaging-redis` | API/core, Redis client | authoritative state |
| `easyvip-paper` | API/core/storage, Paper | SQL details to consumers |
| `easyvip-velocity` | API/core/storage, Velocity | Paper classes |

## Current compatibility placement

The repository is still a single Gradle project. Existing `action`, `command`,
`config`, `event`, `listener`, `paper`, `persistence`, `platform`, `service`,
and `webstore` packages are the compatibility adapter. Moving them between
projects now would create no user-visible capability and increase migration
risk, so the API seam is introduced first. A future Gradle split must move code
only after dependency checks prove the boundary.

## Composition rule

Platform entry points should construct services and pass dependencies through
constructors. New core code must not add static service registries. Existing
static services are compatibility debt tracked for the next extraction.

## Dependency checks for future module extraction

Before splitting Gradle projects, add an architecture test that fails if
`easyvip-api` imports `org.bukkit`, `com.velocitypowered`, `java.sql`, or Redis
client packages. The current API contract test exercises the same boundary by
compiling and resolving scopes/capabilities with only JDK values.

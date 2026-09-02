# Velocity adapter (GOAL 05)

`EasyVip-Velocity-1.2.0.jar` is built by the `velocityJar` Gradle task. It
loads the same platform-neutral API/core/storage/cache classes as Paper and
uses the configured SQL source of truth. The Velocity API is compile-only;
the proxy supplies it at runtime. The current build targets Java 25 and the
PaperMC-documented `velocity-api:4.1.1-SNAPSHOT` dependency.

The adapter registers `/easyvip` and `/vip`:

* `/easyvip info` resolves the connected player's capabilities asynchronously;
* `/easyvip capability <name>` checks a generic capability;
* `/easyvip queue`, `/reserved`, and `/maintenance` expose queue priority,
  reserved-slot, and maintenance-bypass capabilities;
* `/easyvip network [nodes]` reports cache/Redis status or advisory nodes.

Redis remains optional. When enabled, the proxy subscribes to invalidation
events and publishes through the same bounded transport as Paper. Scheduler
tasks are asynchronous on Velocity; SQL/Redis failures become failed futures
or sanitized warnings and never grant a capability.

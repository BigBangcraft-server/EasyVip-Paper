# Troubleshooting

* **`sql=unhealthy`**: inspect DB reachability, credentials, TLS mode, pool
  limits, and server-side connection limits; Redis cannot repair SQL failures.
* **`redis=stopped`**: inspect URI/ACL/TLS and Redis logs. The plugin degrades to
  SQL/cache and reconnects without changing entitlement state.
* **Stuck deliveries**: inspect `easyvip_deliveries` by status and lease expiry;
  an expired claim is retryable, while repeated `FAILED` rows indicate an
  external action or configuration problem.
* **LuckPerms drift**: run a player join/reconciliation and inspect only
  `easyvip.managed.*`; unrelated nodes are intentionally untouched.
* **WebStore rejection**: verify key ID, environment secret, timestamp tolerance,
  server ID, HTTPS, and the signed response fields. Never log or paste tokens.
* **Paper 26.2 load failure**: confirm Java 25, `paper-plugin.yml` API `26.2`,
  and the Paper artifact rather than the Velocity artifact is installed.

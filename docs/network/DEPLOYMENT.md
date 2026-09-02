# Deployment checklist

* Run Java 25 with Paper/Purpur/Folia API `26.2`.
* Set a unique `network.node_id`, group, environment, and tags on every node.
* Use MySQL/MariaDB with least-privilege credentials and
  `sslMode=VERIFY_IDENTITY` for remote connections.
* Supply SQL credentials with `EASYVIP_SQL_USERNAME` and
  `EASYVIP_SQL_PASSWORD`; leave inline TOML credentials empty.
* Use `rediss://` with ACL credentials for remote Redis; keep SQL authoritative.
* Supply WebStore/HMAC secrets through environment variables and use HTTPS.
* Keep `command_allowlist_enabled = true` and review every configured command.
* Install the Paper JAR on backend nodes and the Velocity JAR only on the proxy.
* Verify diagnostics and player join/capability behavior on a canary before
  horizontal rollout.

## Loader smoke evidence

On 2026-09-02, the built Paper artifact was loaded by Paper `26.2-121` under
Temurin Java 25. Paper recognized `EasyVip (1.2.0)`, advertised API
`26.2.build.121-stable`, and enabled the plugin successfully in 47 ms using
the default JSON configuration. This is disposable loader evidence only; it
does not replace a production canary with the real SQL, Redis, proxy, and
trust-store configuration.

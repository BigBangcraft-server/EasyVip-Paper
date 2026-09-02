# Deployment checklist

* Run Java 25 with Paper/Purpur/Folia API `26.2`.
* Set a unique `network.node_id`, group, environment, and tags on every node.
* Use MySQL/MariaDB with least-privilege credentials and
  `sslMode=VERIFY_IDENTITY` for remote connections.
* Use `rediss://` with ACL credentials for remote Redis; keep SQL authoritative.
* Supply WebStore/HMAC secrets through environment variables and use HTTPS.
* Keep `command_allowlist_enabled = true` and review every configured command.
* Install the Paper JAR on backend nodes and the Velocity JAR only on the proxy.
* Verify diagnostics and player join/capability behavior on a canary before
  horizontal rollout.

package br.com.pedrodalben.easyvip.persistence;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.delivery.DeliveryRequest;
import br.com.pedrodalben.easyvip.delivery.DeliveryStatus;
import br.com.pedrodalben.easyvip.model.*;
import br.com.pedrodalben.easyvip.webstore.model.FulfillmentRecord;
import br.com.pedrodalben.easyvip.webstore.model.FulfillmentItemRecord;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SqlDatabaseManager {

    private static final Gson GSON = new GsonBuilder().create();
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    private static volatile HikariDataSource dataSource;
    private static volatile boolean initialized = false;

    private SqlDatabaseManager() {
    }

    public static synchronized void initialize(String dbUrl, String dbUsername, String dbPassword) {
        shutdown();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(Objects.requireNonNull(dbUrl, "dbUrl"));
        config.setUsername(dbUsername == null ? "" : dbUsername);
        config.setPassword(dbPassword == null ? "" : dbPassword);
        config.setMaximumPoolSize(Math.max(1, EasyVipConfig.integrations.sqlPoolSize));
        config.setMinimumIdle(Math.max(0, Math.min(EasyVipConfig.integrations.sqlMinimumIdle,
                Math.max(1, EasyVipConfig.integrations.sqlPoolSize))));
        config.setConnectionTimeout(Math.max(250, EasyVipConfig.integrations.sqlConnectionTimeoutSeconds * 1000L));
        config.setIdleTimeout(Math.max(10_000L, EasyVipConfig.integrations.sqlIdleTimeoutSeconds * 1000L));
        config.setMaxLifetime(Math.max(30_000L, EasyVipConfig.integrations.sqlMaxLifetimeMinutes * 60_000L));
        if (EasyVipConfig.integrations.sqlLeakDetectionThresholdSeconds > 0) {
            config.setLeakDetectionThreshold(EasyVipConfig.integrations.sqlLeakDetectionThresholdSeconds * 1000L);
        }
        config.setPoolName("EasyVip-SQL");
        config.addDataSourceProperty("useSSL", "false");
        config.addDataSourceProperty("allowPublicKeyRetrieval", "true");
        dataSource = new HikariDataSource(config);
        try {
            createTables();
            migrateLegacyVipData();
            MigrationVerification verification = verifyLegacyVipMigration();
            if (!verification.complete()) {
                System.err.println("[EasyVip-SQL] Legacy migration verification mismatch: " + verification);
            }
            initialized = true;
        } catch (RuntimeException e) {
            shutdown();
            throw e;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isHealthy() {
        if (!initialized) {
            return false;
        }
        HikariDataSource pool = dataSource;
        if (pool == null || pool.isClosed()) {
            return false;
        }
        try (Connection conn = pool.getConnection()) {
            return conn != null && conn.isValid(2);
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Health check failed: " + e.getMessage());
            return false;
        }
    }

    public static synchronized void shutdown() {
        initialized = false;
        HikariDataSource pool = dataSource;
        dataSource = null;
        if (pool != null) {
            pool.close();
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection conn) throws SQLException;
    }

    public static <T> T withConnection(SqlWork<T> work) {
        try (Connection conn = getConnection()) {
            return work.apply(conn);
        } catch (SQLException e) {
            throw new RuntimeException("SQL operation failed: " + e.getMessage(), e);
        }
    }

    private static Connection getConnection() throws SQLException {
        HikariDataSource pool = dataSource;
        if (pool == null || pool.isClosed()) {
            throw new SQLException("SQL datasource is not initialized");
        }
        return pool.getConnection();
    }

    // ─── Table Creation ──────────────────────────────────────

    private static void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_schema_migrations (
                    version INT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    applied_at BIGINT NOT NULL
                )
            """);

            // V2 tables are additive. Legacy tables remain available for read-only migration/reconciliation.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_players (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    player_name VARCHAR(255) NOT NULL DEFAULT '',
                    active_entitlement_id VARCHAR(255) DEFAULT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL DEFAULT 0,
                    updated_at BIGINT NOT NULL DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_entitlement_grants (
                    grant_id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    entitlement_id VARCHAR(255) NOT NULL,
                    starts_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL DEFAULT -1,
                    status VARCHAR(32) NOT NULL DEFAULT 'active',
                    active BOOLEAN NOT NULL DEFAULT FALSE,
                    pending_activate BOOLEAN NOT NULL DEFAULT FALSE,
                    source VARCHAR(64) NOT NULL DEFAULT 'legacy',
                    source_reference VARCHAR(255) DEFAULT NULL,
                    created_by VARCHAR(255) DEFAULT NULL,
                    created_at BIGINT NOT NULL DEFAULT 0,
                    updated_at BIGINT NOT NULL DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0
                )
            """);
            ensureIndex(conn, "easyvip_entitlement_grants", "ix_easyvip_entitlement_player", "player_uuid");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_player_preferences (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    active_entitlement_id VARCHAR(255) DEFAULT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    updated_at BIGINT NOT NULL DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_key_redemptions (
                    redemption_id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(255) NOT NULL,
                    code VARCHAR(255) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    physical_instance_id VARCHAR(255) DEFAULT NULL,
                    status VARCHAR(32) NOT NULL,
                    claimed_at BIGINT NOT NULL,
                    lease_expires_at BIGINT NOT NULL,
                    completed_at BIGINT DEFAULT NULL,
                    failure_code VARCHAR(80) DEFAULT NULL,
                    UNIQUE (idempotency_key),
                    UNIQUE (code, physical_instance_id)
                )
            """);
            ensureIndex(conn, "easyvip_key_redemptions", "ix_easyvip_key_redemptions_code", "code, status");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_package_claims (
                    claim_id VARCHAR(36) PRIMARY KEY,
                    claim_key VARCHAR(512) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    package_id VARCHAR(255) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    claimed_at BIGINT NOT NULL,
                    lease_expires_at BIGINT NOT NULL,
                    completed_at BIGINT DEFAULT NULL,
                    failure_code VARCHAR(80) DEFAULT NULL,
                    UNIQUE (claim_key),
                    UNIQUE (idempotency_key)
                )
            """);
            ensureIndex(conn, "easyvip_package_claims", "ix_easyvip_package_claims_lookup", "player_uuid, package_id, status, claimed_at");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_deliveries (
                    delivery_id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    grant_id VARCHAR(36) DEFAULT NULL,
                    benefit_id VARCHAR(255) NOT NULL,
                    scope_type VARCHAR(32) NOT NULL,
                    scope_value VARCHAR(255) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    policy VARCHAR(32) NOT NULL DEFAULT 'ONCE',
                    status VARCHAR(32) NOT NULL,
                    claimed_by_node VARCHAR(255) DEFAULT NULL,
                    lease_expires_at BIGINT DEFAULT NULL,
                    attempts INT NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    delivered_at BIGINT DEFAULT NULL,
                    failure_code VARCHAR(80) DEFAULT NULL,
                    UNIQUE (idempotency_key)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_network_nodes (
                    node_id VARCHAR(255) PRIMARY KEY,
                    node_group VARCHAR(255) NOT NULL,
                    environment VARCHAR(255) NOT NULL,
                    tags_json TEXT,
                    plugin_version VARCHAR(64) NOT NULL DEFAULT '',
                    api_version VARCHAR(64) NOT NULL DEFAULT '',
                    started_at BIGINT NOT NULL DEFAULT 0,
                    last_heartbeat_at BIGINT NOT NULL DEFAULT 0
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_vips (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    player_name VARCHAR(255) NOT NULL DEFAULT '',
                    last_observed_active_vip VARCHAR(255) DEFAULT NULL,
                    vips_data MEDIUMTEXT
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_keys (
                    code VARCHAR(255) PRIMARY KEY,
                    type VARCHAR(50) NOT NULL,
                    tier_id VARCHAR(255) DEFAULT NULL,
                    duration VARCHAR(100) DEFAULT NULL,
                    reward_key_id VARCHAR(255) DEFAULT NULL,
                    max_uses INT NOT NULL DEFAULT 1,
                    used_count INT NOT NULL DEFAULT 0,
                    bound_player_uuid VARCHAR(36) DEFAULT NULL,
                    created_time BIGINT NOT NULL DEFAULT 0,
                    expiry_time BIGINT NOT NULL DEFAULT -1,
                    used_by_json MEDIUMTEXT,
                    last_used_at_by_json MEDIUMTEXT,
                    actions_json MEDIUMTEXT,
                    consumed_instances_json MEDIUMTEXT
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS webstore_fulfillments (
                    fulfillment_id VARCHAR(36) PRIMARY KEY,
                    order_id VARCHAR(255) NOT NULL,
                    origin_server_id VARCHAR(255) NOT NULL,
                    server_id VARCHAR(255) NOT NULL,
                    minecraft_uuid VARCHAR(36) NOT NULL,
                    minecraft_username VARCHAR(255) NOT NULL DEFAULT '',
                    payload_digest VARCHAR(128) NOT NULL,
                    status VARCHAR(50) NOT NULL DEFAULT 'pending',
                    request_key_id VARCHAR(255) DEFAULT NULL,
                    claim_token VARCHAR(255) DEFAULT NULL,
                    created_at BIGINT NOT NULL DEFAULT 0,
                    claimed_at BIGINT DEFAULT NULL,
                    lease_expires_at BIGINT DEFAULT NULL,
                    completed_at BIGINT DEFAULT NULL,
                    failed_at BIGINT DEFAULT NULL,
                    failure_code VARCHAR(80) DEFAULT NULL,
                    error_message VARCHAR(255) DEFAULT NULL,
                    updated_at BIGINT NOT NULL DEFAULT 0
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS webstore_fulfillment_items (
                    line_item_id VARCHAR(36) PRIMARY KEY,
                    fulfillment_id VARCHAR(36) NOT NULL,
                    product_sku VARCHAR(255) NOT NULL,
                    quantity INT NOT NULL DEFAULT 1,
                    key_code VARCHAR(255) DEFAULT NULL,
                    key_fingerprint VARCHAR(255) DEFAULT NULL,
                    status VARCHAR(50) NOT NULL DEFAULT 'pending',
                    created_at BIGINT NOT NULL DEFAULT 0,
                    updated_at BIGINT NOT NULL DEFAULT 0
                )
            """);

            // Schema migrations for existing databases
            ensureColumnExists(conn, "easyvip_keys", "consumed_instances_json", "MEDIUMTEXT");
            ensureColumnExists(conn, "easyvip_deliveries", "policy", "VARCHAR(32) NOT NULL DEFAULT 'ONCE'");
            recordMigration(conn, 2, "delivery-ledger-policy");
            ensureColumnExists(conn, "easyvip_package_claims", "claim_key", "VARCHAR(512) DEFAULT NULL");
            stmt.execute("UPDATE easyvip_package_claims SET claim_key = CONCAT('legacy:', claim_id) WHERE claim_key IS NULL OR claim_key = ''");
            ensureUniqueIndex(conn, "easyvip_package_claims", "ux_easyvip_package_claim_key", "claim_key");
            ensureColumnExists(conn, "webstore_fulfillments", "server_id", "VARCHAR(255) NOT NULL DEFAULT ''");
            ensureColumnExists(conn, "webstore_fulfillments", "origin_server_id", "VARCHAR(255) NOT NULL DEFAULT ''");
            ensureColumnExists(conn, "webstore_fulfillments", "claimed_at", "BIGINT DEFAULT NULL");
            ensureColumnExists(conn, "webstore_fulfillments", "claim_token", "VARCHAR(255) DEFAULT NULL");
            ensureColumnExists(conn, "webstore_fulfillments", "lease_expires_at", "BIGINT DEFAULT NULL");
            ensureColumnExists(conn, "webstore_fulfillments", "completed_at", "BIGINT DEFAULT NULL");
            ensureColumnExists(conn, "webstore_fulfillments", "failed_at", "BIGINT DEFAULT NULL");
            ensureColumnExists(conn, "webstore_fulfillments", "failure_code", "VARCHAR(80) DEFAULT NULL");
            ensureColumnExists(conn, "webstore_fulfillments", "error_message", "VARCHAR(255) DEFAULT NULL");
            ensureColumnExists(conn, "webstore_fulfillments", "updated_at", "BIGINT NOT NULL DEFAULT 0");
            ensureColumnExists(conn, "webstore_fulfillment_items", "updated_at", "BIGINT NOT NULL DEFAULT 0");
            ensureUniqueIndex(conn, "webstore_fulfillments", "ux_webstore_fulfillments_fulfillment_id", "fulfillment_id");
            ensureUniqueIndex(conn, "webstore_fulfillment_items", "ux_webstore_fulfillment_items_line_item_id", "line_item_id");
            ensureUniqueIndex(conn, "webstore_fulfillment_items", "ux_webstore_fulfillment_items_key_code", "key_code");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_pending_variants (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    package_id VARCHAR(255) NOT NULL,
                    variants_json TEXT,
                    timestamp BIGINT NOT NULL DEFAULT 0,
                    claim_id VARCHAR(36) DEFAULT NULL
                )
            """);
            ensureColumnExists(conn, "easyvip_pending_variants", "claim_id", "VARCHAR(36) DEFAULT NULL");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_package_usage (
                    player_uuid VARCHAR(36) NOT NULL,
                    package_id VARCHAR(255) NOT NULL,
                    usage_count BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (player_uuid, package_id)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_audit_logs (
                    id VARCHAR(36) PRIMARY KEY,
                    timestamp BIGINT NOT NULL DEFAULT 0,
                    operator VARCHAR(255) DEFAULT NULL,
                    action VARCHAR(255) DEFAULT NULL,
                    details TEXT
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_fulfillments (
                    fulfillment_id VARCHAR(36) PRIMARY KEY,
                    order_id VARCHAR(255) NOT NULL,
                    minecraft_uuid VARCHAR(36) NOT NULL,
                    minecraft_username VARCHAR(255),
                    payload_digest VARCHAR(64),
                    status VARCHAR(50) NOT NULL DEFAULT 'pending',
                    request_key_id VARCHAR(255),
                    created_at BIGINT NOT NULL DEFAULT 0,
                    completed_at BIGINT DEFAULT NULL,
                    failed_at BIGINT DEFAULT NULL,
                    failure_code VARCHAR(50) DEFAULT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS easyvip_fulfillment_items (
                    line_item_id VARCHAR(36) PRIMARY KEY,
                    fulfillment_id VARCHAR(36) NOT NULL,
                    product_sku VARCHAR(255) NOT NULL,
                    quantity INT NOT NULL DEFAULT 1,
                    key_code VARCHAR(255),
                    key_fingerprint VARCHAR(255),
                    status VARCHAR(50) NOT NULL DEFAULT 'pending',
                    created_at BIGINT NOT NULL DEFAULT 0
                )
            """);

            recordMigration(conn, 1, "storage-v2-foundation");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database tables", e);
        }
    }

    private static void recordMigration(Connection conn, int version, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO easyvip_schema_migrations (version, name, applied_at) VALUES (?, ?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!isDuplicateKeyError(e)) {
                throw e;
            }
        }
    }

    public record MigrationVerification(long legacyPlayers, long migratedPlayers,
                                        long legacyGrants, long migratedGrants, boolean complete) {
    }

    /** Compares legacy rows with materialized V2 rows without mutating either source. */
    public static MigrationVerification verifyLegacyVipMigration() {
        try (Connection conn = getConnection()) {
            long legacyPlayers = count(conn, "SELECT COUNT(*) FROM easyvip_vips");
            long migratedPlayers = count(conn, """
                    SELECT COUNT(*) FROM easyvip_vips legacy
                    WHERE EXISTS (SELECT 1 FROM easyvip_players v2 WHERE v2.player_uuid = legacy.player_uuid)
                    """);
            long migratedGrants = count(conn, "SELECT COUNT(*) FROM easyvip_entitlement_grants");
            long legacyGrants = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT vips_data FROM easyvip_vips")) {
                while (rs.next()) {
                    String json = rs.getString(1);
                    if (json != null && !json.isBlank()) {
                        Type type = new TypeToken<Map<String, PlayerVipRecord>>() {}.getType();
                        Map<String, PlayerVipRecord> values = GSON.fromJson(json, type);
                        if (values != null) legacyGrants += values.size();
                    }
                }
            }
            boolean complete = migratedPlayers >= legacyPlayers && migratedGrants >= legacyGrants;
            return new MigrationVerification(legacyPlayers, migratedPlayers, legacyGrants, migratedGrants, complete);
        } catch (SQLException | RuntimeException e) {
            return new MigrationVerification(0, 0, 0, 0, false);
        }
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static void ensureIndex(Connection conn, String table, String indexName, String columns) {
        try (ResultSet indexes = conn.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (indexes.next()) {
                String existing = indexes.getString("INDEX_NAME");
                if (existing != null && existing.equalsIgnoreCase(indexName)) return;
            }
            try (Statement create = conn.createStatement()) {
                create.execute("CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")");
            }
        } catch (SQLException e) {
            if (!isDuplicateKeyError(e)) {
                System.err.println("[EasyVip-SQL] Failed to ensure index " + table + "." + indexName + ": " + e.getMessage());
            }
        }
    }

    private static void ensureUniqueIndex(Connection conn, String table, String indexName, String column) {
        try (ResultSet indexes = conn.getMetaData().getIndexInfo(null, null, table, true, false)) {
            while (indexes.next()) {
                String existing = indexes.getString("INDEX_NAME");
                if (existing != null && existing.equalsIgnoreCase(indexName)) return;
            }
            try (Statement create = conn.createStatement()) {
                create.execute("CREATE UNIQUE INDEX " + indexName + " ON " + table + " (" + column + ")");
            }
        } catch (SQLException e) {
            if (!isDuplicateKeyError(e)) {
                System.err.println("[EasyVip-SQL] Failed to ensure unique index " + table + "." + indexName + ": " + e.getMessage());
            }
        }
    }

    private static void ensureColumnExists(Connection conn, String table, String column, String type) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE UPPER(table_name) = UPPER(?) AND UPPER(column_name) = UPPER(?)")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (Statement alter = conn.createStatement()) {
                        alter.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Failed to ensure column " + table + "." + column + ": " + e.getMessage());
        }
    }

    private static void migrateLegacyVipData() {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, player_name, last_observed_active_vip, vips_data FROM easyvip_vips");
             ResultSet rs = ps.executeQuery()) {
            conn.setAutoCommit(false);
            while (rs.next()) {
                Savepoint rowSavepoint = conn.setSavepoint();
                String rawUuid = rs.getString("player_uuid");
                try {
                    UUID uuid = UUID.fromString(rawUuid);
                    long now = System.currentTimeMillis();
                    ensureV2Player(conn, uuid, rs.getString("player_name"), rs.getString("last_observed_active_vip"), now);
                    String json = rs.getString("vips_data");
                    if (json != null && !json.isBlank()) {
                        Type type = new TypeToken<Map<String, PlayerVipRecord>>() {}.getType();
                        Map<String, PlayerVipRecord> records = GSON.fromJson(json, type);
                        if (records != null) {
                            for (PlayerVipRecord record : records.values()) {
                                insertLegacyGrant(conn, uuid, record, now);
                            }
                        }
                    }
                    if (rs.getString("last_observed_active_vip") != null) {
                        upsertPreference(conn, uuid, rs.getString("last_observed_active_vip"), now);
                    }
                } catch (SQLException | RuntimeException rowError) {
                    conn.rollback(rowSavepoint);
                    System.err.println("[EasyVip-SQL] Legacy VIP row skipped for " + rawUuid + ": " + rowError.getMessage());
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException | RuntimeException e) {
            System.err.println("[EasyVip-SQL] Legacy VIP migration skipped: " + e.getMessage());
        }
    }

    private static void ensureV2Player(Connection conn, UUID uuid, String name, String active, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO easyvip_players (player_uuid, player_name, active_entitlement_id, version, created_at, updated_at) VALUES (?, ?, ?, 0, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name == null ? "" : name);
            ps.setString(3, active);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!isDuplicateKeyError(e)) throw e;
        }
    }

    private static void insertLegacyGrant(Connection conn, UUID uuid, PlayerVipRecord record, long now) throws SQLException {
        String grantId = UUID.nameUUIDFromBytes((uuid + ":" + record.getTierId() + ":" + record.getStartTime())
                .getBytes(StandardCharsets.UTF_8)).toString();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO easyvip_entitlement_grants
                (grant_id, player_uuid, entitlement_id, starts_at, expires_at, status, active,
                 pending_activate, source, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'legacy', ?, ?, 0)
                """)) {
            ps.setString(1, grantId);
            ps.setString(2, uuid.toString());
            ps.setString(3, record.getTierId());
            ps.setLong(4, record.getStartTime());
            ps.setLong(5, record.getExpiryTime());
            ps.setString(6, record.isExpired() ? "expired" : "active");
            ps.setBoolean(7, record.isActive());
            ps.setBoolean(8, record.isPendingActivateActions());
            ps.setLong(9, now);
            ps.setLong(10, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!isDuplicateKeyError(e)) throw e;
        }
    }

    private static void upsertPreference(Connection conn, UUID uuid, String activeTier, long now) throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement("DELETE FROM easyvip_player_preferences WHERE player_uuid = ?");
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO easyvip_player_preferences (player_uuid, active_entitlement_id, version, updated_at) VALUES (?, ?, 0, ?)")) {
            delete.setString(1, uuid.toString());
            delete.executeUpdate();
            insert.setString(1, uuid.toString());
            insert.setString(2, activeTier);
            insert.setLong(3, now);
            insert.executeUpdate();
        }
    }

    // ─── VIPs ────────────────────────────────────────────────

    public static PlayerVipRegistry getPlayerVips(UUID uuid) {
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT player_name, active_entitlement_id, version FROM easyvip_players WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readV2Registry(conn, uuid, rs.getString("player_name"),
                            rs.getString("active_entitlement_id"), rs.getLong("version"));
                }
            }
            return readLegacyRegistry(conn, uuid);
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading VIPs for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return null;
    }

    public static Map<UUID, PlayerVipRegistry> getAllPlayerVips() {
        Map<UUID, PlayerVipRegistry> result = new HashMap<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT player_uuid, player_name, active_entitlement_id, version FROM easyvip_players")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                result.put(uuid, readV2Registry(conn, uuid, rs.getString("player_name"),
                        rs.getString("active_entitlement_id"), rs.getLong("version")));
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all VIPs: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void updatePlayerVips(UUID uuid, PlayerVipRegistry registry) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            long now = System.currentTimeMillis();
            long expectedVersion = registry.getVersion();
            int updated = 0;
            if (expectedVersion == 0) {
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT INTO easyvip_players
                        (player_uuid, player_name, active_entitlement_id, version, created_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, ?)
                        """)) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, registry.getPlayerName() == null ? "" : registry.getPlayerName());
                    insert.setString(3, registry.getLastObservedActiveVip());
                    insert.setLong(4, now);
                    insert.setLong(5, now);
                    try {
                        updated = insert.executeUpdate();
                    } catch (SQLException e) {
                        if (!isDuplicateKeyError(e)) throw e;
                    }
                }
            }
            if (updated == 0) {
                try (PreparedStatement update = conn.prepareStatement("""
                        UPDATE easyvip_players
                        SET player_name = ?, active_entitlement_id = ?, version = version + 1, updated_at = ?
                        WHERE player_uuid = ? AND version = ?
                        """)) {
                    update.setString(1, registry.getPlayerName() == null ? "" : registry.getPlayerName());
                    update.setString(2, registry.getLastObservedActiveVip());
                    update.setLong(3, now);
                    update.setString(4, uuid.toString());
                    update.setLong(5, expectedVersion);
                    updated = update.executeUpdate();
                }
            }
            if (updated != 1) {
                conn.rollback();
                throw new ConcurrentModificationException("Stale VIP snapshot for " + uuid);
            }
            Set<String> currentTierIds = new HashSet<>(registry.getVips().keySet());
            revokeMissingGrants(conn, uuid, currentTierIds, now);
            try (PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM easyvip_entitlement_grants WHERE player_uuid = ? AND status = 'active'")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
            }
            for (PlayerVipRecord record : registry.getVips().values()) {
                insertCurrentGrant(conn, uuid, record, now);
            }
            upsertPreference(conn, uuid, registry.getLastObservedActiveVip(), now);

            // Keep the legacy row synchronized for rollback/reconciliation tooling only.
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM easyvip_vips WHERE player_uuid = ?");
                 PreparedStatement insert = conn.prepareStatement("""
                         INSERT INTO easyvip_vips (player_uuid, player_name, last_observed_active_vip, vips_data)
                         VALUES (?, ?, ?, ?)
                         """)) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
                insert.setString(1, uuid.toString());
                insert.setString(2, registry.getPlayerName() == null ? "" : registry.getPlayerName());
                insert.setString(3, registry.getLastObservedActiveVip());
                insert.setString(4, GSON.toJson(registry.getVips()));
                insert.executeUpdate();
            }
            conn.commit();
            conn.setAutoCommit(true);
            registry.setVersion(expectedVersion + 1);
        } catch (SQLException e) {
            throw new RuntimeException("[EasyVip-SQL] Error updating VIPs for " + uuid, e);
        }
    }

    private static PlayerVipRegistry readV2Registry(Connection conn, UUID uuid, String playerName,
                                                     String activeTier, long version) throws SQLException {
        PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
        registry.setPlayerName(playerName);
        registry.setLastObservedActiveVip(activeTier);
        registry.setVersion(version);
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT entitlement_id, starts_at, expires_at, active, pending_activate
                FROM easyvip_entitlement_grants WHERE player_uuid = ? AND status = 'active' AND active = TRUE
                """)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerVipRecord record = new PlayerVipRecord(rs.getString("entitlement_id"),
                            rs.getLong("starts_at"), rs.getLong("expires_at"),
                            rs.getBoolean("active"), rs.getBoolean("pending_activate"));
                    registry.getVips().put(record.getTierId(), record);
                }
            }
        }
        return registry;
    }

    private static void revokeMissingGrants(Connection conn, UUID uuid, Set<String> currentTierIds, long now) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT grant_id, entitlement_id FROM easyvip_entitlement_grants WHERE player_uuid = ? AND status = 'active'");
             PreparedStatement update = conn.prepareStatement(
                     "UPDATE easyvip_entitlement_grants SET status = 'revoked', active = FALSE, updated_at = ?, version = version + 1 WHERE grant_id = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    if (!currentTierIds.contains(rs.getString("entitlement_id"))) {
                        update.setLong(1, now);
                        update.setString(2, rs.getString("grant_id"));
                        update.addBatch();
                    }
                }
            }
            update.executeBatch();
        }
    }

    private static PlayerVipRegistry readLegacyRegistry(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT player_name, last_observed_active_vip, vips_data FROM easyvip_vips WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
                registry.setPlayerName(rs.getString("player_name"));
                registry.setLastObservedActiveVip(rs.getString("last_observed_active_vip"));
                Type type = new TypeToken<Map<String, PlayerVipRecord>>() {}.getType();
                Map<String, PlayerVipRecord> values = GSON.fromJson(rs.getString("vips_data"), type);
                if (values != null) registry.setVips(values);
                return registry;
            }
        }
    }

    private static void insertCurrentGrant(Connection conn, UUID uuid, PlayerVipRecord record, long now) throws SQLException {
        String grantId = UUID.nameUUIDFromBytes((uuid + ":" + record.getTierId() + ":" + record.getStartTime())
                .getBytes(StandardCharsets.UTF_8)).toString();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO easyvip_entitlement_grants
                (grant_id, player_uuid, entitlement_id, starts_at, expires_at, status, active,
                 pending_activate, source, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'legacy', ?, ?, 0)
                """)) {
            ps.setString(1, grantId);
            ps.setString(2, uuid.toString());
            ps.setString(3, record.getTierId());
            ps.setLong(4, record.getStartTime());
            ps.setLong(5, record.getExpiryTime());
            ps.setString(6, record.isExpired() ? "expired" : "active");
            ps.setBoolean(7, record.isActive());
            ps.setBoolean(8, record.isPendingActivateActions());
            ps.setLong(9, now);
            ps.setLong(10, now);
            ps.executeUpdate();
        }
    }

    /** Exactly one node can win an expiry transition for a normalized grant. */
    public static boolean transitionEntitlementExpired(UUID uuid, String tierId, long startTime, long now) {
        if (uuid == null || tierId == null) return false;
        String grantId = UUID.nameUUIDFromBytes((uuid + ":" + tierId + ":" + startTime)
                .getBytes(StandardCharsets.UTF_8)).toString();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE easyvip_entitlement_grants
                     SET status = 'expired', active = FALSE, updated_at = ?, version = version + 1
                     WHERE grant_id = ? AND status = 'active' AND expires_at <> -1 AND expires_at <= ?
                     """)) {
            ps.setLong(1, now);
            ps.setString(2, grantId);
            ps.setLong(3, now);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    // ─── Keys ────────────────────────────────────────────────

    public static KeyRecord getKey(String code) {
        LOCK.readLock().lock();
        try (Connection conn = getConnection()) {
            return getKey(conn, code);
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading key " + br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(code) + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return null;
    }

    private static KeyRecord getKey(Connection conn, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM easyvip_keys WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapKeyRecord(rs);
                }
            }
        }
        return null;
    }

    public static List<KeyRecord> getAllKeys() {
        List<KeyRecord> result = new ArrayList<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM easyvip_keys")) {
            while (rs.next()) {
                result.add(mapKeyRecord(rs));
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all keys: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void putKey(KeyRecord record) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement update = conn.prepareStatement("""
                    UPDATE easyvip_keys SET type = ?, tier_id = ?, duration = ?, reward_key_id = ?,
                        max_uses = ?, used_count = ?, bound_player_uuid = ?, created_time = ?, expiry_time = ?,
                        used_by_json = ?, last_used_at_by_json = ?, actions_json = ?, consumed_instances_json = ?
                    WHERE code = ?
                    """)) {
                setKeyUpdateStatement(update, record);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = conn.prepareStatement("""
                            INSERT INTO easyvip_keys
                            (code, type, tier_id, duration, reward_key_id, max_uses, used_count,
                             bound_player_uuid, created_time, expiry_time, used_by_json,
                             last_used_at_by_json, actions_json, consumed_instances_json)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        setKeyStatement(insert, record);
                        insert.executeUpdate();
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error saving key " + br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(record.getCode()) + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static KeyRecord putKeyIfAbsent(KeyRecord record) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                KeyRecord existing = getKey(conn, record.getCode());
                if (existing != null) {
                    conn.commit();
                    return existing;
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO easyvip_keys
                    (code, type, tier_id, duration, reward_key_id, max_uses, used_count,
                     bound_player_uuid, created_time, expiry_time, used_by_json,
                     last_used_at_by_json, actions_json, consumed_instances_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                    setKeyStatement(ps, record);
                    ps.executeUpdate();
                }
                conn.commit();
                return null;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                if (isDuplicateKeyError(e)) {
                    KeyRecord existing = getKey(conn, record.getCode());
                    return existing != null ? existing : record;
                }
                System.err.println("[EasyVip-SQL] Error inserting key " + br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(record.getCode()) + ": " + e.getMessage());
                return record;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error getting connection for key insert: " + e.getMessage());
            return record;
        }
    }

    private static boolean isDuplicateKeyError(SQLException e) {
        String sqlState = e.getSQLState();
        return "23000".equals(sqlState) || "23505".equals(sqlState);
    }

    private static void setKeyStatement(PreparedStatement ps, KeyRecord record) throws SQLException {
        ps.setString(1, record.getCode());
        ps.setString(2, record.getType());
        ps.setString(3, record.getTierId());
        ps.setString(4, record.getDuration());
        ps.setString(5, record.getRewardKeyId());
        ps.setInt(6, record.getMaxUses());
        ps.setInt(7, record.getUsedCount());
        ps.setString(8, record.getBoundPlayerUuid() != null ? record.getBoundPlayerUuid().toString() : null);
        ps.setLong(9, record.getCreatedTime());
        ps.setLong(10, record.getExpiryTime());
        ps.setString(11, GSON.toJson(record.getUsedBy()));
        ps.setString(12, GSON.toJson(record.getLastUsedAtBy()));
        ps.setString(13, GSON.toJson(record.getActions()));
        ps.setString(14, GSON.toJson(record.getConsumedInstances()));
    }

    private static void setKeyUpdateStatement(PreparedStatement ps, KeyRecord record) throws SQLException {
        ps.setString(1, record.getType());
        ps.setString(2, record.getTierId());
        ps.setString(3, record.getDuration());
        ps.setString(4, record.getRewardKeyId());
        ps.setInt(5, record.getMaxUses());
        ps.setInt(6, record.getUsedCount());
        ps.setString(7, record.getBoundPlayerUuid() != null ? record.getBoundPlayerUuid().toString() : null);
        ps.setLong(8, record.getCreatedTime());
        ps.setLong(9, record.getExpiryTime());
        ps.setString(10, GSON.toJson(record.getUsedBy()));
        ps.setString(11, GSON.toJson(record.getLastUsedAtBy()));
        ps.setString(12, GSON.toJson(record.getActions()));
        ps.setString(13, GSON.toJson(record.getConsumedInstances()));
        ps.setString(14, record.getCode());
    }

    public static void removeKey(String code) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM easyvip_keys WHERE code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error removing key " + br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(code) + ": " + e.getMessage());
        }
    }

    private static KeyRecord mapKeyRecord(ResultSet rs) throws SQLException {
        KeyRecord record = new KeyRecord();
        record.setCode(rs.getString("code"));
        record.setType(rs.getString("type"));
        record.setTierId(rs.getString("tier_id"));
        record.setDuration(rs.getString("duration"));
        record.setRewardKeyId(rs.getString("reward_key_id"));
        record.setMaxUses(rs.getInt("max_uses"));
        record.setUsedCount(rs.getInt("used_count"));
        String boundStr = rs.getString("bound_player_uuid");
        if (boundStr != null && !boundStr.isEmpty()) {
            record.setBoundPlayerUuid(UUID.fromString(boundStr));
        }
        record.setCreatedTime(rs.getLong("created_time"));
        record.setExpiryTime(rs.getLong("expiry_time"));

        String usedByJson = rs.getString("used_by_json");
        if (usedByJson != null && !usedByJson.isEmpty()) {
            Type type = new TypeToken<Set<UUID>>(){}.getType();
            Set<UUID> usedBy = GSON.fromJson(usedByJson, type);
            if (usedBy != null) record.setUsedBy(usedBy);
        }

        String lastUsedJson = rs.getString("last_used_at_by_json");
        if (lastUsedJson != null && !lastUsedJson.isEmpty()) {
            Type type = new TypeToken<Map<UUID, Long>>(){}.getType();
            Map<UUID, Long> lastUsed = GSON.fromJson(lastUsedJson, type);
            if (lastUsed != null) record.setLastUsedAtBy(lastUsed);
        }

        String actionsJson = rs.getString("actions_json");
        if (actionsJson != null && !actionsJson.isEmpty()) {
            Type type = new TypeToken<List<Map<String, Object>>>(){}.getType();
            List<Map<String, Object>> actions = GSON.fromJson(actionsJson, type);
            if (actions != null) record.setActions(actions);
        }

        String consumedInstancesJson = rs.getString("consumed_instances_json");
        if (consumedInstancesJson != null && !consumedInstancesJson.isEmpty()) {
            Type type = new TypeToken<Set<String>>(){}.getType();
            Set<String> consumedInstances = GSON.fromJson(consumedInstancesJson, type);
            if (consumedInstances != null) record.setConsumedInstances(consumedInstances);
        }

        return record;
    }

    public enum KeyClaimStatus {
        CLAIMED,
        ALREADY_CLAIMED,
        INVALID_KEY,
        EXPIRED,
        NO_USES_LEFT,
        ALREADY_USED,
        BOUND_TO_OTHER,
        ERROR
    }

    public record KeyClaimResult(KeyClaimStatus status, String claimId, KeyRecord record) {
    }

    /**
     * Reserves one key use under a database transaction. The reservation lease
     * prevents a crashed node from permanently consuming a key.
     */
    public static KeyClaimResult claimKey(String code, UUID playerUuid, String physicalInstanceId,
                                          boolean consumesUse, String idempotencyKey, long now, long leaseMillis) {
        if (code == null || playerUuid == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return new KeyClaimResult(KeyClaimStatus.ERROR, null, null);
        }
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            // Do not take a gap lock for an absent idempotency key; the unique
            // constraint is the winner election and avoids cross-node deadlocks.
            RedemptionRow existing = findRedemption(conn, idempotencyKey, false);
            if (existing != null) {
                KeyRecord existingKey = getKey(conn, existing.code());
                conn.commit();
                return new KeyClaimResult(KeyClaimStatus.ALREADY_CLAIMED, existing.redemptionId(), existingKey);
            }

            KeyRecord key = getKeyForUpdate(conn, code);
            if (key == null) {
                conn.rollback();
                return new KeyClaimResult(KeyClaimStatus.INVALID_KEY, null, null);
            }
            if (key.isExpired()) {
                conn.rollback();
                return new KeyClaimResult(KeyClaimStatus.EXPIRED, null, key);
            }
            if (key.getBoundPlayerUuid() != null && !key.getBoundPlayerUuid().equals(playerUuid)) {
                conn.rollback();
                return new KeyClaimResult(KeyClaimStatus.BOUND_TO_OTHER, null, key);
            }
            if (physicalInstanceId != null && key.getConsumedInstances().contains(physicalInstanceId)) {
                conn.rollback();
                return new KeyClaimResult(KeyClaimStatus.ALREADY_USED, null, key);
            }
            if (consumesUse && key.getUsedBy().contains(playerUuid)) {
                conn.rollback();
                return new KeyClaimResult(KeyClaimStatus.ALREADY_USED, null, key);
            }

            expireKeyClaims(conn, code, now);
            RedemptionRow physicalClaim = physicalInstanceId == null
                    ? null : findRedemptionByPhysical(conn, code, physicalInstanceId, true);
            if (physicalClaim != null && ("CLAIMED".equals(physicalClaim.status())
                    || "COMPLETE".equals(physicalClaim.status()))) {
                conn.rollback();
                return new KeyClaimResult(KeyClaimStatus.ALREADY_USED, physicalClaim.redemptionId(), key);
            }
            long activeClaims = activeKeyClaims(conn, code, now);
            if (consumesUse && key.getUsedCount() + activeClaims >= key.getMaxUses()) {
                conn.rollback();
                return new KeyClaimResult(KeyClaimStatus.NO_USES_LEFT, null, key);
            }

            String claimId = physicalClaim == null ? UUID.randomUUID().toString() : physicalClaim.redemptionId();
            if (physicalClaim == null) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO easyvip_key_redemptions
                        (redemption_id, idempotency_key, code, player_uuid, physical_instance_id,
                         status, claimed_at, lease_expires_at)
                        VALUES (?, ?, ?, ?, ?, 'CLAIMED', ?, ?)
                        """)) {
                    ps.setString(1, claimId);
                    ps.setString(2, idempotencyKey);
                    ps.setString(3, code);
                    ps.setString(4, playerUuid.toString());
                    if (physicalInstanceId == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, physicalInstanceId);
                    ps.setLong(6, now);
                    ps.setLong(7, now + Math.max(1_000L, leaseMillis));
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE easyvip_key_redemptions
                        SET idempotency_key = ?, player_uuid = ?, status = 'CLAIMED', claimed_at = ?,
                            lease_expires_at = ?, completed_at = NULL, failure_code = NULL
                        WHERE redemption_id = ?
                        """)) {
                    ps.setString(1, idempotencyKey);
                    ps.setString(2, playerUuid.toString());
                    ps.setLong(3, now);
                    ps.setLong(4, now + Math.max(1_000L, leaseMillis));
                    ps.setString(5, claimId);
                    ps.executeUpdate();
                }
            }
            conn.commit();
            return new KeyClaimResult(KeyClaimStatus.CLAIMED, claimId, key);
        } catch (SQLException e) {
            if (isDuplicateKeyError(e) && physicalInstanceId != null) {
                try (Connection conn = getConnection()) {
                    RedemptionRow physicalClaim = findRedemptionByPhysical(conn, code, physicalInstanceId, false);
                    if (physicalClaim != null && ("CLAIMED".equals(physicalClaim.status())
                            || "COMPLETE".equals(physicalClaim.status()))) {
                        return new KeyClaimResult(KeyClaimStatus.ALREADY_USED, physicalClaim.redemptionId(), null);
                    }
                } catch (SQLException ignored) {
                }
            }
            System.err.println("[EasyVip-SQL] claimKey failed for "
                    + br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(code)
                    + " state=" + e.getSQLState() + ": " + e.getMessage());
            return new KeyClaimResult(KeyClaimStatus.ERROR, null, null);
        }
    }

    public static boolean completeKeyClaim(String claimId, UUID playerUuid, boolean consumesUse, long now) {
        if (claimId == null || playerUuid == null) return false;
        LOCK.writeLock().lock();
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            RedemptionRow claim = findRedemptionById(conn, claimId, true);
            if (claim == null || !claim.playerUuid().equals(playerUuid.toString())) {
                conn.rollback();
                return false;
            }
            if ("COMPLETE".equals(claim.status())) {
                conn.commit();
                return true;
            }
            if (!"CLAIMED".equals(claim.status()) || claim.leaseExpiresAt() < now) {
                conn.rollback();
                return false;
            }
            KeyRecord key = getKeyForUpdate(conn, claim.code());
            if (key == null || (consumesUse && key.getUsedCount() >= key.getMaxUses())) {
                conn.rollback();
                return false;
            }
            if (consumesUse) {
                key.setUsedCount(key.getUsedCount() + 1);
                key.getUsedBy().add(playerUuid);
            }
            key.getLastUsedAtBy().put(playerUuid, now);
            if (claim.physicalInstanceId() != null) key.markInstanceConsumed(claim.physicalInstanceId());
            updateKeyConsumption(conn, key);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE easyvip_key_redemptions SET status = 'COMPLETE', completed_at = ? WHERE redemption_id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, claimId);
                ps.executeUpdate();
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            return false;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static boolean releaseKeyClaim(String claimId, String failureCode) {
        if (claimId == null) return false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE easyvip_key_redemptions SET status = 'FAILED', failure_code = ? WHERE redemption_id = ? AND status = 'CLAIMED'")) {
            ps.setString(1, failureCode == null ? "action_failed" : failureCode);
            ps.setString(2, claimId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void updateKeyConsumption(Connection conn, KeyRecord key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE easyvip_keys
                SET used_count = ?, used_by_json = ?, last_used_at_by_json = ?, consumed_instances_json = ?
                WHERE code = ?
                """)) {
            ps.setInt(1, key.getUsedCount());
            ps.setString(2, GSON.toJson(key.getUsedBy()));
            ps.setString(3, GSON.toJson(key.getLastUsedAtBy()));
            ps.setString(4, GSON.toJson(key.getConsumedInstances()));
            ps.setString(5, key.getCode());
            ps.executeUpdate();
        }
    }

    private static KeyRecord getKeyForUpdate(Connection conn, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM easyvip_keys WHERE code = ? FOR UPDATE")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapKeyRecord(rs) : null;
            }
        }
    }

    private static void expireKeyClaims(Connection conn, String code, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE easyvip_key_redemptions SET status = 'EXPIRED', failure_code = 'lease_expired'
                WHERE code = ? AND status = 'CLAIMED' AND lease_expires_at < ?
                """)) {
            ps.setString(1, code);
            ps.setLong(2, now);
            ps.executeUpdate();
        }
    }

    private static long activeKeyClaims(Connection conn, String code, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT redemption_id FROM easyvip_key_redemptions
                WHERE code = ? AND status = 'CLAIMED' AND lease_expires_at >= ?
                FOR UPDATE
                """)) {
            ps.setString(1, code);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                long count = 0;
                while (rs.next()) count++;
                return count;
            }
        }
    }

    private record RedemptionRow(String redemptionId, String code, String playerUuid,
                                 String physicalInstanceId, String status, long leaseExpiresAt) {
    }

    private static RedemptionRow findRedemption(Connection conn, String idempotencyKey, boolean forUpdate) throws SQLException {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT redemption_id, code, player_uuid, physical_instance_id, status, lease_expires_at "
                        + "FROM easyvip_key_redemptions WHERE idempotency_key = ?" + suffix)) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRedemption(rs) : null;
            }
        }
    }

    private static RedemptionRow findRedemptionById(Connection conn, String claimId, boolean forUpdate) throws SQLException {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT redemption_id, code, player_uuid, physical_instance_id, status, lease_expires_at "
                        + "FROM easyvip_key_redemptions WHERE redemption_id = ?" + suffix)) {
            ps.setString(1, claimId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRedemption(rs) : null;
            }
        }
    }

    private static RedemptionRow findRedemptionByPhysical(Connection conn, String code, String physicalInstanceId,
                                                          boolean forUpdate) throws SQLException {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT redemption_id, code, player_uuid, physical_instance_id, status, lease_expires_at "
                        + "FROM easyvip_key_redemptions WHERE code = ? AND physical_instance_id = ?" + suffix)) {
            ps.setString(1, code);
            ps.setString(2, physicalInstanceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRedemption(rs) : null;
            }
        }
    }

    private static RedemptionRow mapRedemption(ResultSet rs) throws SQLException {
        return new RedemptionRow(rs.getString("redemption_id"), rs.getString("code"),
                rs.getString("player_uuid"), rs.getString("physical_instance_id"),
                rs.getString("status"), rs.getLong("lease_expires_at"));
    }

    // ─── Pending Variants ────────────────────────────────────

    public static List<PendingVariantSelection> getPendingVariants(UUID uuid) {
        List<PendingVariantSelection> result = new ArrayList<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM easyvip_pending_variants WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapPendingVariant(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading pending variants for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static Map<UUID, List<PendingVariantSelection>> getAllPendingVariants() {
        Map<UUID, List<PendingVariantSelection>> result = new HashMap<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM easyvip_pending_variants")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                result.computeIfAbsent(uuid, k -> new ArrayList<>()).add(mapPendingVariant(rs));
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all pending variants: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void addPendingVariant(UUID uuid, PendingVariantSelection selection) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO easyvip_pending_variants (player_uuid, package_id, variants_json, timestamp, claim_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, selection.getPackageId());
            ps.setString(3, GSON.toJson(selection.getVariants()));
            ps.setLong(4, selection.getTimestamp());
            ps.setString(5, selection.getClaimId());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error adding pending variant: " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static void removePendingVariant(UUID uuid, String packageId) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM easyvip_pending_variants WHERE player_uuid = ? AND package_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, packageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error removing pending variant: " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static PendingVariantSelection mapPendingVariant(ResultSet rs) throws SQLException {
        PendingVariantSelection sel = new PendingVariantSelection();
        sel.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        sel.setPackageId(rs.getString("package_id"));
        sel.setTimestamp(rs.getLong("timestamp"));
        String variantsJson = rs.getString("variants_json");
        if (variantsJson != null && !variantsJson.isEmpty()) {
            Type type = new TypeToken<List<String>>(){}.getType();
            List<String> variants = GSON.fromJson(variantsJson, type);
            if (variants != null) sel.setVariants(variants);
        }
        try {
            sel.setClaimId(rs.getString("claim_id"));
        } catch (SQLException ignored) {
            // Legacy databases may not have the additive claim column yet.
        }
        return sel;
    }

    // ─── Package Usage ───────────────────────────────────────

    public static Map<String, Long> getPackageUsage(UUID uuid) {
        Map<String, Long> result = new HashMap<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT package_id, usage_count FROM easyvip_package_usage WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("package_id"), rs.getLong("usage_count"));
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading package usage for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static Map<UUID, Map<String, Long>> getAllPackageUsage() {
        Map<UUID, Map<String, Long>> result = new HashMap<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT player_uuid, package_id, usage_count FROM easyvip_package_usage")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                result.computeIfAbsent(uuid, k -> new HashMap<>())
                    .put(rs.getString("package_id"), rs.getLong("usage_count"));
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all package usage: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void updatePackageUsage(UUID uuid, Map<String, Long> usage) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement deletePs = conn.prepareStatement(
                     "DELETE FROM easyvip_package_usage WHERE player_uuid = ?");
                 PreparedStatement insertPs = conn.prepareStatement(
                     "INSERT INTO easyvip_package_usage (player_uuid, package_id, usage_count) VALUES (?, ?, ?)")) {
                deletePs.setString(1, uuid.toString());
                deletePs.executeUpdate();

                for (Map.Entry<String, Long> entry : usage.entrySet()) {
                    insertPs.setString(1, uuid.toString());
                    insertPs.setString(2, entry.getKey());
                    insertPs.setLong(3, entry.getValue());
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error updating package usage for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public enum PackageClaimStatus {
        CLAIMED,
        ALREADY_CLAIMED,
        COOLDOWN,
        ERROR
    }

    public record PackageClaimResult(PackageClaimStatus status, String claimId) {
    }

    /** Atomically reserves a package claim; uniqueness and cooldown are DB decisions. */
    public static PackageClaimResult claimPackage(UUID playerUuid, String packageId, boolean repeatable,
                                                  long cooldownMillis, String idempotencyKey,
                                                  long now, long leaseMillis) {
        if (playerUuid == null || packageId == null || packageId.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            return new PackageClaimResult(PackageClaimStatus.ERROR, null);
        }
        String claimKey = repeatable ? idempotencyKey : "once:" + playerUuid + ":" + packageId;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            // An absent unique key cannot be locked; let the unique constraint
            // arbitrate concurrent inserts after the cooldown read.
            PackageClaimRow existing = findPackageClaim(conn, claimKey, false);
            if (existing != null && "COMPLETE".equals(existing.status())) {
                conn.commit();
                return new PackageClaimResult(PackageClaimStatus.ALREADY_CLAIMED, existing.claimId());
            }
            if (existing != null && "CLAIMED".equals(existing.status()) && existing.leaseExpiresAt() >= now) {
                conn.commit();
                return new PackageClaimResult(PackageClaimStatus.ALREADY_CLAIMED, existing.claimId());
            }
            if (repeatable && cooldownMillis > 0) {
                Long lastClaim = latestPackageClaim(conn, playerUuid, packageId);
                if (lastClaim != null && now - lastClaim < cooldownMillis) {
                    conn.rollback();
                    return new PackageClaimResult(PackageClaimStatus.COOLDOWN, null);
                }
            }
            String claimId = existing == null ? UUID.randomUUID().toString() : existing.claimId();
            if (existing == null) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO easyvip_package_claims
                        (claim_id, claim_key, idempotency_key, player_uuid, package_id, status,
                         claimed_at, lease_expires_at)
                        VALUES (?, ?, ?, ?, ?, 'CLAIMED', ?, ?)
                        """)) {
                    ps.setString(1, claimId);
                    ps.setString(2, claimKey);
                    ps.setString(3, idempotencyKey);
                    ps.setString(4, playerUuid.toString());
                    ps.setString(5, packageId);
                    ps.setLong(6, now);
                    ps.setLong(7, now + Math.max(1_000L, leaseMillis));
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE easyvip_package_claims
                        SET idempotency_key = ?, status = 'CLAIMED', claimed_at = ?, lease_expires_at = ?,
                            completed_at = NULL, failure_code = NULL
                        WHERE claim_id = ?
                        """)) {
                    ps.setString(1, idempotencyKey);
                    ps.setLong(2, now);
                    ps.setLong(3, now + Math.max(1_000L, leaseMillis));
                    ps.setString(4, claimId);
                    ps.executeUpdate();
                }
            }
            conn.commit();
            return new PackageClaimResult(PackageClaimStatus.CLAIMED, claimId);
        } catch (SQLException e) {
            if (isDuplicateKeyError(e)) {
                try (Connection conn = getConnection()) {
                    PackageClaimRow existing = findPackageClaim(conn, claimKey, false);
                    return existing == null
                            ? new PackageClaimResult(PackageClaimStatus.ERROR, null)
                            : new PackageClaimResult(PackageClaimStatus.ALREADY_CLAIMED, existing.claimId());
                } catch (SQLException ignored) {
                    return new PackageClaimResult(PackageClaimStatus.ERROR, null);
                }
            }
            System.err.println("[EasyVip-SQL] claimPackage failed for " + packageId
                    + " state=" + e.getSQLState() + ": " + e.getMessage());
            return new PackageClaimResult(PackageClaimStatus.ERROR, null);
        }
    }

    public static boolean completePackageClaim(String claimId, UUID playerUuid, long now) {
        return updatePackageClaim(claimId, playerUuid, "COMPLETE", null, now, false);
    }

    public static boolean releasePackageClaim(String claimId, UUID playerUuid, String failureCode, long now) {
        return updatePackageClaim(claimId, playerUuid, "FAILED", failureCode, now, true);
    }

    private static boolean updatePackageClaim(String claimId, UUID playerUuid, String status,
                                              String failureCode, long now, boolean allowExpired) {
        if (claimId == null || playerUuid == null) return false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE easyvip_package_claims
                     SET status = ?, failure_code = ?, completed_at = ?
                     WHERE claim_id = ? AND player_uuid = ? AND status = 'CLAIMED'
                       AND (lease_expires_at >= ? OR ? = TRUE)
                     """)) {
            ps.setString(1, status);
            ps.setString(2, failureCode);
            ps.setLong(3, now);
            ps.setString(4, claimId);
            ps.setString(5, playerUuid.toString());
            ps.setLong(6, now);
            ps.setBoolean(7, allowExpired);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    private record PackageClaimRow(String claimId, String status, long claimedAt, long leaseExpiresAt) {
    }

    private static PackageClaimRow findPackageClaim(Connection conn, String claimKey, boolean forUpdate) throws SQLException {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT claim_id, status, claimed_at, lease_expires_at FROM easyvip_package_claims WHERE claim_key = ?" + suffix)) {
            ps.setString(1, claimKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new PackageClaimRow(rs.getString("claim_id"), rs.getString("status"),
                        rs.getLong("claimed_at"), rs.getLong("lease_expires_at")) : null;
            }
        }
    }

    private static Long latestPackageClaim(Connection conn, UUID playerUuid, String packageId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT MAX(claimed_at) FROM easyvip_package_claims
                WHERE player_uuid = ? AND package_id = ? AND status = 'COMPLETE'
                """)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, packageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    // ─── Durable Delivery Ledger ────────────────────────────

    public record DeliveryClaimResult(DeliveryStatus status, String deliveryId, int attempts,
                                      long leaseExpiresAt, String failureCode) {
    }

    /** Claims a delivery by durable idempotency key. Expired leases are recoverable by another node. */
    public static DeliveryClaimResult claimDelivery(DeliveryRequest request, String nodeId,
                                                     long now, long leaseMillis) {
        if (request == null || nodeId == null || nodeId.isBlank() || leaseMillis < 1_000L) {
            return new DeliveryClaimResult(DeliveryStatus.ERROR, null, 0, 0L, "invalid_request");
        }
        long leaseExpiresAt = now + leaseMillis;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            DeliveryRow existing = findDelivery(conn, request.idempotencyKey(), false);
            if (existing != null) {
                existing = findDelivery(conn, request.idempotencyKey(), true);
                if (!matches(existing, request)) {
                    conn.rollback();
                    return new DeliveryClaimResult(DeliveryStatus.ERROR, existing.deliveryId(), existing.attempts(),
                            existing.leaseExpiresAt(), "idempotency_mismatch");
                }
                if ("DELIVERED".equals(existing.status())) {
                    conn.commit();
                    return deliveryResult(existing, DeliveryStatus.DELIVERED);
                }
                if ("CLAIMED".equals(existing.status()) && existing.leaseExpiresAt() >= now
                        && !nodeId.equals(existing.claimedByNode())) {
                    conn.commit();
                    return deliveryResult(existing, DeliveryStatus.IN_PROGRESS);
                }
                if ("CLAIMED".equals(existing.status()) && existing.leaseExpiresAt() >= now
                        && nodeId.equals(existing.claimedByNode())) {
                    conn.commit();
                    return deliveryResult(existing, DeliveryStatus.CLAIMED);
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE easyvip_deliveries
                        SET status = 'CLAIMED', claimed_by_node = ?, lease_expires_at = ?,
                            attempts = attempts + 1, failure_code = NULL, delivered_at = NULL
                        WHERE delivery_id = ?
                        """)) {
                    ps.setString(1, nodeId.trim());
                    ps.setLong(2, leaseExpiresAt);
                    ps.setString(3, existing.deliveryId());
                    ps.executeUpdate();
                }
                conn.commit();
                return new DeliveryClaimResult(DeliveryStatus.CLAIMED, existing.deliveryId(),
                        existing.attempts() + 1, leaseExpiresAt, null);
            }

            String deliveryId = UUID.randomUUID().toString();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO easyvip_deliveries
                    (delivery_id, player_uuid, grant_id, benefit_id, scope_type, scope_value,
                     idempotency_key, policy, status, claimed_by_node, lease_expires_at, attempts, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CLAIMED', ?, ?, 1, ?)
                    """)) {
                ps.setString(1, deliveryId);
                ps.setString(2, request.playerUuid().toString());
                ps.setString(3, request.grantId());
                ps.setString(4, request.benefitId());
                ps.setString(5, request.scopeType());
                ps.setString(6, request.scopeValue());
                ps.setString(7, request.idempotencyKey());
                ps.setString(8, request.policy().name());
                ps.setString(9, nodeId.trim());
                ps.setLong(10, leaseExpiresAt);
                ps.setLong(11, now);
                ps.executeUpdate();
            }
            conn.commit();
            return new DeliveryClaimResult(DeliveryStatus.CLAIMED, deliveryId, 1, leaseExpiresAt, null);
        } catch (SQLException e) {
            if (isDuplicateKeyError(e)) {
                try (Connection conn = getConnection()) {
                    DeliveryRow winner = findDelivery(conn, request.idempotencyKey(), false);
                    if (winner != null && matches(winner, request)) {
                        DeliveryStatus status = "DELIVERED".equals(winner.status())
                                ? DeliveryStatus.DELIVERED : DeliveryStatus.IN_PROGRESS;
                        return deliveryResult(winner, status);
                    }
                } catch (SQLException ignored) {
                }
            }
            System.err.println("[EasyVip-SQL] delivery claim failed: " + e.getSQLState());
            return new DeliveryClaimResult(DeliveryStatus.ERROR, null, 0, 0L, "sql_error");
        }
    }

    public static boolean completeDelivery(String deliveryId, UUID playerUuid, String nodeId, long now) {
        if (deliveryId == null || playerUuid == null || nodeId == null || nodeId.isBlank()) return false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE easyvip_deliveries
                     SET status = 'DELIVERED', delivered_at = ?, lease_expires_at = NULL, failure_code = NULL
                     WHERE delivery_id = ? AND player_uuid = ? AND status = 'CLAIMED'
                       AND claimed_by_node = ? AND lease_expires_at >= ?
                     """)) {
            ps.setLong(1, now);
            ps.setString(2, deliveryId);
            ps.setString(3, playerUuid.toString());
            ps.setString(4, nodeId.trim());
            ps.setLong(5, now);
            if (ps.executeUpdate() == 1) return true;
            return deliveryStatus(conn, deliveryId) == DeliveryStatus.DELIVERED;
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean failDelivery(String deliveryId, UUID playerUuid, String nodeId,
                                       String failureCode, long now) {
        if (deliveryId == null || playerUuid == null || nodeId == null || nodeId.isBlank()) return false;
        String safeFailure = failureCode == null || failureCode.isBlank() ? "delivery_failed" : failureCode.trim();
        if (safeFailure.length() > 80) safeFailure = safeFailure.substring(0, 80);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE easyvip_deliveries
                     SET status = 'FAILED', failure_code = ?, delivered_at = NULL, lease_expires_at = NULL
                     WHERE delivery_id = ? AND player_uuid = ? AND status = 'CLAIMED'
                       AND claimed_by_node = ? AND lease_expires_at >= ?
                     """)) {
            ps.setString(1, safeFailure);
            ps.setString(2, deliveryId);
            ps.setString(3, playerUuid.toString());
            ps.setString(4, nodeId.trim());
            ps.setLong(5, now);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    private static DeliveryStatus deliveryStatus(Connection conn, String deliveryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM easyvip_deliveries WHERE delivery_id = ?")) {
            ps.setString(1, deliveryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return DeliveryStatus.ERROR;
                return toDeliveryStatus(rs.getString(1));
            }
        }
    }

    private static DeliveryClaimResult deliveryResult(DeliveryRow row, DeliveryStatus status) {
        return new DeliveryClaimResult(status, row.deliveryId(), row.attempts(), row.leaseExpiresAt(), row.failureCode());
    }

    private static DeliveryStatus toDeliveryStatus(String status) {
        if ("DELIVERED".equals(status)) return DeliveryStatus.DELIVERED;
        if ("CLAIMED".equals(status)) return DeliveryStatus.CLAIMED;
        if ("FAILED".equals(status)) return DeliveryStatus.FAILED;
        return DeliveryStatus.ERROR;
    }

    private static boolean matches(DeliveryRow row, DeliveryRequest request) {
        return request.playerUuid().toString().equals(row.playerUuid())
                && Objects.equals(request.grantId(), row.grantId())
                && request.benefitId().equals(row.benefitId())
                && request.scopeType().equals(row.scopeType())
                && request.scopeValue().equals(row.scopeValue())
                && request.policy().name().equals(row.policy());
    }

    private record DeliveryRow(String deliveryId, String playerUuid, String grantId, String benefitId,
                               String scopeType, String scopeValue, String policy, String status, String claimedByNode,
                               int attempts, long leaseExpiresAt, String failureCode) {
    }

    private static DeliveryRow findDelivery(Connection conn, String idempotencyKey, boolean forUpdate) throws SQLException {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT delivery_id, player_uuid, grant_id, benefit_id, scope_type, scope_value, policy,
                       status, claimed_by_node, attempts, lease_expires_at, failure_code
                FROM easyvip_deliveries WHERE idempotency_key = ?
                """ + suffix)) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long lease = rs.getLong("lease_expires_at");
                if (rs.wasNull()) lease = 0L;
                return new DeliveryRow(rs.getString("delivery_id"), rs.getString("player_uuid"),
                        rs.getString("grant_id"), rs.getString("benefit_id"), rs.getString("scope_type"),
                        rs.getString("scope_value"), rs.getString("policy"), rs.getString("status"),
                        rs.getString("claimed_by_node"),
                        rs.getInt("attempts"), lease, rs.getString("failure_code"));
            }
        }
    }

    // ─── Audit Logs ──────────────────────────────────────────

    public static void log(AuditLogRecord record) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO easyvip_audit_logs (id, timestamp, operator, action, details) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, record.getId().toString());
            ps.setLong(2, record.getTimestamp());
            ps.setString(3, record.getOperator());
            ps.setString(4, record.getAction());
            ps.setString(5, br.com.pedrodalben.easyvip.util.KeySecurity.sanitizeAuditDetails(record.getDetails()));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error writing audit log: " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static List<AuditLogRecord> getAuditLogs() {
        List<AuditLogRecord> result = new ArrayList<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT * FROM easyvip_audit_logs ORDER BY timestamp ASC")) {
            while (rs.next()) {
                AuditLogRecord record = new AuditLogRecord();
                record.setId(UUID.fromString(rs.getString("id")));
                record.setTimestamp(rs.getLong("timestamp"));
                record.setOperator(rs.getString("operator"));
                record.setAction(rs.getString("action"));
                record.setDetails(rs.getString("details"));
                result.add(record);
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading audit logs: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    // ─── Fulfillment Operations ──────────────────────────────

    static Connection rawConnection() throws SQLException {
        return getConnection();
    }

    public static FulfillmentRecord getFulfillment(String fulfillmentId) {
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM easyvip_fulfillments WHERE fulfillment_id = ?")) {
            ps.setString(1, fulfillmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    FulfillmentRecord rec = mapFulfillment(rs);
                    List<FulfillmentItemRecord> items = getFulfillmentItems(conn, fulfillmentId);
                    rec.getItems().addAll(items);
                    return rec;
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading fulfillment " + fulfillmentId + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return null;
    }

    public static boolean insertFulfillmentTransaction(FulfillmentRecord fulfillment) {
        LOCK.writeLock().lock();
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO easyvip_fulfillments (fulfillment_id, order_id, minecraft_uuid, minecraft_username, "
                     + "payload_digest, status, request_key_id, created_at, completed_at) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, fulfillment.getFulfillmentId());
                ps.setString(2, fulfillment.getOrderId());
                ps.setString(3, fulfillment.getMinecraftUuid());
                ps.setString(4, fulfillment.getMinecraftUsername());
                ps.setString(5, fulfillment.getPayloadDigest());
                ps.setString(6, fulfillment.getStatus());
                ps.setString(7, fulfillment.getRequestKeyId());
                ps.setLong(8, fulfillment.getCreatedAt());
                if (fulfillment.getCompletedAt() != null) {
                    ps.setLong(9, fulfillment.getCompletedAt());
                } else {
                    ps.setNull(9, java.sql.Types.BIGINT);
                }
                ps.executeUpdate();
            }

            for (FulfillmentItemRecord item : fulfillment.getItems()) {
                try (PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO easyvip_fulfillment_items (line_item_id, fulfillment_id, product_sku, "
                         + "quantity, key_code, key_fingerprint, status, created_at) "
                         + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, item.getLineItemId());
                    ps.setString(2, item.getFulfillmentId());
                    ps.setString(3, item.getProductSku());
                    ps.setInt(4, item.getQuantity());
                    ps.setString(5, item.getKeyCode());
                    ps.setString(6, item.getKeyFingerprint());
                    ps.setString(7, item.getStatus());
                    ps.setLong(8, item.getCreatedAt());
                    ps.executeUpdate();
                }
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            System.err.println("[EasyVip-SQL] Error inserting fulfillment: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                try { conn.close(); } catch (SQLException ignored) {}
            }
            LOCK.writeLock().unlock();
        }
    }

    private static List<FulfillmentItemRecord> getFulfillmentItems(Connection conn, String fulfillmentId) throws SQLException {
        List<FulfillmentItemRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM easyvip_fulfillment_items WHERE fulfillment_id = ?")) {
            ps.setString(1, fulfillmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FulfillmentItemRecord item = new FulfillmentItemRecord();
                    item.setLineItemId(rs.getString("line_item_id"));
                    item.setFulfillmentId(rs.getString("fulfillment_id"));
                    item.setProductSku(rs.getString("product_sku"));
                    item.setQuantity(rs.getInt("quantity"));
                    item.setKeyCode(rs.getString("key_code"));
                    item.setKeyFingerprint(rs.getString("key_fingerprint"));
                    item.setStatus(rs.getString("status"));
                    item.setCreatedAt(rs.getLong("created_at"));
                    result.add(item);
                }
            }
        }
        return result;
    }

    private static FulfillmentRecord mapFulfillment(ResultSet rs) throws SQLException {
        FulfillmentRecord rec = new FulfillmentRecord();
        rec.setFulfillmentId(rs.getString("fulfillment_id"));
        rec.setOrderId(rs.getString("order_id"));
        rec.setMinecraftUuid(rs.getString("minecraft_uuid"));
        rec.setMinecraftUsername(rs.getString("minecraft_username"));
        rec.setPayloadDigest(rs.getString("payload_digest"));
        rec.setStatus(rs.getString("status"));
        rec.setRequestKeyId(rs.getString("request_key_id"));
        rec.setCreatedAt(rs.getLong("created_at"));
        long completedAt = rs.getLong("completed_at");
        if (!rs.wasNull()) rec.setCompletedAt(completedAt);
        long failedAt = rs.getLong("failed_at");
        if (!rs.wasNull()) rec.setFailedAt(failedAt);
        rec.setFailureCode(rs.getString("failure_code"));
        return rec;
    }

    public static FulfillmentRecord getWebStoreFulfillment(String fulfillmentId) {
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM webstore_fulfillments WHERE fulfillment_id = ?")) {
            ps.setString(1, fulfillmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    FulfillmentRecord rec = mapWebStoreFulfillment(rs);
                    rec.getItems().addAll(getWebStoreFulfillmentItems(conn, fulfillmentId));
                    return rec;
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading webstore fulfillment " + fulfillmentId + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return null;
    }

    public static boolean upsertWebStoreFulfillment(Connection conn, FulfillmentRecord fulfillment) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO webstore_fulfillments (
                    fulfillment_id, order_id, origin_server_id, server_id, minecraft_uuid, minecraft_username,
                    payload_digest, status, request_key_id, claim_token, created_at, claimed_at,
                    lease_expires_at, completed_at, failed_at, failure_code, error_message, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    order_id = VALUES(order_id),
                    origin_server_id = VALUES(origin_server_id),
                    server_id = VALUES(server_id),
                    minecraft_uuid = VALUES(minecraft_uuid),
                    minecraft_username = VALUES(minecraft_username),
                    payload_digest = VALUES(payload_digest),
                    status = VALUES(status),
                    request_key_id = VALUES(request_key_id),
                    claim_token = VALUES(claim_token),
                    claimed_at = VALUES(claimed_at),
                    lease_expires_at = VALUES(lease_expires_at),
                    completed_at = VALUES(completed_at),
                    failed_at = VALUES(failed_at),
                    failure_code = VALUES(failure_code),
                    error_message = VALUES(error_message),
                    updated_at = VALUES(updated_at)
                """)) {
            ps.setString(1, fulfillment.getFulfillmentId());
            ps.setString(2, fulfillment.getOrderId());
            ps.setString(3, fulfillment.getOriginServerId());
            ps.setString(4, fulfillment.getServerId());
            ps.setString(5, fulfillment.getMinecraftUuid());
            ps.setString(6, fulfillment.getMinecraftUsername());
            ps.setString(7, fulfillment.getPayloadDigest());
            ps.setString(8, fulfillment.getStatus());
            ps.setString(9, fulfillment.getRequestKeyId());
            ps.setString(10, fulfillment.getClaimToken());
            ps.setLong(11, fulfillment.getCreatedAt());
            if (fulfillment.getClaimedAt() != null) {
                ps.setLong(12, fulfillment.getClaimedAt());
            } else {
                ps.setNull(12, Types.BIGINT);
            }
            if (fulfillment.getLeaseExpiresAt() != null) {
                ps.setLong(13, fulfillment.getLeaseExpiresAt());
            } else {
                ps.setNull(13, Types.BIGINT);
            }
            if (fulfillment.getCompletedAt() != null) {
                ps.setLong(14, fulfillment.getCompletedAt());
            } else {
                ps.setNull(14, Types.BIGINT);
            }
            if (fulfillment.getFailedAt() != null) {
                ps.setLong(15, fulfillment.getFailedAt());
            } else {
                ps.setNull(15, Types.BIGINT);
            }
            ps.setString(16, fulfillment.getFailureCode());
            ps.setString(17, fulfillment.getErrorMessage());
            ps.setLong(18, fulfillment.getUpdatedAt());
            ps.executeUpdate();
            return true;
        }
    }

    public static boolean upsertWebStoreFulfillmentItem(Connection conn, FulfillmentItemRecord item) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO webstore_fulfillment_items (
                    line_item_id, fulfillment_id, product_sku, quantity, key_code,
                    key_fingerprint, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    fulfillment_id = VALUES(fulfillment_id),
                    product_sku = VALUES(product_sku),
                    quantity = VALUES(quantity),
                    key_code = VALUES(key_code),
                    key_fingerprint = VALUES(key_fingerprint),
                    status = VALUES(status),
                    created_at = VALUES(created_at),
                    updated_at = VALUES(updated_at)
                """)) {
            ps.setString(1, item.getLineItemId());
            ps.setString(2, item.getFulfillmentId());
            ps.setString(3, item.getProductSku());
            ps.setInt(4, item.getQuantity());
            ps.setString(5, item.getKeyCode());
            ps.setString(6, item.getKeyFingerprint());
            ps.setString(7, item.getStatus());
            ps.setLong(8, item.getCreatedAt());
            ps.setLong(9, item.getUpdatedAt());
            ps.executeUpdate();
            return true;
        }
    }

    public static boolean insertKeyRecord(Connection conn, KeyRecord record) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                 INSERT INTO easyvip_keys
                 (code, type, tier_id, duration, reward_key_id, max_uses, used_count,
                  bound_player_uuid, created_time, expiry_time, used_by_json,
                  last_used_at_by_json, actions_json, consumed_instances_json)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            setKeyStatement(ps, record);
            ps.executeUpdate();
            return true;
        }
    }

    private static FulfillmentRecord mapWebStoreFulfillment(ResultSet rs) throws SQLException {
        FulfillmentRecord rec = new FulfillmentRecord();
        rec.setFulfillmentId(rs.getString("fulfillment_id"));
        rec.setOrderId(rs.getString("order_id"));
        String originServerId = rs.getString("origin_server_id");
        if (originServerId == null || originServerId.isBlank()) {
            originServerId = rs.getString("server_id");
        }
        rec.setOriginServerId(originServerId);
        rec.setServerId(rs.getString("server_id"));
        rec.setMinecraftUuid(rs.getString("minecraft_uuid"));
        rec.setMinecraftUsername(rs.getString("minecraft_username"));
        rec.setPayloadDigest(rs.getString("payload_digest"));
        rec.setStatus(rs.getString("status"));
        rec.setRequestKeyId(rs.getString("request_key_id"));
        rec.setClaimToken(rs.getString("claim_token"));
        rec.setCreatedAt(rs.getLong("created_at"));
        long claimedAt = rs.getLong("claimed_at");
        if (!rs.wasNull()) rec.setClaimedAt(claimedAt);
        long leaseExpiresAt = rs.getLong("lease_expires_at");
        if (!rs.wasNull()) rec.setLeaseExpiresAt(leaseExpiresAt);
        long completedAt = rs.getLong("completed_at");
        if (!rs.wasNull()) rec.setCompletedAt(completedAt);
        long failedAt = rs.getLong("failed_at");
        if (!rs.wasNull()) rec.setFailedAt(failedAt);
        rec.setFailureCode(rs.getString("failure_code"));
        rec.setErrorMessage(rs.getString("error_message"));
        rec.setUpdatedAt(rs.getLong("updated_at"));
        return rec;
    }

    private static List<FulfillmentItemRecord> getWebStoreFulfillmentItems(Connection conn, String fulfillmentId) throws SQLException {
        List<FulfillmentItemRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM webstore_fulfillment_items WHERE fulfillment_id = ?")) {
            ps.setString(1, fulfillmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FulfillmentItemRecord item = new FulfillmentItemRecord();
                    item.setLineItemId(rs.getString("line_item_id"));
                    item.setFulfillmentId(rs.getString("fulfillment_id"));
                    item.setProductSku(rs.getString("product_sku"));
                    item.setQuantity(rs.getInt("quantity"));
                    item.setKeyCode(rs.getString("key_code"));
                    item.setKeyFingerprint(rs.getString("key_fingerprint"));
                    item.setStatus(rs.getString("status"));
                    item.setCreatedAt(rs.getLong("created_at"));
                    item.setUpdatedAt(rs.getLong("updated_at"));
                    result.add(item);
                }
            }
        }
        return result;
    }
}

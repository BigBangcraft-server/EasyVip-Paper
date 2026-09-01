package br.com.pedrodalben.easyvip.persistence;

import br.com.pedrodalben.easyvip.model.*;
import br.com.pedrodalben.easyvip.webstore.model.FulfillmentRecord;
import br.com.pedrodalben.easyvip.webstore.model.FulfillmentItemRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SqlDatabaseManager {

    private static final Gson GSON = new GsonBuilder().create();
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    private static String url;
    private static String username;
    private static String password;
    private static boolean initialized = false;

    private SqlDatabaseManager() {
    }

    public static void initialize(String dbUrl, String dbUsername, String dbPassword) {
        url = dbUrl;
        username = dbUsername;
        password = dbPassword;
        createTables();
        initialized = true;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isHealthy() {
        if (!initialized) {
            return false;
        }
        try (Connection conn = getConnection()) {
            return conn != null && conn.isValid(2);
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Health check failed: " + e.getMessage());
            return false;
        }
    }

    public static void shutdown() {
        initialized = false;
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection conn) throws SQLException;
    }

    public static <T> T withConnection(SqlWork<T> work) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection()) {
            return work.apply(conn);
        } catch (SQLException e) {
            throw new RuntimeException("SQL operation failed: " + e.getMessage(), e);
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username != null ? username : "");
        props.setProperty("password", password != null ? password : "");
        props.setProperty("useSSL", "false");
        props.setProperty("allowPublicKeyRetrieval", "true");
        return DriverManager.getConnection(url, props);
    }

    // ─── Table Creation ──────────────────────────────────────

    private static void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
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
                    timestamp BIGINT NOT NULL DEFAULT 0
                )
            """);

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
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database tables", e);
        }
    }

    private static void ensureUniqueIndex(Connection conn, String table, String indexName, String column) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (Statement alter = conn.createStatement()) {
                        alter.execute("ALTER TABLE " + table + " ADD UNIQUE KEY " + indexName + " (" + column + ")");
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Failed to ensure unique index " + table + "." + indexName + ": " + e.getMessage());
        }
    }

    private static void ensureColumnExists(Connection conn, String table, String column, String type) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?")) {
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

    // ─── VIPs ────────────────────────────────────────────────

    public static PlayerVipRegistry getPlayerVips(UUID uuid) {
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT player_name, last_observed_active_vip, vips_data FROM easyvip_vips WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
                    registry.setPlayerName(rs.getString("player_name"));
                    registry.setLastObservedActiveVip(rs.getString("last_observed_active_vip"));
                    String vipsJson = rs.getString("vips_data");
                    if (vipsJson != null && !vipsJson.isEmpty()) {
                        Type type = new TypeToken<Map<String, PlayerVipRecord>>(){}.getType();
                        Map<String, PlayerVipRecord> vips = GSON.fromJson(vipsJson, type);
                        if (vips != null) {
                            registry.setVips(vips);
                        }
                    }
                    return registry;
                }
            }
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
                 "SELECT player_uuid, player_name, last_observed_active_vip, vips_data FROM easyvip_vips")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
                registry.setPlayerName(rs.getString("player_name"));
                registry.setLastObservedActiveVip(rs.getString("last_observed_active_vip"));
                String vipsJson = rs.getString("vips_data");
                if (vipsJson != null && !vipsJson.isEmpty()) {
                    Type type = new TypeToken<Map<String, PlayerVipRecord>>(){}.getType();
                    Map<String, PlayerVipRecord> vips = GSON.fromJson(vipsJson, type);
                    if (vips != null) {
                        registry.setVips(vips);
                    }
                }
                result.put(uuid, registry);
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all VIPs: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void updatePlayerVips(UUID uuid, PlayerVipRegistry registry) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "REPLACE INTO easyvip_vips (player_uuid, player_name, last_observed_active_vip, vips_data) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, registry.getPlayerName() != null ? registry.getPlayerName() : "");
            ps.setString(3, registry.getLastObservedActiveVip());
            ps.setString(4, GSON.toJson(registry.getVips()));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error updating VIPs for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
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
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 REPLACE INTO easyvip_keys
                 (code, type, tier_id, duration, reward_key_id, max_uses, used_count,
                  bound_player_uuid, created_time, expiry_time, used_by_json,
                  last_used_at_by_json, actions_json, consumed_instances_json)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            setKeyStatement(ps, record);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error saving key " + br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(record.getCode()) + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static KeyRecord putKeyIfAbsent(KeyRecord record) {
        LOCK.writeLock().lock();
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
        } finally {
            LOCK.writeLock().unlock();
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

    public static void removeKey(String code) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM easyvip_keys WHERE code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error removing key " + br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(code) + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
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
                 "INSERT INTO easyvip_pending_variants (player_uuid, package_id, variants_json, timestamp) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, selection.getPackageId());
            ps.setString(3, GSON.toJson(selection.getVariants()));
            ps.setLong(4, selection.getTimestamp());
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
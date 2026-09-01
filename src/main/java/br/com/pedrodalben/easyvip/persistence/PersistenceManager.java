package br.com.pedrodalben.easyvip.persistence;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PersistenceManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "EasyVip-Persistence-Thread");
        thread.setDaemon(true);
        return thread;
    });

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static Path dataDir;
    private static boolean sqlMode = false;

    // Cache
    private static final Map<UUID, PlayerVipRegistry> vips = new HashMap<>();
    private static final Map<String, KeyRecord> keys = new HashMap<>();
    private static final Map<UUID, List<PendingVariantSelection>> pendingVariants = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> packageUsage = new HashMap<>();
    private static final List<AuditLogRecord> auditLogs = new ArrayList<>();

    private PersistenceManager() {
    }

    public static void initialize(Path dir) {
        dataDir = dir.resolve("data");

        if (EasyVipConfig.integrations.sqlEnabled) {
            sqlMode = true;
            SqlDatabaseManager.initialize(
                EasyVipConfig.integrations.sqlUrl,
                EasyVipConfig.integrations.sqlUsername,
                EasyVipConfig.integrations.sqlPassword
            );
            System.out.println("[EasyVip] SQL persistence enabled");
            return;
        }

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create data directory", e);
        }
        loadAll();
    }

    public static void reload() {
        if (EasyVipConfig.integrations.sqlEnabled) {
            sqlMode = true;
            SqlDatabaseManager.initialize(
                EasyVipConfig.integrations.sqlUrl,
                EasyVipConfig.integrations.sqlUsername,
                EasyVipConfig.integrations.sqlPassword
            );
            System.out.println("[EasyVip] SQL persistence reloaded: " + EasyVipConfig.integrations.sqlUrl);
        } else if (sqlMode) {
            SqlDatabaseManager.shutdown();
            sqlMode = false;
            loadAll();
        }
    }

    public static void shutdown() {
        if (dataDir == null && !sqlMode) {
            return;
        }
        if (sqlMode) {
            SqlDatabaseManager.shutdown();
            return;
        }
        flush();
        LOCK.writeLock().lock();
        try {
            saveVipsSync();
            saveKeysSync();
            savePendingVariantsSync();
            savePackageUsageSync();
            saveAuditLogsSync();
        } finally {
            LOCK.writeLock().unlock();
        }
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void flush() {
        try {
            EXECUTOR.submit(() -> { }).get();
        } catch (Exception e) {
            throw new RuntimeException("Could not flush persistence executor", e);
        }
    }

    // ─── Load Operations ────────────────────────────────────
    private static void loadAll() {
        LOCK.writeLock().lock();
        try {
            loadVips();
            loadKeys();
            loadPendingVariants();
            loadPackageUsage();
            loadAuditLogs();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static void loadVips() {
        Path file = dataDir.resolve("vips.json");
        Path backup = dataDir.resolve("vips.json.bak");
        Type type = new TypeToken<Map<String, PlayerVipRegistry>>(){}.getType();

        Map<String, PlayerVipRegistry> loaded = loadFile(file, backup, type);
        vips.clear();
        if (loaded != null) {
            for (Map.Entry<String, PlayerVipRegistry> entry : loaded.entrySet()) {
                try {
                    vips.put(UUID.fromString(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException e) {
                    // Ignore corrupted key
                }
            }
        }
    }

    private static void loadKeys() {
        Path file = dataDir.resolve("keys.json");
        Path backup = dataDir.resolve("keys.json.bak");
        Type type = new TypeToken<Map<String, KeyRecord>>(){}.getType();

        Map<String, KeyRecord> loaded = loadFile(file, backup, type);
        keys.clear();
        if (loaded != null) {
            keys.putAll(loaded);
        }
    }

    private static void loadPendingVariants() {
        Path file = dataDir.resolve("pending_variants.json");
        Path backup = dataDir.resolve("pending_variants.json.bak");
        Type type = new TypeToken<Map<String, List<PendingVariantSelection>>>(){}.getType();

        Map<String, List<PendingVariantSelection>> loaded = loadFile(file, backup, type);
        pendingVariants.clear();
        if (loaded != null) {
            for (Map.Entry<String, List<PendingVariantSelection>> entry : loaded.entrySet()) {
                try {
                    pendingVariants.put(UUID.fromString(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException e) {
                    // Ignore
                }
            }
        }
    }

    private static void loadPackageUsage() {
        Path file = dataDir.resolve("package_usage.json");
        Path backup = dataDir.resolve("package_usage.json.bak");
        Type type = new TypeToken<Map<String, Map<String, Long>>>(){}.getType();

        Map<String, Map<String, Long>> loaded = loadFile(file, backup, type);
        packageUsage.clear();
        if (loaded != null) {
            for (Map.Entry<String, Map<String, Long>> entry : loaded.entrySet()) {
                try {
                    packageUsage.put(UUID.fromString(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException e) {
                    // Ignore
                }
            }
        }
    }

    private static void loadAuditLogs() {
        Path file = dataDir.resolve("audit_logs.json");
        Path backup = dataDir.resolve("audit_logs.json.bak");
        Type type = new TypeToken<List<AuditLogRecord>>(){}.getType();

        List<AuditLogRecord> loaded = loadFile(file, backup, type);
        auditLogs.clear();
        if (loaded != null) {
            auditLogs.addAll(loaded);
        }
    }

    private static <T> T loadFile(Path file, Path backup, Type type) {
        if (Files.exists(file)) {
            try {
                String content = Files.readString(file);
                return GSON.fromJson(content, type);
            } catch (Exception e) {
                System.err.println("[EasyVip] Error loading " + file.getFileName() + ", trying backup: " + e.getMessage());
                if (Files.exists(backup)) {
                    try {
                        String content = Files.readString(backup);
                        return GSON.fromJson(content, type);
                    } catch (Exception ex) {
                        System.err.println("[EasyVip] Backup also corrupt for " + file.getFileName() + ": " + ex.getMessage());
                    }
                }
            }
        } else if (Files.exists(backup)) {
            try {
                String content = Files.readString(backup);
                return GSON.fromJson(content, type);
            } catch (Exception e) {
                System.err.println("[EasyVip] Error loading backup for " + file.getFileName() + ": " + e.getMessage());
            }
        }
        return null;
    }

    // ─── Save Trigger Helpers (Async) ───────────────────────
    public static void saveVips() {
        EXECUTOR.submit(PersistenceManager::saveVipsSync);
    }

    public static void saveKeys() {
        EXECUTOR.submit(PersistenceManager::saveKeysSync);
    }

    public static void savePendingVariants() {
        EXECUTOR.submit(PersistenceManager::savePendingVariantsSync);
    }

    public static void savePackageUsage() {
        EXECUTOR.submit(PersistenceManager::savePackageUsageSync);
    }

    public static void saveAuditLogs() {
        EXECUTOR.submit(PersistenceManager::saveAuditLogsSync);
    }

    // ─── Sync Atomic Save Operations ────────────────────────
    private static void saveVipsSync() {
        LOCK.readLock().lock();
        Map<String, PlayerVipRegistry> data = new HashMap<>();
        try {
            for (Map.Entry<UUID, PlayerVipRegistry> entry : vips.entrySet()) {
                data.put(entry.getKey().toString(), entry.getValue());
            }
        } finally {
            LOCK.readLock().unlock();
        }
        saveAtomic(dataDir.resolve("vips.json"), dataDir.resolve("vips.json.bak"), data);
    }

    private static void saveKeysSync() {
        LOCK.readLock().lock();
        Map<String, KeyRecord> data = new HashMap<>();
        try {
            for (Map.Entry<String, KeyRecord> entry : keys.entrySet()) {
                data.put(entry.getKey(), entry.getValue().copy());
            }
        } finally {
            LOCK.readLock().unlock();
        }
        saveAtomic(dataDir.resolve("keys.json"), dataDir.resolve("keys.json.bak"), data);
    }

    private static void savePendingVariantsSync() {
        LOCK.readLock().lock();
        Map<String, List<PendingVariantSelection>> data = new HashMap<>();
        try {
            for (Map.Entry<UUID, List<PendingVariantSelection>> entry : pendingVariants.entrySet()) {
                data.put(entry.getKey().toString(), entry.getValue());
            }
        } finally {
            LOCK.readLock().unlock();
        }
        saveAtomic(dataDir.resolve("pending_variants.json"), dataDir.resolve("pending_variants.json.bak"), data);
    }

    private static void savePackageUsageSync() {
        LOCK.readLock().lock();
        Map<String, Map<String, Long>> data = new HashMap<>();
        try {
            for (Map.Entry<UUID, Map<String, Long>> entry : packageUsage.entrySet()) {
                data.put(entry.getKey().toString(), new HashMap<>(entry.getValue()));
            }
        } finally {
            LOCK.readLock().unlock();
        }
        saveAtomic(dataDir.resolve("package_usage.json"), dataDir.resolve("package_usage.json.bak"), data);
    }

    private static void saveAuditLogsSync() {
        LOCK.readLock().lock();
        List<AuditLogRecord> data = new ArrayList<>();
        try {
            data.addAll(auditLogs);
        } finally {
            LOCK.readLock().unlock();
        }
        saveAtomic(dataDir.resolve("audit_logs.json"), dataDir.resolve("audit_logs.json.bak"), data);
    }

    private static void saveAtomic(Path file, Path backup, Object data) {
        try {
            String jsonStr = GSON.toJson(data);
            Path tempFile = file.getParent().resolve(file.getFileName().toString() + ".tmp");

            // Write to tmp and sync to storage
            Files.writeString(tempFile, jsonStr, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(tempFile,
                    java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            // Create backup if target file exists
            if (Files.exists(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            // Atomic move/rename with fallback
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicEx) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[EasyVip] Failed to save file atomically: " + file.getFileName() + ": " + e.getMessage());
        }
    }

    // ─── API Getters & Setters ──────────────────────────────
    public static PlayerVipRegistry getPlayerVips(UUID uuid) {
        if (sqlMode) {
            return SqlDatabaseManager.getPlayerVips(uuid);
        }
        LOCK.readLock().lock();
        try {
            return vips.get(uuid);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static Map<UUID, PlayerVipRegistry> getAllPlayerVips() {
        if (sqlMode) {
            return SqlDatabaseManager.getAllPlayerVips();
        }
        LOCK.readLock().lock();
        try {
            Map<UUID, PlayerVipRegistry> snapshot = new HashMap<>();
            for (Map.Entry<UUID, PlayerVipRegistry> entry : vips.entrySet()) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
            return snapshot;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static void updatePlayerVips(UUID uuid, PlayerVipRegistry registry) {
        if (sqlMode) {
            SqlDatabaseManager.updatePlayerVips(uuid, registry);
            return;
        }
        LOCK.writeLock().lock();
        try {
            vips.put(uuid, registry);
        } finally {
            LOCK.writeLock().unlock();
        }
        saveVips();
    }

    public static KeyRecord getKey(String code) {
        if (sqlMode) {
            return SqlDatabaseManager.getKey(code);
        }
        LOCK.readLock().lock();
        try {
            KeyRecord record = keys.get(code);
            return record != null ? record.copy() : null;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static void putKey(KeyRecord keyRecord) {
        if (sqlMode) {
            SqlDatabaseManager.putKey(keyRecord);
            return;
        }
        LOCK.writeLock().lock();
        try {
            keys.put(keyRecord.getCode(), keyRecord);
        } finally {
            LOCK.writeLock().unlock();
        }
        saveKeys();
    }

    public static KeyRecord putKeyIfAbsent(KeyRecord keyRecord) {
        if (sqlMode) {
            return SqlDatabaseManager.putKeyIfAbsent(keyRecord);
        }
        KeyRecord existing;
        LOCK.writeLock().lock();
        try {
            existing = keys.get(keyRecord.getCode());
            if (existing == null) {
                keys.put(keyRecord.getCode(), keyRecord);
            }
        } finally {
            LOCK.writeLock().unlock();
        }
        if (existing == null) {
            saveKeys();
            return null;
        }
        return existing.copy();
    }

    public static void removeKey(String code) {
        if (sqlMode) {
            SqlDatabaseManager.removeKey(code);
            return;
        }
        LOCK.writeLock().lock();
        try {
            keys.remove(code);
        } finally {
            LOCK.writeLock().unlock();
        }
        saveKeys();
    }

    public static List<KeyRecord> getAllKeys() {
        if (sqlMode) {
            return SqlDatabaseManager.getAllKeys();
        }
        LOCK.readLock().lock();
        try {
            List<KeyRecord> result = new ArrayList<>();
            for (KeyRecord record : keys.values()) {
                result.add(record.copy());
            }
            return result;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static List<PendingVariantSelection> getPendingVariants(UUID uuid) {
        if (sqlMode) {
            return SqlDatabaseManager.getPendingVariants(uuid);
        }
        LOCK.readLock().lock();
        try {
            List<PendingVariantSelection> list = pendingVariants.get(uuid);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static Map<UUID, List<PendingVariantSelection>> getAllPendingVariants() {
        if (sqlMode) {
            return SqlDatabaseManager.getAllPendingVariants();
        }
        LOCK.readLock().lock();
        try {
            Map<UUID, List<PendingVariantSelection>> snapshot = new HashMap<>();
            for (Map.Entry<UUID, List<PendingVariantSelection>> entry : pendingVariants.entrySet()) {
                snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return snapshot;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static void addPendingVariant(UUID uuid, PendingVariantSelection selection) {
        if (sqlMode) {
            SqlDatabaseManager.addPendingVariant(uuid, selection);
            return;
        }
        LOCK.writeLock().lock();
        try {
            List<PendingVariantSelection> list = pendingVariants.computeIfAbsent(uuid, k -> new ArrayList<>());
            list.add(selection);
        } finally {
            LOCK.writeLock().unlock();
        }
        savePendingVariants();
    }

    public static void removePendingVariant(UUID uuid, String packageId) {
        if (sqlMode) {
            SqlDatabaseManager.removePendingVariant(uuid, packageId);
            return;
        }
        LOCK.writeLock().lock();
        try {
            List<PendingVariantSelection> list = pendingVariants.get(uuid);
            if (list != null) {
                list.removeIf(sel -> sel.getPackageId().equals(packageId));
                if (list.isEmpty()) {
                    pendingVariants.remove(uuid);
                }
            }
        } finally {
            LOCK.writeLock().unlock();
        }
        savePendingVariants();
    }

    public static Map<String, Long> getPackageUsage(UUID uuid) {
        if (sqlMode) {
            return SqlDatabaseManager.getPackageUsage(uuid);
        }
        LOCK.readLock().lock();
        try {
            Map<String, Long> usage = packageUsage.get(uuid);
            return usage == null ? new HashMap<>() : new HashMap<>(usage);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static void updatePackageUsage(UUID uuid, Map<String, Long> usage) {
        if (sqlMode) {
            SqlDatabaseManager.updatePackageUsage(uuid, usage);
            return;
        }
        LOCK.writeLock().lock();
        try {
            packageUsage.put(uuid, new HashMap<>(usage));
        } finally {
            LOCK.writeLock().unlock();
        }
        savePackageUsage();
    }

    public static Map<UUID, Map<String, Long>> getAllPackageUsage() {
        if (sqlMode) {
            return SqlDatabaseManager.getAllPackageUsage();
        }
        LOCK.readLock().lock();
        try {
            Map<UUID, Map<String, Long>> snapshot = new HashMap<>();
            for (Map.Entry<UUID, Map<String, Long>> entry : packageUsage.entrySet()) {
                snapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
            return snapshot;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static void log(String operator, String action, String details) {
        String safeDetails = br.com.pedrodalben.easyvip.util.KeySecurity.sanitizeAuditDetails(details);
        if (sqlMode) {
            SqlDatabaseManager.log(new AuditLogRecord(operator, action, safeDetails));
            return;
        }
        LOCK.writeLock().lock();
        try {
            auditLogs.add(new AuditLogRecord(operator, action, safeDetails));
        } finally {
            LOCK.writeLock().unlock();
        }
        saveAuditLogs();
    }

    public static List<AuditLogRecord> getAuditLogs() {
        if (sqlMode) {
            return SqlDatabaseManager.getAuditLogs();
        }
        LOCK.readLock().lock();
        try {
            return new ArrayList<>(auditLogs);
        } finally {
            LOCK.readLock().unlock();
        }
    }
}

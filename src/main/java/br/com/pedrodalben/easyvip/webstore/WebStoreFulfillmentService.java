package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager;
import br.com.pedrodalben.easyvip.service.KeyService;
import br.com.pedrodalben.easyvip.util.KeySecurity;
import br.com.pedrodalben.easyvip.webstore.model.FulfillmentItemRecord;
import br.com.pedrodalben.easyvip.webstore.model.FulfillmentRecord;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebStoreFulfillmentService {

    private static final Gson GSON = new Gson();
    private static final String CLAIM_PATH = "/api/v1/minecraft/fulfillments/claim";
    private static final String COMPLETE_PATH_TEMPLATE = "/api/v1/minecraft/fulfillments/%s/complete";
    private static final String FAIL_PATH_TEMPLATE = "/api/v1/minecraft/fulfillments/%s/fail";
    private static final String SIGNATURE_PREFIX = "v1=";
    private static final String RESPONSE_SIGNATURE_PREFIX = "v1=";
    private static final int MAX_GENERATION_ATTEMPTS = 1000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_FULFILLMENTS_PER_RESPONSE = 100;
    private static final int MAX_ITEMS_PER_FULFILLMENT = 100;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private static volatile ScheduledExecutorService scheduler;
    private static volatile Path logFile;
    private static volatile boolean running;
    private static volatile long lastPollAt;
    private static volatile long lastSuccessAt;
    private static volatile long lastErrorAt;
    private static volatile long lastEmptyAt;
    private static volatile String lastErrorCode;
    private static volatile String lastState = "stopped";
    private static volatile int consecutiveFailures;
    private static volatile int lastClaimCount;
    private static volatile int lastProcessedCount;
    private static volatile String lastFulfillmentId;
    private static volatile boolean emptyPollLogged;

    private WebStoreFulfillmentService() {
    }

    public static synchronized void start(Path configDir) {
        initLog(configDir);
        if (running) {
            return;
        }

        if (!isConfigured()) {
            setUnavailable("disabled");
            log("UNAVAILABLE | " + serverLogPrefix() + "fulfillment disabled or missing secret/token");
            return;
        }

        if (!isSqlHealthy()) {
            setUnavailable("sql_unavailable");
            log("UNAVAILABLE | " + serverLogPrefix() + "SQL mode not active or unhealthy");
            PersistenceManager.log("WebStore", "fulfillment_unavailable", "SQL mode not active or unhealthy");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EasyVip-WebStore-Fulfillment");
            t.setDaemon(true);
            return t;
        });
        running = true;
        lastState = "running";
        scheduleNext(0L);
        log("STARTED | server_id=" + EasyVipConfig.fulfillment.serverId + " key_id=" + EasyVipConfig.fulfillment.keyId);
    }

    public static synchronized void reload(Path configDir) {
        stop();
        start(configDir);
    }

    public static synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        lastState = "stopped";
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isAvailable() {
        return running && isConfigured() && isSqlHealthy();
    }

    public static String statusSummary() {
        return "state=" + lastState
                + " server_id=" + EasyVipConfig.fulfillment.serverId
                + " key_id=" + EasyVipConfig.fulfillment.keyId
                + " sql=" + (isSqlHealthy() ? "healthy" : "unavailable")
                + " last_poll=" + formatTs(lastPollAt)
                + " last_success=" + formatTs(lastSuccessAt)
                + " last_error=" + formatTs(lastErrorAt)
                + " claims=" + lastClaimCount
                + " processed=" + lastProcessedCount
                + (lastErrorCode != null ? " error=" + lastErrorCode : "");
    }

    /** Keeps the SQL health probe out of a synchronous Paper command thread. */
    public static java.util.concurrent.CompletableFuture<String> statusSummaryAsync() {
        return PersistenceManager.executeAsync(WebStoreFulfillmentService::statusSummary);
    }

    public static void pollNowForTest() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (IN_FLIGHT.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        runCycle();
    }

    private static boolean isConfigured() {
        FulfillmentConfig cfg = EasyVipConfig.fulfillment;
        String secret = resolveSecret();
        String token = resolveToken();
        return cfg.enabled
                && cfg.serverId != null && !cfg.serverId.isBlank()
                && cfg.keyId != null && !cfg.keyId.isBlank()
                && secret != null && !secret.isBlank()
                && token != null && !token.isBlank();
    }

    private static boolean isSqlHealthy() {
        return EasyVipConfig.integrations.sqlEnabled
                && SqlDatabaseManager.isInitialized()
                && SqlDatabaseManager.isHealthy();
    }

    private static void initLog(Path configDir) {
        Path dataDir = configDir.resolve("data");
        try {
            Files.createDirectories(dataDir);
            logFile = dataDir.resolve("webstore_fulfillment.log");
            emptyPollLogged = false;
        } catch (IOException e) {
            System.err.println("[EasyVip-Fulfillment] Failed to initialize log file: " + e.getClass().getSimpleName());
        }
    }

    private static void scheduleNext(long delayMillis) {
        ScheduledExecutorService exec = scheduler;
        if (exec == null || !running) {
            return;
        }
        exec.schedule(WebStoreFulfillmentService::runCycleSafe, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private static void runCycleSafe() {
        try {
            runCycle();
        } catch (Throwable t) {
            lastErrorAt = System.currentTimeMillis();
            lastErrorCode = "scheduler_crash";
            consecutiveFailures++;
            lastState = "scheduler_error";
            log("ERROR | " + serverLogPrefix() + "scheduler crash: " + t.getClass().getSimpleName());
            scheduleNext(nextBackoffMillis());
        }
    }

    private static void runCycle() {
        if (!running) {
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            scheduleNext(Math.max(1000L, EasyVipConfig.fulfillment.pollIntervalSeconds * 1000L));
            return;
        }

        lastPollAt = System.currentTimeMillis();
        try {
            if (!isConfigured()) {
                lastState = "unavailable";
                lastErrorCode = "fulfillment_unavailable";
                log("UNAVAILABLE | " + serverLogPrefix() + "configuration missing secret/token/server_id/key_id");
                scheduleNext(EasyVipConfig.fulfillment.pollIntervalSeconds * 1000L);
                return;
            }
            if (!isSqlHealthy()) {
                lastState = "sql_unavailable";
                lastErrorCode = "sql_unavailable";
                log("UNAVAILABLE | " + serverLogPrefix() + "SQL mode not active or unhealthy");
                scheduleNext(EasyVipConfig.fulfillment.pollIntervalSeconds * 1000L);
                return;
            }

            ClaimResponse claim = claimPending();
            if (claim == null) {
                lastState = "transient_error";
                if (lastErrorCode == null || lastErrorCode.isBlank()) {
                    lastErrorCode = "claim_failed";
                }
                lastErrorAt = System.currentTimeMillis();
                consecutiveFailures++;
                scheduleNext(nextBackoffMillis());
                return;
            }

            lastClaimCount = claim.fulfillments.size();
            if (claim.fulfillments.isEmpty()) {
                lastState = "empty";
                lastEmptyAt = System.currentTimeMillis();
                consecutiveFailures = 0;
                if (!emptyPollLogged) {
                    log("EMPTY | " + serverLogPrefix() + "no fulfillments");
                    emptyPollLogged = true;
                }
                scheduleNext(EasyVipConfig.fulfillment.pollIntervalSeconds * 1000L);
                return;
            }

            emptyPollLogged = false;
            boolean sawTransient = false;
            int processed = 0;
            for (ClaimFulfillment fulfillment : claim.fulfillments) {
                lastFulfillmentId = fulfillment.fulfillmentId;
                FulfillmentOutcome outcome = processFulfillment(fulfillment);
                if (outcome.transientError) {
                    sawTransient = true;
                    lastErrorCode = outcome.errorCode;
                    lastErrorAt = System.currentTimeMillis();
                    log("RETRY | " + serverLogPrefix() + "fulfillment_id=" + fulfillment.fulfillmentId + " code=" + outcome.errorCode);
                    continue;
                }
                if (outcome.processed) {
                    processed++;
                }
            }

            lastProcessedCount = processed;
            if (sawTransient) {
                consecutiveFailures++;
                lastState = "retry";
                scheduleNext(nextBackoffMillis());
            } else {
                consecutiveFailures = 0;
                lastState = "idle";
                lastSuccessAt = System.currentTimeMillis();
                scheduleNext(EasyVipConfig.fulfillment.pollIntervalSeconds * 1000L);
            }
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private static long nextBackoffMillis() {
        long base = Math.max(1000L, EasyVipConfig.fulfillment.pollIntervalSeconds * 1000L);
        int shift = Math.min(6, Math.max(0, consecutiveFailures));
        long delay = base << shift;
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, delay / 4L) + 1L);
        return delay + jitter;
    }

    private static FulfillmentOutcome processFulfillment(ClaimFulfillment claim) {
        try {
            FulfillmentLedger ledger = SqlDatabaseManager.withConnection(conn -> processFulfillmentInTransaction(conn, claim));
            if (ledger == null) {
                return FulfillmentOutcome.transientError("ledger_unavailable");
            }

            if (ledger.status.equals("conflict")
                    || ledger.status.equals("invalid_sku")
                    || ledger.status.equals("invalid_config")
                    || ledger.status.equals("server_mismatch")) {
                lastErrorCode = ledger.errorCode;
                failFulfillment(claim.fulfillmentId, ledger.fulfillment.getClaimToken(), ledger.errorCode, ledger.errorMessage);
                return FulfillmentOutcome.definitiveFailure(ledger.errorCode);
            }

            if (ledger.items.isEmpty()) {
                return FulfillmentOutcome.definitiveFailure("empty_items");
            }

            if ("completed".equalsIgnoreCase(ledger.fulfillment.getStatus())) {
                log("ALREADY_COMPLETE | " + serverLogPrefix() + "fulfillment_id=" + ledger.fulfillment.getFulfillmentId());
                return FulfillmentOutcome.processed();
            }

            if (!ledger.readyToComplete) {
                return FulfillmentOutcome.definitiveFailure("not_ready");
            }

            CompleteResponse complete = completeFulfillment(ledger);
            if (complete == null) {
                return FulfillmentOutcome.transientError("complete_failed");
            }
            if (!complete.success) {
                if (complete.transientError) {
                    if ("lease_expired".equals(complete.errorCode)) {
                        SqlDatabaseManager.withConnection(conn -> {
                            FulfillmentRecord record = cloneRecord(ledger.fulfillment);
                            record.setStatus("lease_expired");
                            record.setUpdatedAt(System.currentTimeMillis());
                            SqlDatabaseManager.upsertWebStoreFulfillment(conn, record);
                            return null;
                        });
                    }
                    return FulfillmentOutcome.transientError(complete.errorCode);
                }
                lastErrorCode = complete.errorCode;
                return FulfillmentOutcome.definitiveFailure(complete.errorCode);
            }

            SqlDatabaseManager.withConnection(conn -> {
                markFulfillmentCompleted(conn, ledger.fulfillment);
                return null;
            });
            log("COMPLETE_OK | " + serverLogPrefix() + "fulfillment_id=" + claim.fulfillmentId + " items=" + ledger.items.size());
            PersistenceManager.log("WebStore", "fulfillment_complete", "Fulfillment " + claim.fulfillmentId + " confirmed");
            return FulfillmentOutcome.processed();
        } catch (RuntimeException e) {
            lastErrorCode = "processing_exception";
            logException("ERROR | " + serverLogPrefix() + "fulfillment_id=" + claim.fulfillmentId
                    + " | " + e.getClass().getSimpleName(), e);
            if (e instanceof IllegalArgumentException) {
                failFulfillment(claim.fulfillmentId, claim.claimToken, "processing_exception", exceptionMessage(e));
                return FulfillmentOutcome.definitiveFailure("processing_exception");
            }
            return FulfillmentOutcome.transientError("processing_exception");
        }
    }

    private static FulfillmentLedger processFulfillmentInTransaction(java.sql.Connection conn, ClaimFulfillment claim) throws java.sql.SQLException {
        conn.setAutoCommit(false);
        try {
            FulfillmentRecord existing = loadWebStoreFulfillment(conn, claim.fulfillmentId);
            String payloadDigest = claim.payloadDigest();
            long now = System.currentTimeMillis();
            String serverId = claim.resolveServerId();

            if (existing != null && !Objects.equals(existing.getPayloadDigest(), payloadDigest)) {
                FulfillmentRecord conflict = cloneRecord(existing);
                conflict.setStatus("conflict");
                conflict.setFailureCode("payload_conflict");
                conflict.setErrorMessage("conflicting payload");
                conflict.setUpdatedAt(now);
                SqlDatabaseManager.upsertWebStoreFulfillment(conn, conflict);
                conn.commit();
                return FulfillmentLedger.conflict(conflict, "idempotency_conflict", "payload_conflict");
            }

            FulfillmentRecord ledger = existing != null ? cloneRecord(existing) : new FulfillmentRecord();
            ledger.setFulfillmentId(claim.fulfillmentId);
            ledger.setOrderId(claim.orderId);
            ledger.setOriginServerId(serverId);
            ledger.setServerId(serverId);
            ledger.setMinecraftUuid(claim.minecraftUuid);
            ledger.setMinecraftUsername(claim.minecraftUsername != null ? claim.minecraftUsername : "");
            ledger.setPayloadDigest(payloadDigest);
            ledger.setRequestKeyId(EasyVipConfig.fulfillment.keyId);
            ledger.setClaimToken(claim.claimToken);
            ledger.setCreatedAt(existing != null ? existing.getCreatedAt() : now);
            ledger.setClaimedAt(now);
            ledger.setLeaseExpiresAt(claim.leaseExpiresAt);
            ledger.setUpdatedAt(now);
            if (!EasyVipConfig.fulfillment.serverId.equals(serverId)) {
                ledger.setStatus("server_mismatch");
                ledger.setFailureCode("server_mismatch");
                ledger.setErrorMessage("fulfillment belongs to another server");
                ledger.setUpdatedAt(now);
                SqlDatabaseManager.upsertWebStoreFulfillment(conn, ledger);
                conn.commit();
                return FulfillmentLedger.invalidConfig(ledger, "server_mismatch", "fulfillment belongs to another server");
            }
            if (existing == null) {
                ledger.setStatus("processing");
            } else if ("completed".equalsIgnoreCase(existing.getStatus()) || "awaiting_complete".equalsIgnoreCase(existing.getStatus())) {
                ledger.setStatus(existing.getStatus());
                ledger.setClaimToken(claim.claimToken);
                ledger.setLeaseExpiresAt(claim.leaseExpiresAt);
                ledger.setUpdatedAt(now);
                SqlDatabaseManager.upsertWebStoreFulfillment(conn, ledger);
                conn.commit();
                return FulfillmentLedger.ready(ledger, cloneItems(existing.getItems()));
            }

            List<FulfillmentItemRecord> readyItems = new ArrayList<>();
            for (ClaimItem item : claim.items) {
                if (item.quantity != 1) {
                    ledger.setStatus("invalid_config");
                    ledger.setFailureCode("unsupported_quantity");
                    ledger.setErrorMessage("quantity_must_be_1");
                    ledger.setUpdatedAt(now);
                    SqlDatabaseManager.upsertWebStoreFulfillment(conn, ledger);
                    conn.commit();
                    return FulfillmentLedger.invalidConfig(ledger, "unsupported_quantity", "quantity_must_be_1");
                }

                FulfillmentProductConfig product = EasyVipConfig.fulfillment.products.get(item.productSku);
                String productError = validateProduct(product);
                if (productError != null) {
                    ledger.setStatus("invalid_sku");
                    ledger.setFailureCode(productError);
                    ledger.setErrorMessage("invalid product");
                    ledger.setUpdatedAt(now);
                    SqlDatabaseManager.upsertWebStoreFulfillment(conn, ledger);
                    conn.commit();
                    return FulfillmentLedger.invalidConfig(ledger, productError, "invalid product");
                }

                String bindingError = validatePlayerBinding(product, claim.minecraftUuid);
                if (bindingError != null) {
                    String message = bindingError + ": minecraft_uuid is required when bind_to_player=true";
                    ledger.setStatus("invalid_config");
                    ledger.setFailureCode("processing_exception");
                    ledger.setErrorMessage(message);
                    ledger.setUpdatedAt(now);
                    SqlDatabaseManager.upsertWebStoreFulfillment(conn, ledger);
                    conn.commit();
                    return FulfillmentLedger.invalidConfig(ledger, "processing_exception", message);
                }

                FulfillmentItemRecord existingItem = findItemByLineId(conn, item.lineItemId);
                if (existingItem != null && existingItem.getKeyCode() != null && !existingItem.getKeyCode().isBlank()) {
                    readyItems.add(existingItem);
                    continue;
                }

                FulfillmentItemRecord itemRecord = existingItem != null ? existingItem : new FulfillmentItemRecord();
                itemRecord.setLineItemId(item.lineItemId);
                itemRecord.setFulfillmentId(claim.fulfillmentId);
                itemRecord.setProductSku(item.productSku);
                itemRecord.setQuantity(item.quantity);
                itemRecord.setStatus("pending");
                itemRecord.setCreatedAt(existingItem != null ? existingItem.getCreatedAt() : now);
                itemRecord.setUpdatedAt(now);
                SqlDatabaseManager.upsertWebStoreFulfillmentItem(conn, itemRecord);

                if (itemRecord.getKeyCode() == null || itemRecord.getKeyCode().isBlank()) {
                    KeyRecord keyRecord = generateKeyRecordForProduct(product, claim.minecraftUuid, now);
                    keyRecord.setCode(generateUniqueKeyCode(conn));
                    SqlDatabaseManager.insertKeyRecord(conn, keyRecord);
                    itemRecord.setKeyCode(keyRecord.getCode());
                    itemRecord.setKeyFingerprint(KeySecurity.fingerprintKey(keyRecord.getCode()));
                    itemRecord.setStatus("generated");
                    itemRecord.setUpdatedAt(now);
                    SqlDatabaseManager.upsertWebStoreFulfillmentItem(conn, itemRecord);
                } else {
                    itemRecord.setStatus(itemRecord.getStatus() == null || itemRecord.getStatus().isBlank()
                            ? "generated"
                            : itemRecord.getStatus());
                    itemRecord.setUpdatedAt(now);
                    SqlDatabaseManager.upsertWebStoreFulfillmentItem(conn, itemRecord);
                }
                readyItems.add(itemRecord);
            }

            ledger.getItems().clear();
            ledger.getItems().addAll(cloneItems(readyItems));
            ledger.setStatus("awaiting_complete");
            ledger.setUpdatedAt(now);
            SqlDatabaseManager.upsertWebStoreFulfillment(conn, ledger);
            conn.commit();
            return FulfillmentLedger.ready(ledger, readyItems);
        } catch (java.sql.SQLException e) {
            try {
                conn.rollback();
            } catch (java.sql.SQLException ignored) {
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (java.sql.SQLException ignored) {
            }
        }
    }

    private static FulfillmentRecord loadWebStoreFulfillment(java.sql.Connection conn, String fulfillmentId) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM webstore_fulfillments WHERE fulfillment_id = ?")) {
            ps.setString(1, fulfillmentId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    FulfillmentRecord rec = mapWebStoreFulfillment(rs);
                    rec.getItems().addAll(loadWebStoreItems(conn, fulfillmentId));
                    return rec;
                }
            }
        }
        return null;
    }

    private static FulfillmentRecord mapWebStoreFulfillment(java.sql.ResultSet rs) throws java.sql.SQLException {
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

    private static List<FulfillmentItemRecord> loadWebStoreItems(java.sql.Connection conn, String fulfillmentId) throws java.sql.SQLException {
        List<FulfillmentItemRecord> result = new ArrayList<>();
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM webstore_fulfillment_items WHERE fulfillment_id = ?")) {
            ps.setString(1, fulfillmentId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapWebStoreItem(rs));
                }
            }
        }
        return result;
    }

    private static FulfillmentItemRecord mapWebStoreItem(java.sql.ResultSet rs) throws java.sql.SQLException {
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
        return item;
    }

    private static List<FulfillmentItemRecord> cloneItems(List<FulfillmentItemRecord> items) {
        List<FulfillmentItemRecord> copies = new ArrayList<>();
        if (items == null) {
            return copies;
        }
        for (FulfillmentItemRecord item : items) {
            FulfillmentItemRecord copy = new FulfillmentItemRecord();
            copy.setLineItemId(item.getLineItemId());
            copy.setFulfillmentId(item.getFulfillmentId());
            copy.setProductSku(item.getProductSku());
            copy.setQuantity(item.getQuantity());
            copy.setKeyCode(item.getKeyCode());
            copy.setKeyFingerprint(item.getKeyFingerprint());
            copy.setStatus(item.getStatus());
            copy.setCreatedAt(item.getCreatedAt());
            copy.setUpdatedAt(item.getUpdatedAt());
            copies.add(copy);
        }
        return copies;
    }

    private static FulfillmentItemRecord findItemByLineId(java.sql.Connection conn, String lineItemId) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM webstore_fulfillment_items WHERE line_item_id = ?")) {
            ps.setString(1, lineItemId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
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
                    return item;
                }
            }
        }
        return null;
    }

    private static String validateProduct(FulfillmentProductConfig product) {
        if (product == null) {
            return "unknown_sku";
        }
        String type = product.normalizedType();
        switch (type) {
            case "vip":
                if (product.tierId == null || product.tierId.isBlank()) {
                    return "missing_tier_id";
                }
                if (!EasyVipConfig.tiers.list.containsKey(product.tierId)) {
                    return "unknown_tier";
                }
                if (product.duration == null || product.duration.isBlank()) {
                    return "missing_duration";
                }
                try {
                    long dur = br.com.pedrodalben.easyvip.util.DurationParser.parseDurationMillis(product.duration);
                    if (dur == 0 || (dur < 0 && dur != -1)) {
                        return "invalid_duration";
                    }
                } catch (Exception e) {
                    return "invalid_duration";
                }
                break;
            case "reward":
                if (product.rewardKeyId == null || product.rewardKeyId.isBlank()) {
                    return "missing_reward_key_id";
                }
                if (!EasyVipConfig.rewardKeys.list.containsKey(product.rewardKeyId)) {
                    return "unknown_reward_key";
                }
                break;
            default:
                return "invalid_kind";
        }
        return null;
    }

    private static String validatePlayerBinding(FulfillmentProductConfig product, String minecraftUuid) {
        if (!product.bindToPlayer) {
            return null;
        }
        if (minecraftUuid == null || minecraftUuid.isBlank()) {
            return "missing_minecraft_uuid";
        }
        try {
            UUID.fromString(minecraftUuid);
            return null;
        } catch (IllegalArgumentException e) {
            return "invalid_minecraft_uuid";
        }
    }

    private static KeyRecord generateKeyRecordForProduct(FulfillmentProductConfig product, String minecraftUuid, long now) {
        UUID bound = product.bindToPlayer ? UUID.fromString(minecraftUuid) : null;
        long expiry = product.parseExpiresAfterMillis();
        if (expiry != -1) {
            expiry = now + expiry;
        }
        String type = product.normalizedType();
        return switch (type) {
            case "vip" -> KeyService.createVipKeyRecord(product.tierId, product.duration, product.maxUses, bound, expiry, null);
            case "reward" -> KeyService.createRewardKeyRecord(product.rewardKeyId, product.maxUses, bound, expiry, null);
            default -> throw new IllegalArgumentException("Unsupported product kind: " + type);
        };
    }

    private static String generateUniqueKeyCode(java.sql.Connection conn) throws java.sql.SQLException {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = br.com.pedrodalben.easyvip.util.UniqueCodeGenerator.generateCandidate(
                    EasyVipConfig.common.keyCharset,
                    EasyVipConfig.common.keyLength,
                    EasyVipConfig.fulfillment.keyPrefix
            );
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (keyExists(conn, candidate)) {
                continue;
            }
            return candidate;
        }
        throw new java.sql.SQLException("Could not allocate unique key code after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    private static boolean keyExists(java.sql.Connection conn, String code) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM easyvip_keys WHERE code = ?")) {
            ps.setString(1, code);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static CompleteResponse completeFulfillment(FulfillmentLedger ledger) {
        try {
            String path = String.format(Locale.ROOT, COMPLETE_PATH_TEMPLATE, ledger.fulfillment.getFulfillmentId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("server_id", EasyVipConfig.fulfillment.serverId);
            body.put("claim_token", ledger.fulfillment.getClaimToken());
            List<Map<String, Object>> items = new ArrayList<>();
            for (FulfillmentItemRecord item : ledger.items) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("line_item_id", item.getLineItemId());
                map.put("product_sku", item.getProductSku());
                map.put("activation_key", item.getKeyCode());
                map.put("key_fingerprint", item.getKeyFingerprint());
                items.add(map);
            }
            body.put("items", items);

            SignedResponse response = sendSignedJson("POST", path, GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
            if (!response.success()) {
                return CompleteResponse.transientError("invalid_response_signature");
            }
            ensureResponseServerId(response.bodyBytes());
            if (response.statusCode() == 204 || response.statusCode() == 200) {
                return CompleteResponse.success();
            }
            String errorCode = extractErrorCode(response.bodyBytes());
            if (response.statusCode() == 409) {
                if (isAlreadyCompleteError(errorCode)) {
                    return CompleteResponse.success();
                }
                if (isLeaseExpiredError(errorCode)) {
                    return CompleteResponse.transientError(errorCode);
                }
                return CompleteResponse.transientError(errorCode != null ? errorCode : "lease_conflict");
            }
            if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500) {
                return CompleteResponse.transientError(errorCode != null ? errorCode : "http_" + response.statusCode());
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return CompleteResponse.success();
            }
            return CompleteResponse.definitiveError(errorCode != null ? errorCode : "http_" + response.statusCode());
        } catch (IllegalArgumentException e) {
            if ("server_mismatch".equals(e.getMessage())) {
                return CompleteResponse.definitiveError("server_mismatch");
            }
            lastErrorCode = "complete_exception";
            log("ERROR | " + serverLogPrefix() + "complete request failed: " + e.getClass().getSimpleName());
            return null;
        } catch (Exception e) {
            lastErrorCode = "complete_exception";
            log("ERROR | " + serverLogPrefix() + "complete request failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static void failFulfillment(String fulfillmentId, String claimToken, String errorCode, String errorMessage) {
        try {
            String path = String.format(Locale.ROOT, FAIL_PATH_TEMPLATE, fulfillmentId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("server_id", EasyVipConfig.fulfillment.serverId);
            body.put("claim_token", claimToken != null ? claimToken : "");
            body.put("error_code", errorCode);
            body.put("error_message", errorMessage != null ? errorMessage : "");
            SignedResponse response = sendSignedJson("POST", path, GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
            if (response.success()) {
                ensureResponseServerId(response.bodyBytes());
                String responseError = extractErrorCode(response.bodyBytes());
                if (response.statusCode() == 409 && isLeaseExpiredError(responseError)) {
                    log("FAIL_RETRY | " + serverLogPrefix() + "fulfillment_id=" + fulfillmentId + " code=" + errorCode);
                } else {
                    log("FAIL_OK | " + serverLogPrefix() + "fulfillment_id=" + fulfillmentId + " code=" + errorCode);
                    PersistenceManager.log("WebStore", "fulfillment_failed", "Fulfillment " + fulfillmentId + " failed: " + errorCode);
                }
            }
        } catch (IllegalArgumentException e) {
            if ("server_mismatch".equals(e.getMessage())) {
                log("ERROR | " + serverLogPrefix() + "fail response rejected: server_mismatch");
                return;
            }
            log("ERROR | " + serverLogPrefix() + "fail request failed: " + e.getClass().getSimpleName());
        } catch (Exception e) {
            log("ERROR | " + serverLogPrefix() + "fail request failed: " + e.getClass().getSimpleName());
        }
    }

    private static ClaimResponse claimPending() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("server_id", EasyVipConfig.fulfillment.serverId);
            body.put("limit", EasyVipConfig.fulfillment.claimLimit);
            SignedResponse response = sendSignedJson("POST", CLAIM_PATH, GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
            if (!response.success()) {
                lastErrorCode = "invalid_response_signature";
                return null;
            }
            if (response.statusCode() == 204) {
                return new ClaimResponse();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorCode = extractErrorCode(response.bodyBytes());
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    lastErrorCode = errorCode != null ? errorCode : "auth_failed";
                    log("ERROR | " + serverLogPrefix() + "claim auth failed code=" + lastErrorCode);
                    return null;
                }
                if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500) {
                    lastErrorCode = errorCode != null ? errorCode : "http_" + response.statusCode();
                    return null;
                }
                lastErrorCode = errorCode != null ? errorCode : "http_" + response.statusCode();
                log("ERROR | " + serverLogPrefix() + "claim rejected code=" + lastErrorCode);
                return null;
            }
            ensureResponseServerId(response.bodyBytes());
            return parseClaimResponse(response.bodyBytes());
        } catch (IllegalArgumentException e) {
            if ("server_mismatch".equals(e.getMessage())) {
                lastErrorCode = "server_mismatch";
                log("ERROR | " + serverLogPrefix() + "claim rejected: server_mismatch");
                return null;
            }
            lastErrorCode = "claim_exception";
            log("ERROR | " + serverLogPrefix() + "claim failed: " + e.getClass().getSimpleName());
            return null;
        } catch (Exception e) {
            lastErrorCode = "claim_exception";
            log("ERROR | " + serverLogPrefix() + "claim failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static SignedResponse sendSignedJson(String method, String path, byte[] body) throws Exception {
        String secret = resolveSecret();
        String token = resolveToken();
        if (secret == null || secret.isBlank() || token == null || token.isBlank()) {
            return SignedResponse.failure(-1, new byte[0]);
        }

        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String bodyHash = sha256(body);
        String canonical = method.toUpperCase(Locale.ROOT) + "\n"
                + path + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + bodyHash;
        String signature = SIGNATURE_PREFIX + hmacSha256(secret, canonical);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(Math.max(1, EasyVipConfig.fulfillment.requestTimeoutSeconds)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-EasyVip-Key-Id", EasyVipConfig.fulfillment.keyId)
                .header("X-EasyVip-Timestamp", Long.toString(timestamp))
                .header("X-EasyVip-Nonce", nonce)
                .header("X-EasyVip-Signature", signature)
                .method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] responseBody;
        try (InputStream responseStream = response.body()) {
            responseBody = responseStream == null ? new byte[0] : responseStream.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (responseBody.length > MAX_RESPONSE_BYTES) {
            return SignedResponse.failure(response.statusCode(), new byte[0]);
        }
        if (!validateResponseSignature(response, responseBody, nonce, secret)) {
            return SignedResponse.failure(response.statusCode(), responseBody);
        }
        return SignedResponse.success(response.statusCode(), responseBody);
    }

    private static boolean validateResponseSignature(HttpResponse<?> response, byte[] body, String nonce, String secret) {
        String tsHeader = response.headers().firstValue("X-EasyVip-Response-Timestamp").orElse(null);
        String sigHeader = response.headers().firstValue("X-EasyVip-Response-Signature").orElse(null);
        if (tsHeader == null || sigHeader == null || !sigHeader.startsWith(RESPONSE_SIGNATURE_PREFIX)) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(tsHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        long tolerance = Math.max(1L, EasyVipConfig.fulfillment.timestampToleranceSeconds);
        long pastDelta = timestamp < now ? now - timestamp : 0L;
        long futureDelta = timestamp > now ? timestamp - now : 0L;
        if (pastDelta < 0L || futureDelta < 0L
                || pastDelta > tolerance || futureDelta > tolerance) {
            return false;
        }
        String canonical = timestamp + "\n"
                + nonce + "\n"
                + response.statusCode() + "\n"
                + sha256(body);
        String expected = RESPONSE_SIGNATURE_PREFIX + hmacSha256(secret, canonical);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sigHeader.getBytes(StandardCharsets.UTF_8));
    }

    private static ClaimResponse parseClaimResponse(byte[] bodyBytes) throws Exception {
        String raw = new String(bodyBytes, StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("claim_response_not_object");
        }
        JsonObject root = parsed.getAsJsonObject();
        validateExactKeys(root, "server_id", "fulfillments");
        String rootServerId = null;
        if (root.has("server_id")) {
            rootServerId = getJsonString(root, "server_id");
            if (rootServerId == null || rootServerId.isBlank()) {
                throw new IllegalArgumentException("missing_server_id");
            }
            if (!EasyVipConfig.fulfillment.serverId.equals(rootServerId)) {
                throw new IllegalArgumentException("server_mismatch");
            }
        }
        if (!root.has("fulfillments") || !root.get("fulfillments").isJsonArray()) {
            throw new IllegalArgumentException("missing_fulfillments");
        }

        ClaimResponse response = new ClaimResponse();
        JsonArray array = root.getAsJsonArray("fulfillments");
        if (array.size() > MAX_FULFILLMENTS_PER_RESPONSE) {
            throw new IllegalArgumentException("too_many_fulfillments");
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("invalid_fulfillment");
            }
            JsonObject obj = element.getAsJsonObject();
            validateExactKeys(obj, "fulfillment_id", "order_id", "minecraft_uuid", "minecraft_username",
                    "claim_token", "lease_expires_at", "origin_server_id", "server_id", "items");
            ClaimFulfillment fulfillment = new ClaimFulfillment();
            fulfillment.fulfillmentId = requiredJsonString(obj, "fulfillment_id");
            if (!isSafePathSegment(fulfillment.fulfillmentId)) {
                throw new IllegalArgumentException("invalid_fulfillment_id");
            }
            fulfillment.orderId = requiredJsonString(obj, "order_id");
            fulfillment.minecraftUuid = getJsonString(obj, "minecraft_uuid");
            fulfillment.minecraftUsername = getJsonString(obj, "minecraft_username");
            fulfillment.claimToken = getJsonString(obj, "claim_token");
            fulfillment.leaseExpiresAt = parseLeaseExpiresAt(getJsonValue(obj, "lease_expires_at"));
            fulfillment.originServerId = firstNonBlank(getJsonString(obj, "origin_server_id"), getJsonString(obj, "server_id"), rootServerId);
            if (fulfillment.originServerId == null || fulfillment.originServerId.isBlank()) {
                throw new IllegalArgumentException("missing_server_id");
            }
            if (!EasyVipConfig.fulfillment.serverId.equals(fulfillment.originServerId)) {
                throw new IllegalArgumentException("server_mismatch");
            }
            if (fulfillment.claimToken == null || fulfillment.claimToken.isBlank()) {
                throw new IllegalArgumentException("missing_claim_token");
            }
            if (fulfillment.leaseExpiresAt == null) {
                throw new IllegalArgumentException("missing_lease_expires_at");
            }
            JsonArray items = obj.getAsJsonArray("items");
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("empty_items");
            }
            if (items.size() > MAX_ITEMS_PER_FULFILLMENT) {
                throw new IllegalArgumentException("too_many_items");
            }
            for (JsonElement itemEl : items) {
                if (!itemEl.isJsonObject()) {
                    throw new IllegalArgumentException("invalid_item");
                }
                JsonObject itemObj = itemEl.getAsJsonObject();
                validateExactKeys(itemObj, "line_item_id", "product_sku", "quantity");
                ClaimItem item = new ClaimItem();
                item.lineItemId = requiredJsonString(itemObj, "line_item_id");
                item.productSku = requiredJsonString(itemObj, "product_sku");
                item.quantity = getJsonInt(itemObj, "quantity");
                fulfillment.items.add(item);
            }
            response.fulfillments.add(fulfillment);
        }
        return response;
    }

    private static void validateExactKeys(JsonObject obj, String... allowed) {
        for (String key : obj.keySet()) {
            boolean found = false;
            for (String allow : allowed) {
                if (allow.equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("unexpected_field:" + key);
            }
        }
    }

    private static String getJsonString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException("invalid_field:" + key);
        }
        String value = element.getAsString();
        if (value.length() > 4096) throw new IllegalArgumentException("field_too_long:" + key);
        return value;
    }

    private static String requiredJsonString(JsonObject obj, String key) {
        String value = getJsonString(obj, key);
        if (value.isBlank()) throw new IllegalArgumentException("missing_field:" + key);
        return value;
    }

    private static boolean isSafePathSegment(String value) {
        return value.length() <= 255 && value.chars().allMatch(character ->
                Character.isLetterOrDigit(character) || ".-_~".indexOf(character) >= 0);
    }

    private static int getJsonInt(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException("invalid_field:" + key);
        }
        try {
            return element.getAsInt();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_field:" + key);
        }
    }

    private static JsonElement getJsonValue(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key) : null;
    }

    private static String extractErrorCode(byte[] bodyBytes) {
        try {
            String raw = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                JsonElement errorCode = obj.get("error_code");
                if (errorCode != null && errorCode.isJsonPrimitive()) {
                    return errorCode.getAsString();
                }
                JsonElement error = obj.get("error");
                if (error != null && error.isJsonPrimitive()) {
                    return error.getAsString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isLeaseExpiredError(String errorCode) {
        if (errorCode == null) {
            return false;
        }
        String normalized = errorCode.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("lease_expired")
                || normalized.equals("lease_expired_conflict")
                || normalized.equals("lease_conflict");
    }

    private static boolean isAlreadyCompleteError(String errorCode) {
        if (errorCode == null) {
            return false;
        }
        String normalized = errorCode.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("already_completed")
                || normalized.equals("already_complete")
                || normalized.equals("duplicate_complete");
    }

    private static void ensureResponseServerId(byte[] bodyBytes) {
        try {
            String raw = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (!obj.has("server_id")) {
                return;
            }
            String serverId = getJsonString(obj, "server_id");
            if (!serverId.isBlank() && !EasyVipConfig.fulfillment.serverId.equals(serverId)) {
                throw new IllegalArgumentException("server_mismatch");
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException && "server_mismatch".equals(e.getMessage())) {
                throw (IllegalArgumentException) e;
            }
        }
    }

    private static Long parseLeaseExpiresAt(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            if (value.isJsonPrimitive()) {
                var primitive = value.getAsJsonPrimitive();
                if (primitive.isNumber()) {
                    long numeric = primitive.getAsLong();
                    if (numeric < 1_000_000_000_000L) {
                        numeric *= 1000L;
                    }
                    return numeric;
                }
                if (primitive.isString()) {
                    String raw = primitive.getAsString().trim();
                    if (raw.isEmpty()) {
                        return null;
                    }
                    if (raw.chars().allMatch(Character::isDigit)) {
                        long numeric = Long.parseLong(raw);
                        if (numeric < 1_000_000_000_000L) {
                            numeric *= 1000L;
                        }
                        return numeric;
                    }
                    return Instant.parse(raw).toEpochMilli();
                }
            }
        } catch (DateTimeParseException | NumberFormatException ignored) {
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static FulfillmentRecord cloneRecord(FulfillmentRecord source) {
        FulfillmentRecord record = new FulfillmentRecord();
        record.setFulfillmentId(source.getFulfillmentId());
        record.setOrderId(source.getOrderId());
        record.setOriginServerId(source.getOriginServerId());
        record.setServerId(source.getServerId());
        record.setMinecraftUuid(source.getMinecraftUuid());
        record.setMinecraftUsername(source.getMinecraftUsername());
        record.setPayloadDigest(source.getPayloadDigest());
        record.setStatus(source.getStatus());
        record.setRequestKeyId(source.getRequestKeyId());
        record.setClaimToken(source.getClaimToken());
        record.setCreatedAt(source.getCreatedAt());
        record.setClaimedAt(source.getClaimedAt());
        record.setLeaseExpiresAt(source.getLeaseExpiresAt());
        record.setCompletedAt(source.getCompletedAt());
        record.setFailedAt(source.getFailedAt());
        record.setFailureCode(source.getFailureCode());
        record.setErrorMessage(source.getErrorMessage());
        record.setUpdatedAt(source.getUpdatedAt());
        for (FulfillmentItemRecord item : source.getItems()) {
            FulfillmentItemRecord copy = new FulfillmentItemRecord();
            copy.setLineItemId(item.getLineItemId());
            copy.setFulfillmentId(item.getFulfillmentId());
            copy.setProductSku(item.getProductSku());
            copy.setQuantity(item.getQuantity());
            copy.setKeyCode(item.getKeyCode());
            copy.setKeyFingerprint(item.getKeyFingerprint());
            copy.setStatus(item.getStatus());
            copy.setCreatedAt(item.getCreatedAt());
            copy.setUpdatedAt(item.getUpdatedAt());
            record.getItems().add(copy);
        }
        return record;
    }

    private static String baseUrl() {
        return EasyVipConfig.webstore.apiUrl != null ? EasyVipConfig.webstore.apiUrl.replaceAll("/+$", "") : "";
    }

    private static String resolveSecret() {
        FulfillmentKeyConfig.KeyEntry configured = EasyVipConfig.fulfillment.keys.keys.get(EasyVipConfig.fulfillment.keyId);
        if (configured == null) {
            configured = EasyVipConfig.fulfillment.keys.current;
        }
        if (configured != null) {
            String inline = configured.resolveSecret();
            if (inline != null && !inline.isBlank()) {
                return inline;
            }
        }
        String env = EasyVipConfig.fulfillment.secretEnv;
        if (env != null && !env.isBlank()) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String resolveToken() {
        if (EasyVipConfig.fulfillment.token != null && !EasyVipConfig.fulfillment.token.isBlank()) {
            return EasyVipConfig.fulfillment.token;
        }
        String env = EasyVipConfig.fulfillment.tokenEnv;
        if (env != null && !env.isBlank()) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void markFulfillmentCompleted(java.sql.Connection conn, FulfillmentRecord record) throws java.sql.SQLException {
        record.setStatus("completed");
        record.setCompletedAt(System.currentTimeMillis());
        record.setUpdatedAt(System.currentTimeMillis());
        SqlDatabaseManager.upsertWebStoreFulfillment(conn, record);
        for (FulfillmentItemRecord item : record.getItems()) {
            item.setStatus("completed");
            item.setUpdatedAt(System.currentTimeMillis());
            SqlDatabaseManager.upsertWebStoreFulfillmentItem(conn, item);
        }
    }

    private static void setUnavailable(String state) {
        running = false;
        lastState = state;
    }

    private static String formatTs(long ts) {
        if (ts <= 0L) {
            return "never";
        }
        return Instant.ofEpochMilli(ts).toString();
    }

    private static void log(String message) {
        if (logFile == null) {
            System.out.println("[EasyVip-Fulfillment] " + message);
            return;
        }
        String line = "[" + Instant.now().toString() + "] " + message + System.lineSeparator();
        try {
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[EasyVip-Fulfillment] Failed to write log: " + e.getClass().getSimpleName());
        }
        if (EasyVipConfig.common.debug) {
            System.out.println("[EasyVip-Fulfillment] " + message);
        }
    }

    private static void logException(String message, Throwable error) {
        log(message + " (" + error.getClass().getSimpleName() + ")");
    }

    private static String exceptionMessage(Throwable error) {
        return error.getClass().getSimpleName();
    }

    private static String serverLogPrefix() {
        return "server_id=" + EasyVipConfig.fulfillment.serverId + " ";
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (Exception e) {
            throw new RuntimeException("HMAC failed", e);
        }
    }

    private static String sha256(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private record SignedResponse(boolean success, int statusCode, byte[] bodyBytes) {
        static SignedResponse success(int statusCode, byte[] bodyBytes) {
            return new SignedResponse(true, statusCode, bodyBytes != null ? bodyBytes : new byte[0]);
        }

        static SignedResponse failure(int statusCode, byte[] bodyBytes) {
            return new SignedResponse(false, statusCode, bodyBytes != null ? bodyBytes : new byte[0]);
        }
    }

    private static final class CompleteResponse {
        final boolean success;
        final boolean transientError;
        final String errorCode;

        private CompleteResponse(boolean success, boolean transientError, String errorCode) {
            this.success = success;
            this.transientError = transientError;
            this.errorCode = errorCode;
        }

        static CompleteResponse success() {
            return new CompleteResponse(true, false, null);
        }

        static CompleteResponse transientError(String errorCode) {
            return new CompleteResponse(false, true, errorCode);
        }

        static CompleteResponse definitiveError(String errorCode) {
            return new CompleteResponse(false, false, errorCode);
        }
    }

    private static final class ClaimResponse {
        final List<ClaimFulfillment> fulfillments = new ArrayList<>();
    }

    private static final class ClaimFulfillment {
        String fulfillmentId;
        String orderId;
        String minecraftUuid;
        String minecraftUsername;
        String claimToken;
        Long leaseExpiresAt;
        String originServerId;
        final List<ClaimItem> items = new ArrayList<>();

        String resolveServerId() {
            return originServerId != null && !originServerId.isBlank() ? originServerId : EasyVipConfig.fulfillment.serverId;
        }

        String payloadDigest() {
            StringBuilder sb = new StringBuilder();
            sb.append(fulfillmentId).append('\n')
                    .append(orderId).append('\n')
                    .append(resolveServerId()).append('\n')
                    .append(minecraftUuid).append('\n')
                    .append(minecraftUsername).append('\n');
            for (ClaimItem item : items) {
                sb.append(item.lineItemId).append('|')
                        .append(item.productSku).append('|')
                        .append(item.quantity).append('\n');
            }
            return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class ClaimItem {
        String lineItemId;
        String productSku;
        int quantity;
    }

    private static final class FulfillmentLedger {
        final FulfillmentRecord fulfillment;
        final List<FulfillmentItemRecord> items;
        final boolean readyToComplete;
        final String status;
        final String errorCode;
        final String errorMessage;

        private FulfillmentLedger(FulfillmentRecord fulfillment, List<FulfillmentItemRecord> items, boolean readyToComplete, String status, String errorCode, String errorMessage) {
            this.fulfillment = fulfillment;
            this.items = items;
            this.readyToComplete = readyToComplete;
            this.status = status;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static FulfillmentLedger ready(FulfillmentRecord fulfillment, List<FulfillmentItemRecord> items) {
            return new FulfillmentLedger(fulfillment, items, true, "ready", null, null);
        }

        static FulfillmentLedger conflict(FulfillmentRecord fulfillment, String errorCode, String errorMessage) {
            return new FulfillmentLedger(fulfillment, new ArrayList<>(), false, "conflict", errorCode, errorMessage);
        }

        static FulfillmentLedger invalidConfig(FulfillmentRecord fulfillment, String errorCode, String errorMessage) {
            return new FulfillmentLedger(fulfillment, new ArrayList<>(), false, "invalid_config", errorCode, errorMessage);
        }
    }

    private static final class FulfillmentOutcome {
        final boolean processed;
        final boolean transientError;
        final String errorCode;

        private FulfillmentOutcome(boolean processed, boolean transientError, String errorCode) {
            this.processed = processed;
            this.transientError = transientError;
            this.errorCode = errorCode;
        }

        static FulfillmentOutcome processed() {
            return new FulfillmentOutcome(true, false, null);
        }

        static FulfillmentOutcome transientError(String errorCode) {
            return new FulfillmentOutcome(false, true, errorCode);
        }

        static FulfillmentOutcome definitiveFailure(String errorCode) {
            return new FulfillmentOutcome(false, false, errorCode);
        }
    }
}

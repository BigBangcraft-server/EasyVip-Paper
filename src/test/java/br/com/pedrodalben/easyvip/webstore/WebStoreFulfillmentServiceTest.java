package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class WebStoreFulfillmentServiceTest {

    private static final Gson GSON = new Gson();
    private static final String SECRET = "test-fulfillment-secret";
    private static final String TOKEN = "test-fulfillment-token";
    private static final String SERVER_ID = "allthemons";
    private static final String KEY_ID = "current";

    @TempDir
    Path tempDir;

    private final AtomicReference<String> originalFulfillmentSecret = new AtomicReference<>();
    private final AtomicReference<String> originalFulfillmentToken = new AtomicReference<>();
    private final AtomicReference<String> originalFulfillmentTokenEnv = new AtomicReference<>();
    private final AtomicReference<String> originalFulfillmentSecretEnv = new AtomicReference<>();
    private final AtomicReference<String> originalFulfillmentServerId = new AtomicReference<>();
    private final AtomicReference<String> originalFulfillmentKeyId = new AtomicReference<>();
    private final AtomicReference<String> originalFulfillmentKeyPrefix = new AtomicReference<>();
    private final AtomicReference<Boolean> originalFulfillmentEnabled = new AtomicReference<>();
    private final AtomicReference<Integer> originalPollInterval = new AtomicReference<>();
    private final AtomicReference<Integer> originalClaimLimit = new AtomicReference<>();
    private final AtomicReference<Integer> originalRequestTimeout = new AtomicReference<>();
    private final AtomicReference<Integer> originalTimestampTolerance = new AtomicReference<>();
    private final AtomicReference<Boolean> originalWebstoreEnabled = new AtomicReference<>();
    private final AtomicReference<String> originalWebstoreApiUrl = new AtomicReference<>();
    private final AtomicReference<String> originalWebstoreApiToken = new AtomicReference<>();
    private final AtomicReference<String> originalWebstoreServerId = new AtomicReference<>();
    private final AtomicReference<Boolean> originalSqlEnabled = new AtomicReference<>();
    private final AtomicReference<String> originalSqlUrl = new AtomicReference<>();
    private final AtomicReference<String> originalSqlUsername = new AtomicReference<>();
    private final AtomicReference<String> originalSqlPassword = new AtomicReference<>();
    private final Map<String, FulfillmentKeyConfig.KeyEntry> originalKeys = new LinkedHashMap<>();
    private final Map<String, FulfillmentProductConfig> originalProducts = new LinkedHashMap<>();
    private final Map<String, EasyVipConfig.RewardKeyDefinition> originalRewardKeys = new LinkedHashMap<>();
    private final Map<String, EasyVipConfig.VipTierDefinition> originalTiers = new LinkedHashMap<>();

    private HttpServer rails;
    private TestRailsServer server;

    @BeforeEach
    void setUp() {
        backupConfig();
        WebStoreFulfillmentService.stop();
        PersistenceManager.shutdown();
        EasyVipConfig.fulfillment.products.clear();
        EasyVipConfig.fulfillment.keys.keys.clear();
        EasyVipConfig.fulfillment.keys.current.secret = SECRET;
        EasyVipConfig.fulfillment.keys.current.secretEnv = "";
        EasyVipConfig.fulfillment.keys.keys.put(KEY_ID, EasyVipConfig.fulfillment.keys.current);
        EasyVipConfig.fulfillment.enabled = true;
        EasyVipConfig.fulfillment.serverId = SERVER_ID;
        EasyVipConfig.fulfillment.keyId = KEY_ID;
        EasyVipConfig.fulfillment.keyPrefix = "ATM-";
        EasyVipConfig.fulfillment.secretEnv = "EASYVIP_FULFILLMENT_SECRET";
        EasyVipConfig.fulfillment.token = TOKEN;
        EasyVipConfig.fulfillment.tokenEnv = "EASYVIP_FULFILLMENT_TOKEN";
        EasyVipConfig.fulfillment.pollIntervalSeconds = 30;
        EasyVipConfig.fulfillment.claimLimit = 20;
        EasyVipConfig.fulfillment.requestTimeoutSeconds = 5;
        EasyVipConfig.fulfillment.timestampToleranceSeconds = 60;
        EasyVipConfig.webstore.enabled = true;
        EasyVipConfig.webstore.apiToken = TOKEN;
        EasyVipConfig.webstore.serverId = SERVER_ID;
        EasyVipConfig.integrations.sqlEnabled = true;
        EasyVipConfig.integrations.sqlUsername = "";
        EasyVipConfig.integrations.sqlPassword = "";
        EasyVipConfig.integrations.sqlUrl = "jdbc:h2:mem:" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        addRewardKey("gems_50");
        addVipTier("ultraball");
        addProduct("gems_50", "reward", "gems_50", "", "", 1);
        addProduct("vip_ultraball_30d", "vip", "", "ultraball", "30d", 1);
        PersistenceManager.initialize(tempDir);
    }

    @AfterEach
    void tearDown() {
        WebStoreFulfillmentService.stop();
        PersistenceManager.shutdown();
        if (server != null) {
            server.close();
        }
        if (rails != null) {
            rails.stop(0);
        }
        restoreConfig();
    }

    @Test
    void claimCompleteRoundTripPersistsSingleKey() throws Exception {
        AtomicInteger completeCount = new AtomicInteger();
        AtomicReference<String> activationKey = new AtomicReference<>();
        try (TestRailsServer rails = startRails(request -> {
            if (request.path.endsWith("/claim")) {
                assertEquals(SERVER_ID, request.body().get("server_id").getAsString());
                assertEquals(20, request.body().get("limit").getAsInt());
                return signedJson(200, claimBody("claim-1", leaseAt(3600), SERVER_ID, "fulfillment-1", "order-1", "line-1", "gems_50", 1));
            }
            if (request.path.endsWith("/complete")) {
                completeCount.incrementAndGet();
                JsonObject body = request.body();
                assertEquals(SERVER_ID, body.get("server_id").getAsString());
                assertEquals("claim-1", body.get("claim_token").getAsString());
                JsonArray items = body.getAsJsonArray("items");
                assertEquals(1, items.size());
                JsonObject item = items.get(0).getAsJsonObject();
                activationKey.set(item.get("activation_key").getAsString());
                assertTrue(activationKey.get().startsWith("ATM-"));
                assertTrue(item.get("key_fingerprint").getAsString().startsWith("sha256:"));
                return signedJson(204, "");
            }
            fail("Unexpected path: " + request.path);
            return signedJson(500, "{\"error\":\"unexpected_path\"}");
        })) {
            startFulfillment(rails.baseUrl());
            awaitCondition(() -> completeCount.get() == 1, Duration.ofSeconds(8));
            assertNull(rails.failure());

            assertEquals(1, PersistenceManager.getAllKeys().size());
            assertNotNull(activationKey.get());

            var fulfillment = SqlDatabaseManager.getWebStoreFulfillment("fulfillment-1");
            assertNotNull(fulfillment);
            assertEquals("completed", fulfillment.getStatus());
            assertEquals(SERVER_ID, fulfillment.getOriginServerId());
            assertEquals("claim-1", fulfillment.getClaimToken());
            assertNotNull(fulfillment.getLeaseExpiresAt());
            assertEquals(1, fulfillment.getItems().size());
            assertEquals("completed", fulfillment.getItems().get(0).getStatus());
            assertEquals(activationKey.get(), fulfillment.getItems().get(0).getKeyCode());
        }
    }

    @Test
    void leaseExpirationReclaimsExistingKeyWithoutDuplicates() throws Exception {
        AtomicInteger claimCount = new AtomicInteger();
        AtomicInteger completeCount = new AtomicInteger();
        AtomicReference<String> firstActivationKey = new AtomicReference<>();
        AtomicReference<String> secondActivationKey = new AtomicReference<>();
        try (TestRailsServer rails = startRails(request -> {
            if (request.path.endsWith("/claim")) {
                int n = claimCount.incrementAndGet();
                String claimToken = n == 1 ? "claim-1" : "claim-2";
                return signedJson(200, claimBody(claimToken, leaseAt(3600 + n), SERVER_ID, "fulfillment-lease", "order-lease", "line-lease", "gems_50", 1));
            }
            if (request.path.endsWith("/complete")) {
                int n = completeCount.incrementAndGet();
                JsonObject body = request.body();
                JsonObject item = body.getAsJsonArray("items").get(0).getAsJsonObject();
                if (n == 1) {
                    firstActivationKey.set(item.get("activation_key").getAsString());
                    return signedJson(409, "{\"server_id\":\"" + SERVER_ID + "\",\"error_code\":\"lease_expired\"}");
                }
                secondActivationKey.set(item.get("activation_key").getAsString());
                return signedJson(204, "");
            }
            fail("Unexpected path: " + request.path);
            return signedJson(500, "{\"error\":\"unexpected_path\"}");
        })) {
            startFulfillment(rails.baseUrl());
            awaitCondition(() -> completeCount.get() == 1, Duration.ofSeconds(8));
            WebStoreFulfillmentService.pollNowForTest();
            awaitCondition(() -> completeCount.get() == 2, Duration.ofSeconds(8));
            assertNull(rails.failure());

            assertEquals(1, PersistenceManager.getAllKeys().size());
            assertEquals(firstActivationKey.get(), secondActivationKey.get());

            var fulfillment = SqlDatabaseManager.getWebStoreFulfillment("fulfillment-lease");
            assertNotNull(fulfillment);
            assertEquals("completed", fulfillment.getStatus());
            assertEquals(firstActivationKey.get(), fulfillment.getItems().get(0).getKeyCode());
        }
    }

    @Test
    void rejectsQuantityGreaterThanOne() throws Exception {
        AtomicInteger failCount = new AtomicInteger();
        try (TestRailsServer rails = startRails(request -> {
            if (request.path.endsWith("/claim")) {
                return signedJson(200, claimBody("claim-qty", leaseAt(3600), SERVER_ID, "fulfillment-qty", "order-qty", "line-qty", "gems_50", 2));
            }
            if (request.path.endsWith("/fail")) {
                failCount.incrementAndGet();
                JsonObject body = request.body();
                assertEquals(SERVER_ID, body.get("server_id").getAsString());
                assertEquals("claim-qty", body.get("claim_token").getAsString());
                assertEquals("unsupported_quantity", body.get("error_code").getAsString());
                return signedJson(204, "");
            }
            fail("Unexpected path: " + request.path);
            return signedJson(500, "{\"error\":\"unexpected_path\"}");
        })) {
            startFulfillment(rails.baseUrl());
            awaitCondition(() -> failCount.get() == 1, Duration.ofSeconds(8));
            assertNull(rails.failure());

            assertTrue(PersistenceManager.getAllKeys().isEmpty());
            var fulfillment = SqlDatabaseManager.getWebStoreFulfillment("fulfillment-qty");
            assertNotNull(fulfillment);
            assertEquals("invalid_config", fulfillment.getStatus());
            assertEquals("unsupported_quantity", fulfillment.getFailureCode());
        }
    }

    @Test
    void rejectsOtherServerIdSafely() throws Exception {
        AtomicInteger claimCount = new AtomicInteger();
        try (TestRailsServer rails = startRails(request -> {
            if (request.path.endsWith("/claim")) {
                claimCount.incrementAndGet();
                return signedJson(200, claimBody("claim-wrong", leaseAt(3600), "cobbleverse", "fulfillment-wrong", "order-wrong", "line-wrong", "gems_50", 1));
            }
            fail("No follow-up calls expected");
            return signedJson(500, "{\"error\":\"unexpected_path\"}");
        })) {
            startFulfillment(rails.baseUrl());
            awaitCondition(() -> claimCount.get() == 1, Duration.ofSeconds(8));
            Thread.sleep(250);
            assertNull(rails.failure());

            assertTrue(PersistenceManager.getAllKeys().isEmpty());
            assertNull(SqlDatabaseManager.getWebStoreFulfillment("fulfillment-wrong"));
            assertTrue(WebStoreFulfillmentService.statusSummary().contains("error=server_mismatch"));
        }
    }

    @Test
    void duplicateStartDoesNotCreateParallelScheduler() throws Exception {
        AtomicInteger claimCount = new AtomicInteger();
        try (TestRailsServer rails = startRails(request -> {
            if (request.path.endsWith("/claim")) {
                claimCount.incrementAndGet();
                return signedJson(204, "");
            }
            fail("No follow-up calls expected");
            return signedJson(500, "{\"error\":\"unexpected_path\"}");
        })) {
            startFulfillment(rails.baseUrl());
            WebStoreFulfillmentService.start(tempDir);
            awaitCondition(() -> claimCount.get() == 1, Duration.ofSeconds(5));
            Thread.sleep(350);
            assertNull(rails.failure());
            assertEquals(1, claimCount.get());
        }
    }

    @Test
    void repeatedEmptyPollsStillCallEndpointButLogOnce() throws Exception {
        AtomicInteger claimCount = new AtomicInteger();
        try (TestRailsServer rails = startRails(request -> {
            if (request.path.endsWith("/claim")) {
                claimCount.incrementAndGet();
                return signedJson(204, "");
            }
            fail("Unexpected path: " + request.path);
            return signedJson(500, "{\"error\":\"unexpected_path\"}");
        })) {
            startFulfillment(rails.baseUrl());
            awaitCondition(() -> claimCount.get() == 1, Duration.ofSeconds(5));
            WebStoreFulfillmentService.pollNowForTest();

            Path logFile = tempDir.resolve("data/webstore_fulfillment.log");
            long emptyLines = Files.readAllLines(logFile).stream()
                    .filter(line -> line.contains("EMPTY |"))
                    .count();
            assertEquals(2, claimCount.get());
            assertEquals(1, emptyLines);
            assertNull(rails.failure());
        }
    }

    @Test
    void responseSignatureValidationRejectsTampering() throws Exception {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "nonce-123";
        String canonical = timestamp + "\n" + nonce + "\n" + 200 + "\n" + sha256(body);
        String signature = "v1=" + hmacSha256(SECRET, canonical);
        HttpResponse<byte[]> response = new TestResponse(200, body, Map.of(
                "X-EasyVip-Response-Timestamp", List.of(Long.toString(timestamp)),
                "X-EasyVip-Response-Signature", List.of(signature)
        ));
        boolean valid = invokeBoolean("validateResponseSignature",
                new Class<?>[]{HttpResponse.class, byte[].class, String.class, String.class},
                response, body, nonce, SECRET);
        assertTrue(valid);

        byte[] tampered = "{\"ok\":false}".getBytes(StandardCharsets.UTF_8);
        boolean invalid = invokeBoolean("validateResponseSignature",
                new Class<?>[]{HttpResponse.class, byte[].class, String.class, String.class},
                response, tampered, nonce, SECRET);
        assertFalse(invalid);
    }

    @Test
    void claimParserRejectsPathManipulationAndMissingIdentifiers() {
        JsonObject unsafe = claimBody("claim-1", leaseAt(60), SERVER_ID,
                "fulfillment/escape", "order-1", "line-1", "vip", 1);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invoke(
                "parseClaimResponse", new Class<?>[]{byte[].class},
                unsafe.toString().getBytes(StandardCharsets.UTF_8)));

        JsonObject missing = claimBody("claim-2", leaseAt(60), SERVER_ID,
                "", "order-1", "line-1", "vip", 1);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invoke(
                "parseClaimResponse", new Class<?>[]{byte[].class},
                missing.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void claimPayloadIncludesServerContext() throws Exception {
        AtomicReference<String> syncBody = new AtomicReference<>();
        try (TestRailsServer rails = startRails(request -> {
            if (request.path.endsWith("/claim")) {
                JsonObject body = request.body();
                syncBody.set(body.toString());
                assertEquals(SERVER_ID, body.get("server_id").getAsString());
                return signedJson(204, "");
            }
            fail("Unexpected path: " + request.path);
            return signedJson(500, "{\"error\":\"unexpected_path\"}");
        })) {
            startFulfillment(rails.baseUrl());
            awaitCondition(() -> syncBody.get() != null, Duration.ofSeconds(5));
            assertNull(rails.failure());
            assertTrue(syncBody.get().contains(SERVER_ID));
        }
    }

    private void startFulfillment(String baseUrl) throws Exception {
        EasyVipConfig.webstore.apiUrl = baseUrl;
        Files.createDirectories(tempDir);
        WebStoreFulfillmentService.start(tempDir);
    }

    private TestRailsServer startRails(RequestRouter router) throws IOException {
        rails = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server = new TestRailsServer(rails, SECRET, TOKEN, SERVER_ID);
        rails.createContext("/", server.new RailsHandler(router));
        rails.setExecutor(Executors.newCachedThreadPool());
        rails.start();
        return server;
    }

    private void backupConfig() {
        originalFulfillmentSecret.set(EasyVipConfig.fulfillment.keys.current.secret);
        originalFulfillmentToken.set(EasyVipConfig.fulfillment.token);
        originalFulfillmentTokenEnv.set(EasyVipConfig.fulfillment.tokenEnv);
        originalFulfillmentSecretEnv.set(EasyVipConfig.fulfillment.secretEnv);
        originalFulfillmentServerId.set(EasyVipConfig.fulfillment.serverId);
        originalFulfillmentKeyId.set(EasyVipConfig.fulfillment.keyId);
        originalFulfillmentKeyPrefix.set(EasyVipConfig.fulfillment.keyPrefix);
        originalFulfillmentEnabled.set(EasyVipConfig.fulfillment.enabled);
        originalPollInterval.set(EasyVipConfig.fulfillment.pollIntervalSeconds);
        originalClaimLimit.set(EasyVipConfig.fulfillment.claimLimit);
        originalRequestTimeout.set(EasyVipConfig.fulfillment.requestTimeoutSeconds);
        originalTimestampTolerance.set(EasyVipConfig.fulfillment.timestampToleranceSeconds);
        originalWebstoreEnabled.set(EasyVipConfig.webstore.enabled);
        originalWebstoreApiUrl.set(EasyVipConfig.webstore.apiUrl);
        originalWebstoreApiToken.set(EasyVipConfig.webstore.apiToken);
        originalWebstoreServerId.set(EasyVipConfig.webstore.serverId);
        originalSqlEnabled.set(EasyVipConfig.integrations.sqlEnabled);
        originalSqlUrl.set(EasyVipConfig.integrations.sqlUrl);
        originalSqlUsername.set(EasyVipConfig.integrations.sqlUsername);
        originalSqlPassword.set(EasyVipConfig.integrations.sqlPassword);
        originalKeys.clear();
        EasyVipConfig.fulfillment.keys.keys.forEach((k, v) -> {
            FulfillmentKeyConfig.KeyEntry copy = new FulfillmentKeyConfig.KeyEntry();
            copy.secret = v.secret;
            copy.secretEnv = v.secretEnv;
            originalKeys.put(k, copy);
        });
        originalProducts.clear();
        EasyVipConfig.fulfillment.products.forEach((k, v) -> originalProducts.put(k, copyProduct(v)));
        originalRewardKeys.clear();
        EasyVipConfig.rewardKeys.list.forEach((k, v) -> originalRewardKeys.put(k, copyRewardKey(v)));
        originalTiers.clear();
        EasyVipConfig.tiers.list.forEach((k, v) -> originalTiers.put(k, copyTier(v)));
    }

    private void restoreConfig() {
        EasyVipConfig.fulfillment.keys.current.secret = originalFulfillmentSecret.get();
        EasyVipConfig.fulfillment.token = originalFulfillmentToken.get();
        EasyVipConfig.fulfillment.tokenEnv = originalFulfillmentTokenEnv.get();
        EasyVipConfig.fulfillment.secretEnv = originalFulfillmentSecretEnv.get();
        EasyVipConfig.fulfillment.serverId = originalFulfillmentServerId.get();
        EasyVipConfig.fulfillment.keyId = originalFulfillmentKeyId.get();
        EasyVipConfig.fulfillment.keyPrefix = originalFulfillmentKeyPrefix.get();
        EasyVipConfig.fulfillment.enabled = Boolean.TRUE.equals(originalFulfillmentEnabled.get());
        EasyVipConfig.fulfillment.pollIntervalSeconds = originalPollInterval.get();
        EasyVipConfig.fulfillment.claimLimit = originalClaimLimit.get();
        EasyVipConfig.fulfillment.requestTimeoutSeconds = originalRequestTimeout.get();
        EasyVipConfig.fulfillment.timestampToleranceSeconds = originalTimestampTolerance.get();
        EasyVipConfig.webstore.enabled = Boolean.TRUE.equals(originalWebstoreEnabled.get());
        EasyVipConfig.webstore.apiUrl = originalWebstoreApiUrl.get();
        EasyVipConfig.webstore.apiToken = originalWebstoreApiToken.get();
        EasyVipConfig.webstore.serverId = originalWebstoreServerId.get();
        EasyVipConfig.integrations.sqlEnabled = Boolean.TRUE.equals(originalSqlEnabled.get());
        EasyVipConfig.integrations.sqlUrl = originalSqlUrl.get();
        EasyVipConfig.integrations.sqlUsername = originalSqlUsername.get();
        EasyVipConfig.integrations.sqlPassword = originalSqlPassword.get();
        EasyVipConfig.fulfillment.keys.keys.clear();
        originalKeys.forEach((k, v) -> {
            FulfillmentKeyConfig.KeyEntry copy = new FulfillmentKeyConfig.KeyEntry();
            copy.secret = v.secret;
            copy.secretEnv = v.secretEnv;
            EasyVipConfig.fulfillment.keys.keys.put(k, copy);
        });
        EasyVipConfig.fulfillment.products.clear();
        originalProducts.forEach((k, v) -> EasyVipConfig.fulfillment.products.put(k, copyProduct(v)));
        EasyVipConfig.rewardKeys.list.clear();
        originalRewardKeys.forEach((k, v) -> EasyVipConfig.rewardKeys.list.put(k, copyRewardKey(v)));
        EasyVipConfig.tiers.list.clear();
        originalTiers.forEach((k, v) -> EasyVipConfig.tiers.list.put(k, copyTier(v)));
    }

    private static void addRewardKey(String id) {
        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = id;
        def.displayName = id;
        EasyVipConfig.rewardKeys.list.put(id, def);
    }

    private static void addVipTier(String id) {
        EasyVipConfig.VipTierDefinition def = new EasyVipConfig.VipTierDefinition();
        def.id = id;
        def.displayName = id;
        def.priority = 1;
        def.defaultDuration = "30d";
        EasyVipConfig.tiers.list.put(id, def);
    }

    private static void addProduct(String sku, String type, String rewardKeyId, String tierId, String duration, int maxUses) {
        FulfillmentProductConfig product = new FulfillmentProductConfig();
        product.sku = sku;
        product.type = type;
        product.rewardKeyId = rewardKeyId;
        product.tierId = tierId;
        product.duration = duration;
        product.maxUses = maxUses;
        product.bindToPlayer = true;
        EasyVipConfig.fulfillment.products.put(sku, product);
    }

    private static FulfillmentProductConfig copyProduct(FulfillmentProductConfig source) {
        if (source == null) {
            return null;
        }
        FulfillmentProductConfig copy = new FulfillmentProductConfig();
        copy.sku = source.sku;
        copy.type = source.type;
        copy.kind = source.kind;
        copy.tierId = source.tierId;
        copy.duration = source.duration;
        copy.rewardKeyId = source.rewardKeyId;
        copy.maxUses = source.maxUses;
        copy.expiresAfter = source.expiresAfter;
        copy.bindToPlayer = source.bindToPlayer;
        return copy;
    }

    private static EasyVipConfig.RewardKeyDefinition copyRewardKey(EasyVipConfig.RewardKeyDefinition source) {
        if (source == null) {
            return null;
        }
        EasyVipConfig.RewardKeyDefinition copy = new EasyVipConfig.RewardKeyDefinition();
        copy.id = source.id;
        copy.displayName = source.displayName;
        copy.consumeOnUse = source.consumeOnUse;
        copy.cooldownSeconds = source.cooldownSeconds;
        copy.allowedDimensions = new ArrayList<>(source.allowedDimensions);
        copy.actions = new ArrayList<>(source.actions);
        return copy;
    }

    private static EasyVipConfig.VipTierDefinition copyTier(EasyVipConfig.VipTierDefinition source) {
        if (source == null) {
            return null;
        }
        EasyVipConfig.VipTierDefinition copy = new EasyVipConfig.VipTierDefinition();
        copy.id = source.id;
        copy.displayName = source.displayName;
        copy.description = source.description;
        copy.priority = source.priority;
        copy.defaultDuration = source.defaultDuration;
        copy.allowStacking = source.allowStacking;
        copy.activationMode = source.activationMode;
        copy.maxStackDurationSeconds = source.maxStackDurationSeconds;
        copy.color = source.color;
        return copy;
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        fail("Condition not met before timeout");
    }

    private static JsonObject claimBody(String claimToken, String leaseExpiresAt, String serverId, String fulfillmentId, String orderId, String lineItemId, String sku, int quantity) {
        JsonObject root = new JsonObject();
        root.addProperty("server_id", serverId);
        JsonArray fulfillments = new JsonArray();
        JsonObject fulfillment = new JsonObject();
        fulfillment.addProperty("fulfillment_id", fulfillmentId);
        fulfillment.addProperty("order_id", orderId);
        fulfillment.addProperty("origin_server_id", serverId);
        fulfillment.addProperty("minecraft_uuid", "e309ad92-e421-420a-8bf3-3df86db3e660");
        fulfillment.addProperty("minecraft_username", "PedropsRei");
        fulfillment.addProperty("claim_token", claimToken);
        fulfillment.addProperty("lease_expires_at", leaseExpiresAt);
        JsonArray items = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("line_item_id", lineItemId);
        item.addProperty("product_sku", sku);
        item.addProperty("quantity", quantity);
        items.add(item);
        fulfillment.add("items", items);
        fulfillments.add(fulfillment);
        root.add("fulfillments", fulfillments);
        return root;
    }

    private static String leaseAt(long secondsFromNow) {
        return Instant.now().plusSeconds(secondsFromNow).toString();
    }

    private static ResponseSpec signedJson(int statusCode, Object body) {
        if (body instanceof JsonObject jsonObject) {
            return new ResponseSpec(statusCode, jsonObject.toString());
        }
        return new ResponseSpec(statusCode, String.valueOf(body));
    }

    private static String sha256(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return bytesToHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static Object invoke(String method, Class<?>[] types, Object... args) throws Exception {
        var m = WebStoreFulfillmentService.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static boolean invokeBoolean(String method, Class<?>[] types, Object... args) throws Exception {
        return (boolean) invoke(method, types, args);
    }

    private static final class TestRailsServer implements AutoCloseable {
        private final HttpServer server;
        private final String secret;
        private final String token;
        private final String serverId;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private TestRailsServer(HttpServer server, String secret, String token, String serverId) {
            this.server = server;
            this.secret = secret;
            this.token = token;
            this.serverId = serverId;
        }

        static TestRailsServer start(String secret, String token, String serverId, RequestRouter router) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestRailsServer wrapper = new TestRailsServer(server, secret, token, serverId);
            server.createContext("/", wrapper.new RailsHandler(router));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return wrapper;
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        Throwable failure() {
            return failure.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private final class RailsHandler implements HttpHandler {
            private final RequestRouter router;

            private RailsHandler(RequestRouter router) {
                this.router = router;
            }

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String bodyText = new String(body, StandardCharsets.UTF_8);
                JsonObject json = bodyText.isBlank() ? new JsonObject() : JsonParser.parseString(bodyText).getAsJsonObject();
                TestRequest request = new TestRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders(),
                        json,
                        bodyText,
                        body
                );
                try {
                    verifyRequest(request);
                    ResponseSpec response = router.route(request);
                    sendResponse(exchange, request, response);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    byte[] errorBody = "{\"error\":\"test_failure\"}".getBytes(StandardCharsets.UTF_8);
                    sendSignedResponse(exchange, request, 500, errorBody);
                } finally {
                    exchange.close();
                }
            }
        }

        private void verifyRequest(TestRequest request) {
            assertEquals("POST", request.method());
            assertEquals("Bearer " + token, header(request.headers(), "Authorization"));
            assertEquals(KEY_ID, header(request.headers(), "X-EasyVip-Key-Id"));
            String timestamp = header(request.headers(), "X-EasyVip-Timestamp");
            String nonce = header(request.headers(), "X-EasyVip-Nonce");
            String signature = header(request.headers(), "X-EasyVip-Signature");
            assertNotNull(timestamp);
            assertNotNull(nonce);
            assertNotNull(signature);
            String canonical = request.method().toUpperCase(Locale.ROOT) + "\n"
                    + request.path() + "\n"
                    + timestamp + "\n"
                    + nonce + "\n"
                    + sha256(request.bodyBytes());
            String expected = "v1=" + hmacSha256(secret, canonical);
            assertEquals(expected, signature);
        }

        private void sendResponse(HttpExchange exchange, TestRequest request, ResponseSpec response) throws IOException {
            sendSignedResponse(exchange, request, response.statusCode(), response.body().getBytes(StandardCharsets.UTF_8));
        }

        private void sendSignedResponse(HttpExchange exchange, TestRequest request, int statusCode, byte[] body) throws IOException {
            String nonce = header(request.headers(), "X-EasyVip-Nonce");
            long timestamp = Instant.now().getEpochSecond();
            String canonical = timestamp + "\n" + nonce + "\n" + statusCode + "\n" + sha256(body);
            String signature = "v1=" + hmacSha256(secret, canonical);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-EasyVip-Response-Timestamp", Long.toString(timestamp));
            exchange.getResponseHeaders().set("X-EasyVip-Response-Signature", signature);
            if (statusCode == 204) {
                exchange.sendResponseHeaders(statusCode, -1);
                return;
            }
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
        }

        private String header(Headers headers, String name) {
            return headers.getFirst(name);
        }
    }

    @FunctionalInterface
    private interface RequestRouter {
        ResponseSpec route(TestRequest request) throws Exception;
    }

    private record ResponseSpec(int statusCode, String body) {
    }

    private record TestRequest(String method, String path, Headers headers, JsonObject body, String bodyText, byte[] bodyBytes) {
    }

    private static final class TestResponse implements HttpResponse<byte[]> {
        private final int statusCode;
        private final byte[] body;
        private final HttpHeaders headers;

        private TestResponse(int statusCode, byte[] body, Map<String, List<String>> headersMap) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = HttpHeaders.of(headersMap, (a, b) -> true);
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://localhost")).build();
        }

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public byte[] body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://localhost");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}

package br.com.pedrodalben.easyvip.persistence;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.delivery.DeliveryClaim;
import br.com.pedrodalben.easyvip.delivery.DeliveryLedger;
import br.com.pedrodalben.easyvip.delivery.DeliveryPolicy;
import br.com.pedrodalben.easyvip.delivery.DeliveryRequest;
import br.com.pedrodalben.easyvip.delivery.DeliveryStatus;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqlConcurrencyTest {

    private String dbUrl;
    private String dbUsername;
    private String dbPassword;
    private String runId;

    @BeforeEach
    void setUp() {
        EasyVipConfig.integrations.sqlPoolSize = 4;
        EasyVipConfig.integrations.sqlMinimumIdle = 1;
        EasyVipConfig.integrations.sqlConnectionTimeoutSeconds = 5;
        runId = UUID.randomUUID().toString().replace("-", "");
        dbUrl = System.getenv().getOrDefault("EASYVIP_TEST_JDBC_URL",
                "jdbc:h2:mem:easyvip_" + runId + ";DB_CLOSE_DELAY=-1");
        dbUsername = System.getenv().getOrDefault("EASYVIP_TEST_JDBC_USER", "");
        dbPassword = System.getenv().getOrDefault("EASYVIP_TEST_JDBC_PASSWORD", "");
        SqlDatabaseManager.initialize(dbUrl, dbUsername, dbPassword);
    }

    @AfterEach
    void tearDown() {
        SqlDatabaseManager.shutdown();
    }

    @Test
    void schemaMigrationIsRecordedAndKeyUseHasOneWinner() throws Exception {
        boolean migrationRecorded = SqlDatabaseManager.withConnection(conn -> {
            try (Statement statement = conn.createStatement();
                 ResultSet result = statement.executeQuery("SELECT version FROM easyvip_schema_migrations WHERE version = 1")) {
                return result.next();
            }
        });
        assertTrue(migrationRecorded);

        KeyRecord key = new KeyRecord();
        key.setCode("EVIP-RACE-" + runId);
        key.setType("custom");
        key.setMaxUses(1);
        key.setCreatedTime(System.currentTimeMillis());
        SqlDatabaseManager.putKey(key);

        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        List<SqlDatabaseManager.KeyClaimResult> claims = race(2, (start, index) -> {
            start.await();
            return SqlDatabaseManager.claimKey("EVIP-RACE-" + runId, index == 0 ? firstPlayer : secondPlayer,
                    null, true, "key-race-" + runId + "-" + index, System.currentTimeMillis(), 10_000L);
        });

        assertEquals(1, claims.stream().filter(c -> c.status() == SqlDatabaseManager.KeyClaimStatus.CLAIMED).count());
        assertEquals(1, claims.stream().filter(c -> c.status() == SqlDatabaseManager.KeyClaimStatus.NO_USES_LEFT).count());
        SqlDatabaseManager.KeyClaimResult winner = claims.stream()
                .filter(c -> c.status() == SqlDatabaseManager.KeyClaimStatus.CLAIMED).findFirst().orElseThrow();
        UUID winnerUuid = claims.get(0).status() == SqlDatabaseManager.KeyClaimStatus.CLAIMED ? firstPlayer : secondPlayer;
        assertTrue(SqlDatabaseManager.completeKeyClaim(winner.claimId(), winnerUuid, true, System.currentTimeMillis()));
        assertEquals(1, SqlDatabaseManager.getKey("EVIP-RACE-" + runId).getUsedCount());

        KeyRecord physicalKey = new KeyRecord();
        physicalKey.setCode("EVIP-PHYSICAL-" + runId);
        physicalKey.setType("custom");
        physicalKey.setMaxUses(1);
        physicalKey.setCreatedTime(System.currentTimeMillis());
        SqlDatabaseManager.putKey(physicalKey);
        SqlDatabaseManager.KeyClaimResult physicalClaim = SqlDatabaseManager.claimKey(
                "EVIP-PHYSICAL-" + runId, firstPlayer, "machine-1", true,
                "physical-" + runId, System.currentTimeMillis(), 10_000L);
        assertEquals(SqlDatabaseManager.KeyClaimStatus.CLAIMED, physicalClaim.status());
        assertTrue(SqlDatabaseManager.completeKeyClaim(physicalClaim.claimId(), firstPlayer, true, System.currentTimeMillis()));
        assertEquals(SqlDatabaseManager.KeyClaimStatus.ALREADY_USED,
                SqlDatabaseManager.claimKey("EVIP-PHYSICAL-" + runId, UUID.randomUUID(), "machine-1", true,
                        "key-replay-" + runId, System.currentTimeMillis(), 10_000L).status());
    }

    @Test
    void packageClaimUniquenessAndVipCasAreDatabaseDecisions() throws Exception {
        UUID player = UUID.randomUUID();
        List<SqlDatabaseManager.PackageClaimResult> claims = race(2, (start, index) -> {
            start.await();
            return SqlDatabaseManager.claimPackage(player, "monthly", false, 0,
                    "package-race-" + runId + "-" + index, System.currentTimeMillis(), 10_000L);
        });
        assertEquals(1, claims.stream().filter(c -> c.status() == SqlDatabaseManager.PackageClaimStatus.CLAIMED).count());
        assertEquals(1, claims.stream().filter(c -> c.status() == SqlDatabaseManager.PackageClaimStatus.ALREADY_CLAIMED).count());

        SqlDatabaseManager.PackageClaimResult packageClaim = claims.stream()
                .filter(c -> c.status() == SqlDatabaseManager.PackageClaimStatus.CLAIMED).findFirst().orElseThrow();
        assertTrue(SqlDatabaseManager.completePackageClaim(packageClaim.claimId(), player, System.currentTimeMillis()));

        PlayerVipRegistry initial = new PlayerVipRegistry(player);
        initial.setPlayerName("Race");
        initial.getVips().put("vip", new PlayerVipRecord("vip", 10L, -1L, true, false));
        SqlDatabaseManager.updatePlayerVips(player, initial);
        PlayerVipRegistry first = SqlDatabaseManager.getPlayerVips(player);
        PlayerVipRegistry stale = SqlDatabaseManager.getPlayerVips(player);
        first.setLastObservedActiveVip("vip");
        SqlDatabaseManager.updatePlayerVips(player, first);

        assertThrows(java.util.ConcurrentModificationException.class,
                () -> SqlDatabaseManager.updatePlayerVips(player, stale));
    }

    @Test
    void duplicateIdempotencyRollbackAndRestartAreObservable() {
        UUID player = UUID.randomUUID();
        KeyRecord key = new KeyRecord();
        key.setCode("EVIP-IDEMPOTENT-" + runId);
        key.setType("custom");
        key.setMaxUses(1);
        key.setCreatedTime(System.currentTimeMillis());
        SqlDatabaseManager.putKey(key);

        SqlDatabaseManager.KeyClaimResult first = SqlDatabaseManager.claimKey(
                key.getCode(), player, null, true, "same-request-" + runId, System.currentTimeMillis(), 10_000L);
        SqlDatabaseManager.KeyClaimResult duplicate = SqlDatabaseManager.claimKey(
                key.getCode(), player, null, true, "same-request-" + runId, System.currentTimeMillis(), 10_000L);
        assertEquals(SqlDatabaseManager.KeyClaimStatus.CLAIMED, first.status());
        assertEquals(SqlDatabaseManager.KeyClaimStatus.ALREADY_CLAIMED, duplicate.status());
        assertTrue(SqlDatabaseManager.releaseKeyClaim(first.claimId(), "test_rollback"));

        PlayerVipRegistry invalid = new PlayerVipRegistry(player);
        invalid.setPlayerName("Rollback");
        invalid.getVips().put("invalid", new PlayerVipRecord(null, 1L, -1L, true, false));
        assertThrows(RuntimeException.class, () -> SqlDatabaseManager.updatePlayerVips(player, invalid));
        assertNull(SqlDatabaseManager.getPlayerVips(player));

        SqlDatabaseManager.shutdown();
        SqlDatabaseManager.initialize(dbUrl, dbUsername, dbPassword);
        assertNotNull(SqlDatabaseManager.getKey(key.getCode()));
        assertTrue(SqlDatabaseManager.verifyLegacyVipMigration().complete());
    }

    @Test
    void legacyVipRowIsMaterializedAndVerifiedAfterRestart() {
        UUID player = UUID.randomUUID();
        SqlDatabaseManager.withConnection(conn -> {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO easyvip_vips (player_uuid, player_name, last_observed_active_vip, vips_data)
                    VALUES (?, ?, ?, ?)
                    """)) {
                ps.setString(1, player.toString());
                ps.setString(2, "Legacy");
                ps.setString(3, "vip");
                ps.setString(4, "{\"vip\":{\"tierId\":\"vip\",\"startTime\":1,\"expiryTime\":-1,\"active\":true,\"pendingActivateActions\":false}}");
                ps.executeUpdate();
            }
            return null;
        });

        SqlDatabaseManager.shutdown();
        SqlDatabaseManager.initialize(dbUrl, dbUsername, dbPassword);
        SqlDatabaseManager.MigrationVerification verification = SqlDatabaseManager.verifyLegacyVipMigration();
        assertTrue(verification.complete());
        assertTrue(verification.legacyPlayers() >= 1);
        assertTrue(verification.migratedPlayers() >= verification.legacyPlayers());
        assertTrue(verification.legacyGrants() >= 1);
        assertTrue(SqlDatabaseManager.getPlayerVips(player).getVips().containsKey("vip"));
    }

    @Test
    void expirationTransitionIsSingleWinner() throws Exception {
        UUID player = UUID.randomUUID();
        long start = 20L;
        long expiry = System.currentTimeMillis() + 60_000L;
        PlayerVipRegistry registry = new PlayerVipRegistry(player);
        registry.setPlayerName("Expiry");
        registry.getVips().put("vip", new PlayerVipRecord("vip", start, expiry, true, false));
        SqlDatabaseManager.updatePlayerVips(player, registry);

        List<Boolean> transitions = race(2, (latch, ignored) -> {
            latch.await();
            return SqlDatabaseManager.transitionEntitlementExpired(player, "vip", start, expiry + 1);
        });
        assertEquals(1, transitions.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, transitions.stream().filter(value -> !value).count());
    }

    @Test
    void deliveryLedgerUsesDurableIdempotencyAndLeaseRecovery() throws Exception {
        UUID player = UUID.randomUUID();
        DeliveryRequest request = new DeliveryRequest(player, "grant-1", "kit.start",
                "NETWORK", "network", "delivery-race-" + runId, DeliveryPolicy.ONCE_PER_GRANT);
        DeliveryLedger ledger = DeliveryLedger.sql();
        List<DeliveryClaim> claims = race(2, (start, index) -> {
            start.await();
            return ledger.claim(request, "node-" + index, 30_000L, Clock.systemUTC());
        });
        assertEquals(1, claims.stream().filter(DeliveryClaim::acquired).count());
        assertEquals(1, claims.stream().filter(claim -> claim.status() == DeliveryStatus.IN_PROGRESS).count());

        DeliveryClaim winner = claims.stream().filter(DeliveryClaim::acquired).findFirst().orElseThrow();
        assertFalse(ledger.complete(winner.deliveryId(), player, "other-node", Clock.systemUTC()));
        assertTrue(ledger.complete(winner.deliveryId(), player,
                claims.get(0).acquired() ? "node-0" : "node-1", Clock.systemUTC()));
        assertTrue(ledger.claim(request, "node-retry", 30_000L, Clock.systemUTC()).delivered());

        DeliveryRequest recoverable = new DeliveryRequest(player, "grant-2", "kit.daily",
                "GROUP", "lobby", "delivery-lease-" + runId, DeliveryPolicy.ONCE_PER_DAY);
        Clock firstNow = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC);
        Clock afterLease = Clock.fixed(Instant.ofEpochMilli(12_001L), ZoneOffset.UTC);
        DeliveryClaim initial = ledger.claim(recoverable, "node-a", 1_000L, firstNow);
        DeliveryClaim recovered = ledger.claim(recoverable, "node-b", 1_000L, afterLease);
        assertTrue(initial.acquired());
        assertTrue(recovered.acquired());
        assertEquals(2, recovered.attempts());
        assertTrue(ledger.fail(recovered.deliveryId(), player, "node-b", "test_failure", afterLease));
        DeliveryRequest mismatch = new DeliveryRequest(player, "grant-2", "kit.other",
                "GROUP", "lobby", "delivery-lease-" + runId, DeliveryPolicy.ONCE_PER_DAY);
        assertEquals(DeliveryStatus.ERROR, ledger.claim(mismatch, "node-c", 1_000L, afterLease).status());
    }

    private static <T> List<T> race(int workers, RaceTask<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int index = i;
                futures.add(executor.submit(() -> task.run(start, index)));
            }
            start.countDown();
            List<T> values = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                values.add(future.get(10, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface RaceTask<T> {
        T run(CountDownLatch start, int index) throws Exception;
    }
}

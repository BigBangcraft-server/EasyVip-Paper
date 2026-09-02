package br.com.pedrodalben.easyvip.persistence;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqlConcurrencyTest {

    @BeforeEach
    void setUp() {
        EasyVipConfig.integrations.sqlPoolSize = 4;
        EasyVipConfig.integrations.sqlMinimumIdle = 1;
        EasyVipConfig.integrations.sqlConnectionTimeoutSeconds = 5;
        SqlDatabaseManager.initialize("jdbc:h2:mem:easyvip_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "", "");
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
        key.setCode("EVIP-RACE");
        key.setType("custom");
        key.setMaxUses(1);
        key.setCreatedTime(System.currentTimeMillis());
        SqlDatabaseManager.putKey(key);

        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        List<SqlDatabaseManager.KeyClaimResult> claims = race(2, (start, index) -> {
            start.await();
            return SqlDatabaseManager.claimKey("EVIP-RACE", index == 0 ? firstPlayer : secondPlayer,
                    null, true, "key-race-" + index, System.currentTimeMillis(), 10_000L);
        });

        assertEquals(1, claims.stream().filter(c -> c.status() == SqlDatabaseManager.KeyClaimStatus.CLAIMED).count());
        assertEquals(1, claims.stream().filter(c -> c.status() == SqlDatabaseManager.KeyClaimStatus.NO_USES_LEFT).count());
        SqlDatabaseManager.KeyClaimResult winner = claims.stream()
                .filter(c -> c.status() == SqlDatabaseManager.KeyClaimStatus.CLAIMED).findFirst().orElseThrow();
        UUID winnerUuid = claims.get(0).status() == SqlDatabaseManager.KeyClaimStatus.CLAIMED ? firstPlayer : secondPlayer;
        assertTrue(SqlDatabaseManager.completeKeyClaim(winner.claimId(), winnerUuid, true, System.currentTimeMillis()));
        assertEquals(1, SqlDatabaseManager.getKey("EVIP-RACE").getUsedCount());

        KeyRecord physicalKey = new KeyRecord();
        physicalKey.setCode("EVIP-PHYSICAL");
        physicalKey.setType("custom");
        physicalKey.setMaxUses(1);
        physicalKey.setCreatedTime(System.currentTimeMillis());
        SqlDatabaseManager.putKey(physicalKey);
        SqlDatabaseManager.KeyClaimResult physicalClaim = SqlDatabaseManager.claimKey(
                "EVIP-PHYSICAL", firstPlayer, "machine-1", true, "physical-1", System.currentTimeMillis(), 10_000L);
        assertEquals(SqlDatabaseManager.KeyClaimStatus.CLAIMED, physicalClaim.status());
        assertTrue(SqlDatabaseManager.completeKeyClaim(physicalClaim.claimId(), firstPlayer, true, System.currentTimeMillis()));
        assertEquals(SqlDatabaseManager.KeyClaimStatus.ALREADY_USED,
                SqlDatabaseManager.claimKey("EVIP-PHYSICAL", UUID.randomUUID(), "machine-1", true,
                        "key-replay", System.currentTimeMillis(), 10_000L).status());
    }

    @Test
    void packageClaimUniquenessAndVipCasAreDatabaseDecisions() throws Exception {
        UUID player = UUID.randomUUID();
        List<SqlDatabaseManager.PackageClaimResult> claims = race(2, (start, index) -> {
            start.await();
            return SqlDatabaseManager.claimPackage(player, "monthly", false, 0,
                    "package-race-" + index, System.currentTimeMillis(), 10_000L);
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

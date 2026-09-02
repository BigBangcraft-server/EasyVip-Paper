package br.com.pedrodalben.easyvip.persistence;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PersistenceLifecycleTest {
    @TempDir
    Path tempDir;

    @Test
    void jsonPersistenceCanRestartAfterCleanShutdown() {
        boolean originalSql = EasyVipConfig.integrations.sqlEnabled;
        EasyVipConfig.integrations.sqlEnabled = false;
        try {
            PersistenceManager.initialize(tempDir);
            PersistenceManager.shutdown();
            PersistenceManager.initialize(tempDir);
            assertNotNull(PersistenceManager.getPackageUsage(UUID.randomUUID()));
        } finally {
            PersistenceManager.shutdown();
            EasyVipConfig.integrations.sqlEnabled = originalSql;
        }
    }

    @Test
    void sqlPersistenceCanRestartAfterCleanShutdown() {
        boolean originalSql = EasyVipConfig.integrations.sqlEnabled;
        String originalUrl = EasyVipConfig.integrations.sqlUrl;
        String originalUsername = EasyVipConfig.integrations.sqlUsername;
        String originalPassword = EasyVipConfig.integrations.sqlPassword;
        EasyVipConfig.integrations.sqlEnabled = true;
        EasyVipConfig.integrations.sqlUrl = "jdbc:h2:mem:lifecycle_"
                + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        EasyVipConfig.integrations.sqlUsername = "";
        EasyVipConfig.integrations.sqlPassword = "";
        try {
            PersistenceManager.initialize(tempDir);
            PersistenceManager.shutdown();
            PersistenceManager.initialize(tempDir);
            assertNotNull(PersistenceManager.getPackageUsage(UUID.randomUUID()));
        } finally {
            PersistenceManager.shutdown();
            EasyVipConfig.integrations.sqlEnabled = originalSql;
            EasyVipConfig.integrations.sqlUrl = originalUrl;
            EasyVipConfig.integrations.sqlUsername = originalUsername;
            EasyVipConfig.integrations.sqlPassword = originalPassword;
        }
    }

    @Test
    void asynchronousPersistenceRunsOffCallerThread() throws Exception {
        boolean originalSql = EasyVipConfig.integrations.sqlEnabled;
        EasyVipConfig.integrations.sqlEnabled = false;
        try {
            PersistenceManager.initialize(tempDir);
            String caller = Thread.currentThread().getName();
            String worker = PersistenceManager.executeAsync(() -> Thread.currentThread().getName())
                    .get(5, TimeUnit.SECONDS);
            assertNotEquals(caller, worker);
            assertNull(PersistenceManager.getPlayerVipsAsync(UUID.randomUUID())
                    .get(5, TimeUnit.SECONDS));
        } finally {
            PersistenceManager.shutdown();
            EasyVipConfig.integrations.sqlEnabled = originalSql;
        }
    }
}

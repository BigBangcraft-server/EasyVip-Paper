package br.com.pedrodalben.easyvip.persistence;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

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
}

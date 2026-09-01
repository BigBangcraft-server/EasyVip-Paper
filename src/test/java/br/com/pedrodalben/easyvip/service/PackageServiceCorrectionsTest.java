package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.PendingVariantSelection;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PackageServiceCorrectionsTest {

    @TempDir
    Path tempDir;

    private boolean originalNotifyPendingVariantOnLogin;
    private int originalVariantSelectionTimeoutSeconds;
    private Map<String, EasyVipConfig.PackageDefinition> packageBackup;

    @BeforeEach
    void setUp() {
        PersistenceManager.initialize(tempDir);

        originalNotifyPendingVariantOnLogin = EasyVipConfig.common.notifyPendingVariantOnLogin;
        originalVariantSelectionTimeoutSeconds = EasyVipConfig.common.variantSelectionTimeoutSeconds;
        packageBackup = new LinkedHashMap<>(EasyVipConfig.packages.list);

        EasyVipConfig.common.notifyPendingVariantOnLogin = true;
        EasyVipConfig.packages.list.clear();
    }

    @AfterEach
    void tearDown() {
        PersistenceManager.flush();
        EasyVipConfig.common.notifyPendingVariantOnLogin = originalNotifyPendingVariantOnLogin;
        EasyVipConfig.common.variantSelectionTimeoutSeconds = originalVariantSelectionTimeoutSeconds;
        EasyVipConfig.packages.list.clear();
        EasyVipConfig.packages.list.putAll(packageBackup);
        PersistenceManager.flush();
    }

    @Test
    void expiredPendingVariantIsCleanedByService() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.common.variantSelectionTimeoutSeconds = 30;
        PendingVariantSelection selection = new PendingVariantSelection(uuid, "starter", List.of("a", "b"));
        selection.setTimestamp(System.currentTimeMillis() - 90_000L);
        PersistenceManager.addPendingVariant(uuid, selection);

        int removed = PackageService.cleanupExpiredPendingVariants(uuid);

        assertEquals(1, removed);
        assertTrue(PersistenceManager.getPendingVariants(uuid).isEmpty());
    }

    @Test
    void notifyPendingVariantRespectsConfigFlag() {
        UUID uuid = UUID.randomUUID();
        PendingVariantSelection selection = new PendingVariantSelection(uuid, "starter", List.of("a", "b"));
        PersistenceManager.addPendingVariant(uuid, selection);
        EasyVipConfig.common.notifyPendingVariantOnLogin = false;

        PackageService.notifyPendingVariantsOnLogin(uuid, "Pedro", component -> fail("should not notify"));

        assertFalse(PersistenceManager.getPendingVariants(uuid).isEmpty());
    }

}

package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionContext;
import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.PlayerVipRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRegistry;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mockStatic;

class VipServiceSqlExpirationTest {
    @TempDir
    Path tempDir;

    private boolean originalSql;
    private java.util.Map<String, EasyVipConfig.VipTierDefinition> originalTiers;

    @BeforeEach
    void setUp() {
        originalSql = EasyVipConfig.integrations.sqlEnabled;
        originalTiers = new LinkedHashMap<>(EasyVipConfig.tiers.list);
        EasyVipConfig.integrations.sqlEnabled = true;
        EasyVipConfig.integrations.sqlUrl = "jdbc:h2:mem:expiration_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        EasyVipConfig.integrations.sqlUsername = "";
        EasyVipConfig.integrations.sqlPassword = "";
        EasyVipConfig.integrations.sqlPoolSize = 4;
        EasyVipConfig.integrations.sqlMinimumIdle = 1;
        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.VipTierDefinition tier = new EasyVipConfig.VipTierDefinition();
        tier.id = "expired";
        tier.displayName = "Expired";
        tier.actionsOnExpire = new ArrayList<>();
        tier.actionsOnUnsetActive = new ArrayList<>();
        EasyVipConfig.tiers.list.put(tier.id, tier);
        PersistenceManager.initialize(tempDir);
    }

    @AfterEach
    void tearDown() {
        PersistenceManager.shutdown();
        EasyVipConfig.integrations.sqlEnabled = originalSql;
        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.tiers.list.putAll(originalTiers);
    }

    @Test
    void expirationDeliveryCompletesBeforeGrantTransition() throws Exception {
        UUID player = UUID.randomUUID();
        PlayerVipRegistry registry = new PlayerVipRegistry(player);
        registry.setPlayerName("Pedro");
        registry.getVips().put("expired", new PlayerVipRecord("expired", 1L,
                System.currentTimeMillis() - 1_000L, true, false));
        SqlDatabaseManager.updatePlayerVips(player, registry);

        try (MockedStatic<ActionExecutor> mocked = mockStatic(ActionExecutor.class)) {
            mocked.when(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap())).thenReturn(true);

            assertEquals(1, VipService.expireDueVipsForTest(player, "Pedro"));
            assertEquals(0, VipService.expireDueVipsForTest(player, "Pedro"));
        }

        assertTrue(Boolean.TRUE.equals(SqlDatabaseManager.withConnection(conn -> {
            try (Statement statement = conn.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT status FROM easyvip_deliveries WHERE benefit_id = 'vip-expiration:expired'")) {
                return result.next() && "DELIVERED".equals(result.getString(1));
            }
        })));
    }
}

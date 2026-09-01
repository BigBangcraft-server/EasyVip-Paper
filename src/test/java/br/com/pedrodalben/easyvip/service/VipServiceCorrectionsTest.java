package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionContext;
import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.PlayerVipRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRegistry;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class VipServiceCorrectionsTest {

    @TempDir
    Path tempDir;

    private Map<String, EasyVipConfig.VipTierDefinition> tierBackup;
    private boolean originalForceHighest;

    @BeforeEach
    void setUp() {
        PersistenceManager.initialize(tempDir);

        tierBackup = new LinkedHashMap<>(EasyVipConfig.tiers.list);
        originalForceHighest = EasyVipConfig.common.forceHighestPriorityAsActive;

        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.common.forceHighestPriorityAsActive = true;
    }

    @AfterEach
    void tearDown() {
        PersistenceManager.flush();
        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.tiers.list.putAll(tierBackup);
        EasyVipConfig.common.forceHighestPriorityAsActive = originalForceHighest;
        PersistenceManager.flush();
    }

    @Test
    void offlineExpiredVipRunsExpireAndUnsetActions() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.VipTierDefinition tier = tier("vip", 10,
                List.of(action("run_server_command", Map.of("command", "ftbranks remove {player} vip-unset"))),
                List.of(action("run_server_command", Map.of("command", "ftbranks remove {player} vip-expire"))),
                List.of());
        EasyVipConfig.tiers.list.put(tier.id, tier);

        PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
        registry.setPlayerName("Pedro");
        registry.getVips().put("vip", new PlayerVipRecord("vip", System.currentTimeMillis() - 10_000L, System.currentTimeMillis() - 1_000L, true, false));
        PersistenceManager.updatePlayerVips(uuid, registry);

        AtomicInteger executions = new AtomicInteger();
        try (MockedStatic<ActionExecutor> mocked = mockStatic(ActionExecutor.class)) {
            mocked.when(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap()))
                    .thenAnswer(invocation -> {
                        executions.incrementAndGet();
                        return true;
                    });

            int expired = VipService.expireDueVipsForTest(uuid, "Pedro");

            assertEquals(1, expired);
            assertTrue(PersistenceManager.getPlayerVips(uuid).getVips().isEmpty());
            assertEquals(2, executions.get());
        }
    }

    @Test
    void expiredVipSelectsAnotherValidActiveVip() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.VipTierDefinition low = tier("low", 1, List.of(), List.of(), List.of());
        EasyVipConfig.VipTierDefinition high = tier("high", 10,
                List.of(action("run_server_command", Map.of("command", "ftbranks set {player} high"))),
                List.of(), List.of());
        EasyVipConfig.tiers.list.put(low.id, low);
        EasyVipConfig.tiers.list.put(high.id, high);

        PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
        registry.setPlayerName("Pedro");
        registry.getVips().put("low", new PlayerVipRecord("low", System.currentTimeMillis() - 20_000L, System.currentTimeMillis() - 1_000L, true, false));
        registry.getVips().put("high", new PlayerVipRecord("high", System.currentTimeMillis(), -1L, false, false));
        PersistenceManager.updatePlayerVips(uuid, registry);

        try (MockedStatic<ActionExecutor> mocked = mockStatic(ActionExecutor.class)) {
            mocked.when(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap())).thenReturn(true);

            int expired = VipService.expireDueVipsForTest(uuid, "Pedro");

            assertEquals(1, expired);
            PlayerVipRegistry updated = PersistenceManager.getPlayerVips(uuid);
            assertFalse(updated.getVips().containsKey("low"));
            assertTrue(updated.getVips().get("high").isActive());
        }
    }

    @Test
    void offlineActiveVipSyncsOnlineOnLogin() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.VipTierDefinition tier = tier("vip_plus", 10, List.of(), List.of(), List.of());
        tier.actionsOnSetActive = List.of(action("add_ftb_rank", Map.of("rank", "vip_plus")));
        EasyVipConfig.tiers.list.put(tier.id, tier);

        PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
        registry.setPlayerName("PedropsRei");
        registry.getVips().put("vip_plus", new PlayerVipRecord("vip_plus", System.currentTimeMillis(), -1L, true, false));
        registry.setLastObservedActiveVip(null);
        PersistenceManager.updatePlayerVips(uuid, registry);

        try (MockedStatic<ActionExecutor> mockedExecutor = mockStatic(ActionExecutor.class)) {
            mockedExecutor.when(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap())).thenReturn(true);

            // 1. Evaluate while offline
            VipService.evaluateActiveVip(uuid, registry);

            // lastObserved should still be null because player was offline
            assertNull(registry.getLastObservedActiveVip());
            // Action should have been executed
            mockedExecutor.verify(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap()), times(1));

            // Clear mock invocations to verify the next step
            mockedExecutor.clearInvocations();

            // 2. Evaluate again while offline - should run again because it hasn't been observed online yet
            VipService.evaluateActiveVip(uuid, registry);
            assertNull(registry.getLastObservedActiveVip());
            mockedExecutor.verify(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap()), times(1));

            mockedExecutor.clearInvocations();

            // 3. Simulate online login by setting lastObservedActiveVip (which the code would do when online)
            registry.setLastObservedActiveVip("vip_plus");

            // 4. Evaluate again - should NOT run because desired matches lastObserved
            VipService.evaluateActiveVip(uuid, registry);
            assertEquals("vip_plus", registry.getLastObservedActiveVip());
            mockedExecutor.verify(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap()), never());
        }
    }

    @Test
    void fakePlayerVipUsesBigBangFakePlayersIdentityAndActivatesImmediately() {
        EasyVipConfig.VipTierDefinition tier = tier("vip_fake", 10,
                List.of(action("run_server_command", Map.of("command", "lp user {player} parent add vip_fake"))),
                List.of(), List.of());
        EasyVipConfig.tiers.list.put(tier.id, tier);

        try (MockedStatic<ActionExecutor> mocked = mockStatic(ActionExecutor.class)) {
            mocked.when(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap())).thenReturn(true);

            assertTrue(VipService.addFakePlayerVip("LunaCraft", "vip_fake", "30d", "Console"));

            UUID uuid = UUID.nameUUIDFromBytes("bigbang-fake-player:lunacraft".getBytes(StandardCharsets.UTF_8));
            PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
            assertNotNull(registry);
            assertEquals("LunaCraft", registry.getPlayerName());
            assertFalse(registry.getVips().get("vip_fake").isPendingActivateActions());
            assertEquals("vip_fake", registry.getLastObservedActiveVip());
            mocked.verify(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap()), atLeastOnce());
        }
    }

    @Test
    void fakePlayerVipExpiresUsingItsConfiguredName() {
        UUID uuid = UUID.nameUUIDFromBytes("bigbang-fake-player:lunacraft".getBytes(StandardCharsets.UTF_8));
        EasyVipConfig.VipTierDefinition tier = tier("vip_fake", 10, List.of(),
                List.of(action("run_server_command", Map.of("command", "lp user {player} parent remove vip_fake"))), List.of());
        EasyVipConfig.tiers.list.put(tier.id, tier);
        PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
        registry.setPlayerName("LunaCraft");
        registry.getVips().put(tier.id, new PlayerVipRecord(tier.id, System.currentTimeMillis() - 2_000L,
                System.currentTimeMillis() - 1_000L, true, false));
        PersistenceManager.updatePlayerVips(uuid, registry);

        AtomicReference<String> actionPlayerName = new AtomicReference<>();
        try (MockedStatic<ActionExecutor> mocked = mockStatic(ActionExecutor.class)) {
            mocked.when(() -> ActionExecutor.execute(any(ActionContext.class), anyList(), anyMap()))
                    .thenAnswer(invocation -> {
                        actionPlayerName.set(invocation.getArgument(0, ActionContext.class).getPlayerName());
                        return true;
                    });

            assertEquals(1, VipService.expireDueVipsForTest(uuid, "LunaCraft"));
            assertEquals("LunaCraft", actionPlayerName.get());
        }
    }


    private EasyVipConfig.VipTierDefinition tier(String id, int priority, List<Map<String, Object>> activate, List<Map<String, Object>> expire, List<Map<String, Object>> unset) {
        EasyVipConfig.VipTierDefinition tier = new EasyVipConfig.VipTierDefinition();
        tier.id = id;
        tier.displayName = id.toUpperCase();
        tier.priority = priority;
        tier.actionsOnActivate = new ArrayList<>(activate);
        tier.actionsOnExpire = new ArrayList<>(expire);
        tier.actionsOnUnsetActive = new ArrayList<>(unset);
        tier.actionsOnSetActive = new ArrayList<>();
        return tier;
    }

    private Map<String, Object> action(String type, Map<String, Object> extras) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", type);
        action.putAll(extras);
        return action;
    }
}

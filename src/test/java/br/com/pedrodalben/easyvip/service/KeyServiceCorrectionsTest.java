package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
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

class KeyServiceCorrectionsTest {

    @TempDir
    Path tempDir;

    private String originalKeyCharset;
    private int originalKeyLength;
    private String originalKeyPrefix;
    private boolean originalCaseSensitive;
    private boolean originalConfirmBeforeUse;
    private int originalCommandCooldownTicks;
    private List<String> originalAllowedDimensions;
    private List<String> originalDenyDimensions;
    private String originalItemKeyItemId;
    private String originalItemKeyMarker;
    private Map<String, EasyVipConfig.RewardKeyDefinition> rewardBackup;
    private Map<String, EasyVipConfig.VipTierDefinition> tierBackup;

    @BeforeEach
    void setUp() {
        PersistenceManager.initialize(tempDir);

        originalKeyCharset = EasyVipConfig.common.keyCharset;
        originalKeyLength = EasyVipConfig.common.keyLength;
        originalKeyPrefix = EasyVipConfig.common.keyPrefix;
        originalCaseSensitive = EasyVipConfig.common.caseSensitiveKeys;
        originalConfirmBeforeUse = EasyVipConfig.common.confirmBeforeUse;
        originalCommandCooldownTicks = EasyVipConfig.common.commandCooldownTicks;
        originalAllowedDimensions = new ArrayList<>(EasyVipConfig.common.allowedDimensions);
        originalDenyDimensions = new ArrayList<>(EasyVipConfig.common.denyDimensions);
        originalItemKeyItemId = EasyVipConfig.common.itemKeyItemId;
        originalItemKeyMarker = EasyVipConfig.common.itemKeyMarker;

        rewardBackup = new LinkedHashMap<>(EasyVipConfig.rewardKeys.list);
        tierBackup = new LinkedHashMap<>(EasyVipConfig.tiers.list);

        EasyVipConfig.common.keyCharset = "ABCDEF0123456789";
        EasyVipConfig.common.keyLength = 6;
        EasyVipConfig.common.keyPrefix = "EVIP-";
        EasyVipConfig.common.caseSensitiveKeys = false;
        EasyVipConfig.common.confirmBeforeUse = false;
        EasyVipConfig.common.commandCooldownTicks = 20;
        EasyVipConfig.common.allowedDimensions = new ArrayList<>();
        EasyVipConfig.common.denyDimensions = new ArrayList<>();
        EasyVipConfig.common.itemKeyItemId = "minecraft:tripwire_hook";
        EasyVipConfig.common.itemKeyMarker = "easyvip_item_key";

        EasyVipConfig.rewardKeys.list.clear();
        EasyVipConfig.tiers.list.clear();
    }

    @AfterEach
    void tearDown() {
        PersistenceManager.flush();
        EasyVipConfig.common.keyCharset = originalKeyCharset;
        EasyVipConfig.common.keyLength = originalKeyLength;
        EasyVipConfig.common.keyPrefix = originalKeyPrefix;
        EasyVipConfig.common.caseSensitiveKeys = originalCaseSensitive;
        EasyVipConfig.common.confirmBeforeUse = originalConfirmBeforeUse;
        EasyVipConfig.common.commandCooldownTicks = originalCommandCooldownTicks;
        EasyVipConfig.common.allowedDimensions = new ArrayList<>(originalAllowedDimensions);
        EasyVipConfig.common.denyDimensions = new ArrayList<>(originalDenyDimensions);
        EasyVipConfig.common.itemKeyItemId = originalItemKeyItemId;
        EasyVipConfig.common.itemKeyMarker = originalItemKeyMarker;

        EasyVipConfig.rewardKeys.list.clear();
        EasyVipConfig.rewardKeys.list.putAll(rewardBackup);
        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.tiers.list.putAll(tierBackup);
        PersistenceManager.flush();
    }

    @Test
    void rewardKeyMissingDefinitionDoesNotConsumeKey() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-ABC123");
        record.setType("reward");
        record.setRewardKeyId("missing");
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.ERROR, KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(0, PersistenceManager.getKey(record.getCode()).getUsedCount());
    }

    @Test
    void rewardKeyWithoutActionsDoesNotConsumeKey() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = "coins";
        def.displayName = "Coins";
        def.actions = new ArrayList<>();
        EasyVipConfig.rewardKeys.list.put(def.id, def);

        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-ABC124");
        record.setType("reward");
        record.setRewardKeyId(def.id);
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.ERROR, KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(0, PersistenceManager.getKey(record.getCode()).getUsedCount());
    }

    @Test
    void rewardKeyValidConsumesKey() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = "coins";
        def.displayName = "Coins";
        def.consumeOnUse = true;
        def.actions = List.of(Map.of(
                "type", "send_message",
                "message", "ok"
        ));
        EasyVipConfig.rewardKeys.list.put(def.id, def);

        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-ABC125");
        record.setType("reward");
        record.setRewardKeyId(def.id);
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS, KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(1, PersistenceManager.getKey(record.getCode()).getUsedCount());
    }

    @Test
    void commandCooldownBlocksRepeatedUse() {
        EasyVipConfig.common.commandCooldownTicks = 20;

        UUID uuid = UUID.randomUUID();
        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = "coins";
        def.displayName = "Coins";
        def.consumeOnUse = false;
        def.actions = List.of(Map.of(
                "type", "send_message",
                "message", "ok"
        ));
        EasyVipConfig.rewardKeys.list.put(def.id, def);

        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-ABC126");
        record.setType("reward");
        record.setRewardKeyId(def.id);
        record.setMaxUses(10);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS, KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(KeyService.RedeemResult.ON_COOLDOWN, KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
    }

    @Test
    void denyDimensionsBlockKeyUse() {
        EasyVipConfig.common.denyDimensions = List.of("minecraft:overworld");

        UUID uuid = UUID.randomUUID();
        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = "coins";
        def.displayName = "Coins";
        def.actions = List.of(Map.of("type", "send_message", "message", "ok"));
        EasyVipConfig.rewardKeys.list.put(def.id, def);

        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-ABC127");
        record.setType("reward");
        record.setRewardKeyId(def.id);
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.ERROR, KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(0, PersistenceManager.getKey(record.getCode()).getUsedCount());
    }

    @Test
    void customKeyValidConsumesKey() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-CUSTOM1");
        record.setType("custom");
        record.setActions(List.of(Map.of(
                "type", "send_message",
                "message", "hello custom"
        )));
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS, KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(1, PersistenceManager.getKey(record.getCode()).getUsedCount());
    }

    @Test
    void customKeyEmptyActionsDoesNotConsume() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-CUSTOM2");
        record.setType("custom");
        record.setActions(new ArrayList<>());
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.ERROR, KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(0, PersistenceManager.getKey(record.getCode()).getUsedCount());
    }

    @Test
    void physicalItemValidationRequiresMarker() {
        assertTrue(KeyService.isPhysicalKeyPayloadValid("minecraft:tripwire_hook", "minecraft:tripwire_hook", true, true));
        assertFalse(KeyService.isPhysicalKeyPayloadValid("minecraft:tripwire_hook", "minecraft:tripwire_hook", false, true));
        assertFalse(KeyService.isPhysicalKeyPayloadValid("minecraft:tripwire_hook", "minecraft:stone", true, true));
    }
}

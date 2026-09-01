package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.util.CommandAllowlist;
import br.com.pedrodalben.easyvip.util.KeySecurity;
import br.com.pedrodalben.easyvip.util.UniqueCodeGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KeyModuleSecurityTest {

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

    // ─── 1. Key Generation Security ──────────────────────────

    @Test
    void uniqueCodeGeneratorRejectsEmptyCharset() {
        assertThrows(IllegalArgumentException.class, () ->
                UniqueCodeGenerator.generateCandidate("", 4, "VIP-"));
        assertThrows(IllegalArgumentException.class, () ->
                UniqueCodeGenerator.generateCandidate(null, 4, "VIP-"));
    }

    @Test
    void uniqueCodeGeneratorRejectsSingleCharCharset() {
        assertThrows(IllegalArgumentException.class, () ->
                UniqueCodeGenerator.generateCandidate("A", 4, "VIP-"));
        assertThrows(IllegalArgumentException.class, () ->
                UniqueCodeGenerator.generateCandidate("AAAA", 4, "VIP-"));
    }

    @Test
    void uniqueCodeGeneratorRejectsZeroLength() {
        assertThrows(IllegalArgumentException.class, () ->
                UniqueCodeGenerator.generateCandidate("AB", 0, "VIP-"));
    }

    @Test
    void generatedCodesRespectPrefixAndLength() {
        String code = UniqueCodeGenerator.generateCandidate("ABC", 5, "VIP-");
        assertTrue(code.startsWith("VIP-"));
        assertEquals(9, code.length());

        String noPrefix = UniqueCodeGenerator.generateCandidate("AB", 8, "");
        assertEquals(8, noPrefix.length());
    }

    @Test
    void generatedCodesUseProvidedCharset() {
        String code = UniqueCodeGenerator.generateCandidate("AB", 20, "");
        for (char c : code.toCharArray()) {
            assertTrue(c == 'A' || c == 'B', "Code contains char outside charset: " + c);
        }
    }

    @Test
    void generateDoesNotProduceDuplicatesWithinAttempts() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = UniqueCodeGenerator.generateCandidate("ABCDEFGHIJKLMNPQRSTUVWXYZ123456789", 8, "");
            assertTrue(codes.add(code), "Duplicate code generated: " + code);
        }
    }

    // ─── 2. Key Normalization ────────────────────────────────

    @Test
    void keyIsTrimmedWhenCaseInsensitive() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = "coins";
        def.displayName = "Coins";
        def.consumeOnUse = true;
        def.actions = List.of(Map.of("type", "send_message", "message", "ok"));
        EasyVipConfig.rewardKeys.list.put(def.id, def);

        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-ABCDEF");
        record.setType("reward");
        record.setRewardKeyId(def.id);
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
    }

    // ─── 3. Expired Key ──────────────────────────────────────

    @Test
    void expiredKeyIsRejected() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-EXPIRED");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(1);
        record.setExpiryTime(System.currentTimeMillis() - 60000);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.EXPIRED,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
    }

    @Test
    void nonExpiredKeyIsAccepted() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-NOTEXPIRED");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(1);
        record.setExpiryTime(System.currentTimeMillis() + 3600000);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
    }

    @Test
    void noExpiryKeyIsAccepted() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-NOEXPIRY");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(1);
        record.setExpiryTime(-1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
    }

    // ─── 4. No Uses Left ─────────────────────────────────────

    @Test
    void fullyUsedKeyIsRejected() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-FULL");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(1);
        record.setUsedCount(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.NO_USES_LEFT,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
    }

    // ─── 5. UUID-Bound Key ───────────────────────────────────

    @Test
    void boundKeyAcceptedByCorrectPlayer() {
        UUID playerUuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-BOUND");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(1);
        record.setBoundPlayerUuid(playerUuid);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS,
                KeyService.redeemRewardKeyForTest(record, playerUuid, "Pedro", "minecraft:overworld", true, actions -> true));
    }

    @Test
    void boundKeyRejectedByWrongPlayer() {
        UUID playerUuid = UUID.randomUUID();
        UUID wrongUuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-BOUND2");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(1);
        record.setBoundPlayerUuid(playerUuid);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.BOUND_TO_OTHER,
                KeyService.redeemRewardKeyForTest(record, wrongUuid, "Wrong", "minecraft:overworld", true, actions -> true));
    }

    // ─── 6. Already Used ─────────────────────────────────────

    @Test
    void alreadyUsedKeyIsRejected() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-USED");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(2);
        record.getUsedBy().add(uuid);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.ALREADY_USED,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
    }

    // ─── 7. Reward consumeOnUse ──────────────────────────────

    @Test
    void rewardNoConsumeDoesNotIncrementUsedCount() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = "coins_noconsume";
        def.displayName = "Coins";
        def.consumeOnUse = false;
        def.actions = List.of(Map.of("type", "send_message", "message", "ok"));
        EasyVipConfig.rewardKeys.list.put(def.id, def);

        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-NOCONSUME");
        record.setType("reward");
        record.setRewardKeyId(def.id);
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(0, PersistenceManager.getKey(record.getCode()).getUsedCount());
        assertTrue(PersistenceManager.getKey(record.getCode()).getLastUsedAtBy().containsKey(uuid));
    }

    @Test
    void rewardConsumeOnUseIncrementsUsedCount() {
        UUID uuid = UUID.randomUUID();
        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = "coins_consume";
        def.displayName = "Coins";
        def.consumeOnUse = true;
        def.actions = List.of(Map.of("type", "send_message", "message", "ok"));
        EasyVipConfig.rewardKeys.list.put(def.id, def);

        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-CONSUME");
        record.setType("reward");
        record.setRewardKeyId(def.id);
        record.setMaxUses(5);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.SUCCESS,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> true));
        assertEquals(1, PersistenceManager.getKey(record.getCode()).getUsedCount());
    }

    // ─── 8. Action Failure Does Not Consume Key ──────────────

    @Test
    void customKeyActionFailureDoesNotConsume() {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-FAIL");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        assertEquals(KeyService.RedeemResult.ERROR,
                KeyService.redeemRewardKeyForTest(record, uuid, "Pedro", "minecraft:overworld", true, actions -> false));
        assertEquals(0, PersistenceManager.getKey(record.getCode()).getUsedCount());
    }

    // ─── 9. Concurrent Redemption ────────────────────────────

    @Test
    void concurrentRedeemOfSameKeyOnlyConsumesOnce() throws Exception {
        UUID uuid = UUID.randomUUID();
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-CONCURRENT");
        record.setType("custom");
        record.setActions(List.of(Map.of("type", "send_message", "message", "ok")));
        record.setMaxUses(1);
        PersistenceManager.putKey(record);

        int threadCount = 4;
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threadCount);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        KeyRecord localRecord = PersistenceManager.getKey(record.getCode());
                        if (localRecord != null) {
                            KeyService.RedeemResult result = KeyService.redeemRewardKeyForTest(
                                    localRecord, uuid, "Pedro", "minecraft:overworld", false, actions -> true);
                            if (result == KeyService.RedeemResult.SUCCESS) {
                                successCount.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(5, TimeUnit.SECONDS);
        }

        assertEquals(1, successCount.get(), "Only one thread should succeed in redeeming the key");
    }

    // ─── 10. Key Security / Masking ─────────────────────────

    @Test
    void maskKeyHidesFullCode() {
        String masked = KeySecurity.maskKey("EVIP-ABCDEFGHIJ");
        assertFalse(masked.contains("ABCDEFGHIJ"), "Masked key should not contain full code");
        assertTrue(masked.startsWith("EVIP"));
    }

    @Test
    void maskKeyShortCode() {
        String masked = KeySecurity.maskKey("AB");
        assertNotNull(masked);
        assertFalse(masked.equals("AB"), "Short codes should still be masked");
    }

    @Test
    void maskKeyNull() {
        assertEquals("***", KeySecurity.maskKey(null));
    }

    @Test
    void maskKeyEmpty() {
        assertEquals("***", KeySecurity.maskKey(""));
    }

    @Test
    void fingerprintKeyProducesHexString() {
        String fp = KeySecurity.fingerprintKey("EVIP-TEST123");
        assertNotNull(fp);
        assertTrue(fp.matches("sha256:[0-9a-f]{16}"));
    }

    @Test
    void fingerprintKeyIsDeterministic() {
        assertEquals(KeySecurity.fingerprintKey("TEST"),
                KeySecurity.fingerprintKey("TEST"));
    }

    @Test
    void describeKeyForLogContainsMaskAndFingerprint() {
        String desc = KeySecurity.describeKeyForLog("EVIP-TEST123");
        assertTrue(desc.contains("[fp:"));
        assertFalse(desc.contains("TEST123"), "describeKeyForLog should not expose full code: " + desc);
    }

    @Test
    void sanitizeAuditDetailsMasksKeyCode() {
        String sanitized = KeySecurity.sanitizeAuditDetails("Redeemed key EVIP-ABCD1234 successfully");
        assertTrue(sanitized.contains("***MASKED***"));
        assertFalse(sanitized.contains("ABCD1234"));
    }

    @Test
    void sanitizeAuditDetailsPreservesNonKeyText() {
        String input = "Player Pedro redeemed reward successfully";
        String sanitized = KeySecurity.sanitizeAuditDetails(input);
        assertEquals(input, sanitized);
    }

    @Test
    void auditLogSanitizesRawKeyMaterial() {
        PersistenceManager.log("Console", "test", "activation_key=EVIP-SECRET1234 code=EVIP-SECRET1234");
        String details = PersistenceManager.getAuditLogs().get(PersistenceManager.getAuditLogs().size() - 1).getDetails();
        assertFalse(details.contains("SECRET1234"));
        assertTrue(details.contains("***MASKED***"));
    }

    // ─── 11. Command Allowlist ───────────────────────────────

    @Test
    void rejectsNullCommand() {
        assertFalse(CommandAllowlist.isAllowed(null, true, List.of("give ")));
    }

    @Test
    void rejectsEmptyCommand() {
        assertFalse(CommandAllowlist.isAllowed("", true, List.of("give ")));
    }

    @Test
    void rejectsCommandWithSeparators() {
        assertFalse(CommandAllowlist.isAllowed("give @p diamond; op @p", true, List.of("give ")));
        assertFalse(CommandAllowlist.isAllowed("give @p diamond&op @p", true, List.of("give ")));
        assertFalse(CommandAllowlist.isAllowed("give @p diamond|op @p", true, List.of("give ")));
    }

    @Test
    void rejectsCommandWithNewlines() {
        assertFalse(CommandAllowlist.isAllowed("give @p diamond\nop @p", true, List.of("give ")));
        assertFalse(CommandAllowlist.isAllowed("give @p diamond\r\nop @p", true, List.of("give ")));
    }

    @Test
    void allowsPrefixWithExactMatch() {
        assertTrue(CommandAllowlist.isAllowed("give", true, List.of("give")));
    }

    @Test
    void normalizesLeadingSlash() {
        assertTrue(CommandAllowlist.isAllowed("/give @p diamond", true, List.of("give")));
    }

    @Test
    void normalizesExtraWhitespace() {
        assertTrue(CommandAllowlist.isAllowed("  give    @p   diamond  ", true, List.of("give")));
        assertTrue(CommandAllowlist.isAllowed(" /give    @p   diamond  ", true, List.of("give")));
    }

    // ─── 12. KeyRecord Copy ──────────────────────────────────

    @Test
    void keyRecordCopyIsDeepForCollections() {
        KeyRecord original = new KeyRecord();
        original.setCode("EVIP-COPYTEST");
        original.setType("vip");
        original.getUsedBy().add(UUID.randomUUID());
        original.getConsumedInstances().add("instance-123");

        KeyRecord copy = original.copy();

        copy.getUsedBy().add(UUID.randomUUID());
        assertEquals(1, original.getUsedBy().size(), "Original usedBy should not be affected");

        copy.getConsumedInstances().add("instance-456");
        assertEquals(1, original.getConsumedInstances().size(), "Original consumedInstances should not be affected");
    }

    @Test
    void keyRecordPhysicalKeyInstanceTracking() {
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-PHYSICAL");

        assertFalse(record.isInstanceConsumed("instance-001"));
        record.markInstanceConsumed("instance-001");
        assertTrue(record.isInstanceConsumed("instance-001"));
        assertFalse(record.isInstanceConsumed("instance-002"));
    }

    // ─── 13. PersistenceManager Defensive Copies ─────────────

    @Test
    void getKeyReturnsCopyNotOriginalReference() {
        KeyRecord original = new KeyRecord();
        original.setCode("EVIP-DEFTST");
        original.setType("vip");
        original.setMaxUses(5);
        PersistenceManager.putKey(original);

        KeyRecord retrieved = PersistenceManager.getKey("EVIP-DEFTST");
        assertNotNull(retrieved);
        retrieved.setMaxUses(99);

        KeyRecord retrievedAgain = PersistenceManager.getKey("EVIP-DEFTST");
        assertEquals(5, retrievedAgain.getMaxUses(),
                "getKey copy should not mutate cached record");
    }

    @Test
    void getAllKeysReturnsDefensiveCopies() {
        KeyRecord record = new KeyRecord();
        record.setCode("EVIP-ALLCPY");
        record.setType("vip");
        record.setMaxUses(3);
        PersistenceManager.putKey(record);

        List<KeyRecord> all = PersistenceManager.getAllKeys();
        assertFalse(all.isEmpty());
        KeyRecord first = all.get(0);
        first.setMaxUses(99);

        KeyRecord fromCache = PersistenceManager.getKey("EVIP-ALLCPY");
        assertEquals(3, fromCache.getMaxUses(),
                "getAllKeys copy should not mutate cached record");
    }

    // ─── 14. Unique Code Collision Simulation ────────────────

    @Test
    void generateRetriesOnCollision() {
        AtomicInteger attempts = new AtomicInteger(0);
        String code = UniqueCodeGenerator.generate("AB", 2, "X-", candidate -> {
            attempts.incrementAndGet();
            return attempts.get() >= 4;
        }, 10);

        assertTrue(code.startsWith("X-"));
        assertTrue(attempts.get() >= 4);
    }

    @Test
    void generateFailsAfterExhaustingMaxAttempts() {
        assertThrows(IllegalStateException.class, () ->
                UniqueCodeGenerator.generate("AB", 2, "", candidate -> false, 3));
    }

    // ─── 15. Key Generation Validation ───────────────────────

    @Test
    void generateVipKeyRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateVipKey("vip", "30d", 0, null, -1, null));
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateVipKey("vip", "30d", -1, null, -1, null));
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateVipKey(null, "30d", 1, null, -1, null));
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateVipKey("", "30d", 1, null, -1, null));
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateVipKey("vip", "invalid", 1, null, -1, null));
    }

    @Test
    void generateVipKeyProducesValidCode() {
        EasyVipConfig.VipTierDefinition tierDef = new EasyVipConfig.VipTierDefinition();
        tierDef.id = "vip";
        tierDef.displayName = "VIP";
        EasyVipConfig.tiers.list.put("vip", tierDef);

        KeyRecord record = KeyService.generateVipKey("vip", "30d", 1, null, -1, null);
        assertNotNull(record.getCode());
        assertTrue(record.getCode().startsWith("EVIP-"));
        assertEquals("vip", record.getType());
        assertEquals(1, record.getMaxUses());
    }

    @Test
    void generateVipKeyRejectsUnknownTier() {
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateVipKey("nonexistent", "30d", 1, null, -1, null));
    }

    @Test
    void generateRewardKeyRejectsUnknownReward() {
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateRewardKey("nonexistent", 1, null, -1, null));
    }

    @Test
    void generateRewardKeyRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateRewardKey(null, 1, null, -1, null));
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateRewardKey("", 1, null, -1, null));
        assertThrows(IllegalArgumentException.class, () ->
                KeyService.generateRewardKey("coins", 0, null, -1, null));
    }
}

package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionContext;
import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.platform.TextUtil;
import br.com.pedrodalben.easyvip.util.DurationParser;
import br.com.pedrodalben.easyvip.util.KeySecurity;
import br.com.pedrodalben.easyvip.util.UniqueCodeGenerator;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class KeyService {

    private static final ConcurrentHashMap<UUID, PendingConfirmation> confirmations = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Map<CommandThrottleType, Long>> commandCooldowns = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> KEY_LOCKS = new ConcurrentHashMap<>();

    private static final int MAX_GENERATION_ATTEMPTS = 1000;

    private enum CommandThrottleType {
        USE,
        CONFIRM
    }

    private KeyService() {
    }

    public static class PendingConfirmation {
        public final String code;
        public final long timestamp;

        public PendingConfirmation(String code) {
            this.code = code;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > (EasyVipConfig.common.confirmTimeoutSeconds * 1000L);
        }
    }

    public enum RedeemResult {
        SUCCESS,
        INVALID_KEY,
        EXPIRED,
        NO_USES_LEFT,
        ON_COOLDOWN,
        ALREADY_USED,
        BOUND_TO_OTHER,
        CONFIRMATION_REQUIRED,
        ERROR
    }

    public static String generateRandomCode() {
        return UniqueCodeGenerator.generateCandidate(
                EasyVipConfig.common.keyCharset,
                EasyVipConfig.common.keyLength,
                EasyVipConfig.common.keyPrefix
        );
    }

    public static KeyRecord generateVipKey(String tierId, String durationStr, int maxUses, UUID boundPlayer, long expiryTime, List<Map<String, Object>> actions) {
        KeyRecord record = createVipKeyRecord(tierId, durationStr, maxUses, boundPlayer, expiryTime, actions);
        insertUnique(record);
        PersistenceManager.log("System", "generate_vip_key", "Generated VIP key "
                + KeySecurity.describeKeyForLog(record.getCode()) + " for tier " + tierId);
        return record;
    }

    public static KeyRecord generateRewardKey(String rewardKeyId, int maxUses, UUID boundPlayer, long expiryTime, List<Map<String, Object>> actions) {
        KeyRecord record = createRewardKeyRecord(rewardKeyId, maxUses, boundPlayer, expiryTime, actions);
        insertUnique(record);
        PersistenceManager.log("System", "generate_reward_key", "Generated Reward key "
                + KeySecurity.describeKeyForLog(record.getCode()) + " of definition " + rewardKeyId);
        return record;
    }

    public static KeyRecord generateCustomKey(List<Map<String, Object>> actions, int maxUses, UUID boundPlayer, long expiryTime) {
        KeyRecord record = createCustomKeyRecord(actions, maxUses, boundPlayer, expiryTime);
        insertUnique(record);
        PersistenceManager.log("System", "generate_custom_key", "Generated Custom key "
                + KeySecurity.describeKeyForLog(record.getCode()) + " with " + actions.size() + " actions");
        return record;
    }

    public static KeyRecord createVipKeyRecord(String tierId, String durationStr, int maxUses, UUID boundPlayer, long expiryTime, List<Map<String, Object>> actions) {
        if (maxUses <= 0) {
            throw new IllegalArgumentException("maxUses must be greater than 0");
        }
        if (tierId == null || tierId.isBlank()) {
            throw new IllegalArgumentException("tierId cannot be empty");
        }
        if (!EasyVipConfig.tiers.list.containsKey(tierId)) {
            throw new IllegalArgumentException("Unknown VIP tier: " + tierId);
        }
        long duration = DurationParser.parseDurationMillis(durationStr);
        if (duration == 0 || (duration < 0 && duration != -1)) {
            throw new IllegalArgumentException("Invalid VIP duration: " + durationStr);
        }
        if (expiryTime < -1) {
            throw new IllegalArgumentException("Invalid key expiry time");
        }

        KeyRecord record = new KeyRecord();
        record.setType("vip");
        record.setTierId(tierId);
        record.setDuration(durationStr);
        record.setMaxUses(maxUses);
        record.setBoundPlayerUuid(boundPlayer);
        record.setCreatedTime(System.currentTimeMillis());
        record.setExpiryTime(expiryTime);
        if (actions != null) {
            record.setActions(actions);
        }
        return record;
    }

    public static KeyRecord createRewardKeyRecord(String rewardKeyId, int maxUses, UUID boundPlayer, long expiryTime, List<Map<String, Object>> actions) {
        if (maxUses <= 0) {
            throw new IllegalArgumentException("maxUses must be greater than 0");
        }
        if (rewardKeyId == null || rewardKeyId.isBlank()) {
            throw new IllegalArgumentException("rewardKeyId cannot be empty");
        }
        if (!EasyVipConfig.rewardKeys.list.containsKey(rewardKeyId)) {
            throw new IllegalArgumentException("Unknown reward key: " + rewardKeyId);
        }
        if (expiryTime < -1) {
            throw new IllegalArgumentException("Invalid key expiry time");
        }

        KeyRecord record = new KeyRecord();
        record.setType("reward");
        record.setRewardKeyId(rewardKeyId);
        record.setMaxUses(maxUses);
        record.setBoundPlayerUuid(boundPlayer);
        record.setCreatedTime(System.currentTimeMillis());
        record.setExpiryTime(expiryTime);
        if (actions != null) {
            record.setActions(actions);
        }
        return record;
    }

    public static KeyRecord createCustomKeyRecord(List<Map<String, Object>> actions, int maxUses, UUID boundPlayer, long expiryTime) {
        if (maxUses <= 0) {
            throw new IllegalArgumentException("maxUses must be greater than 0");
        }
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("custom key actions cannot be empty");
        }
        if (expiryTime < -1) {
            throw new IllegalArgumentException("Invalid key expiry time");
        }

        KeyRecord record = new KeyRecord();
        record.setType("custom");
        record.setMaxUses(maxUses);
        record.setBoundPlayerUuid(boundPlayer);
        record.setCreatedTime(System.currentTimeMillis());
        record.setExpiryTime(expiryTime);
        record.setActions(actions);
        return record;
    }

    private static void insertUnique(KeyRecord record) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            record.setCode(UniqueCodeGenerator.generateCandidate(
                    EasyVipConfig.common.keyCharset,
                    EasyVipConfig.common.keyLength,
                    EasyVipConfig.common.keyPrefix
            ));
            KeyRecord existing = PersistenceManager.putKeyIfAbsent(record);
            if (existing == null) {
                return;
            }
        }
        throw new IllegalStateException("Could not generate a unique key code after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    public static RedeemResult redeemKey(Player player, String rawCode, boolean bypassConfirm) {
        return redeemKey(player, rawCode, bypassConfirm, CommandThrottleType.USE, true, null);
    }

    public static RedeemResult redeemKey(Player player, String rawCode, boolean bypassConfirm, CommandThrottleType throttleType) {
        return redeemKey(player, rawCode, bypassConfirm, throttleType, true, null);
    }

    public static RedeemResult redeemPhysicalKey(Player player, String rawCode, String instanceId) {
        return redeemKey(player, rawCode, false, CommandThrottleType.USE, true, instanceId);
    }

    private static RedeemResult redeemKey(Player player, String rawCode, boolean bypassConfirm, CommandThrottleType throttleType, boolean applyCooldown, String physicalInstanceId) {
        String code = normalizeCode(rawCode);
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();

        if (applyCooldown && isOnCooldown(uuid, throttleType)) {
            return RedeemResult.ON_COOLDOWN;
        }

        if (EasyVipConfig.common.confirmBeforeUse && !bypassConfirm) {
            confirmations.put(uuid, new PendingConfirmation(code));
            return RedeemResult.CONFIRMATION_REQUIRED;
        }

        Object lock = KEY_LOCKS.computeIfAbsent(code, k -> new Object());
        synchronized (lock) {
            KeyRecord record = PersistenceManager.getKey(code);
            RedeemResult preflight = preflightCheck(record, uuid, physicalInstanceId);
            if (preflight != null) {
                return preflight;
            }

            Map<String, String> ctx = new HashMap<>();
            ctx.put("player", playerName);
            ctx.put("player_uuid", uuid.toString());

            boolean success = executeKeyReward(player, record, ctx);
            if (!success) {
                return RedeemResult.ERROR;
            }

            consumeRecord(record, uuid, physicalInstanceId);
            PersistenceManager.putKey(record);
            if (applyCooldown) {
                markCooldown(uuid, throttleType);
            }
            confirmations.remove(uuid);
            PersistenceManager.log(playerName, "redeem_key", "Redeemed key "
                    + KeySecurity.describeKeyForLog(code));
            return RedeemResult.SUCCESS;
        }
    }

    private static String normalizeCode(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        if (EasyVipConfig.common.caseSensitiveKeys) {
            return rawCode.trim();
        }
        return rawCode.trim().toUpperCase(Locale.ROOT);
    }

    private static RedeemResult preflightCheck(KeyRecord record, UUID uuid, String physicalInstanceId) {
        if (record == null) {
            return RedeemResult.INVALID_KEY;
        }
        if (record.isExpired()) {
            return RedeemResult.EXPIRED;
        }
        if (record.isFullyUsed()) {
            return RedeemResult.NO_USES_LEFT;
        }
        if (record.getBoundPlayerUuid() != null && !record.getBoundPlayerUuid().equals(uuid)) {
            return RedeemResult.BOUND_TO_OTHER;
        }

        boolean isRewardNoConsume = "reward".equalsIgnoreCase(record.getType())
                && !isRewardConsumeOnUse(record);
        if (!isRewardNoConsume && record.getUsedBy().contains(uuid)) {
            return RedeemResult.ALREADY_USED;
        }
        if (physicalInstanceId != null && record.isInstanceConsumed(physicalInstanceId)) {
            return RedeemResult.ALREADY_USED;
        }
        return null;
    }

    private static boolean isRewardConsumeOnUse(KeyRecord record) {
        EasyVipConfig.RewardKeyDefinition rkDef = EasyVipConfig.rewardKeys.list.get(record.getRewardKeyId());
        return rkDef == null || rkDef.consumeOnUse;
    }

    private static boolean executeKeyReward(Player player, KeyRecord record, Map<String, String> ctx) {
        UUID uuid = player.getUniqueId();
        String dimensionId = getDimensionId(player);
        String code = record.getCode();
        String playerName = ctx.getOrDefault("player", player.getName());

        if (record.getType().equalsIgnoreCase("vip")) {
            EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
            String tierDisplay = (tierDef != null) ? tierDef.displayName : record.getTierId();
            ctx.put("tier_id", record.getTierId());
            ctx.put("tier_display", tierDisplay);
            ctx.put("duration", record.getDuration());

            if (!isDimensionAllowed(dimensionId, EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                PersistenceManager.log(playerName, "redeem_key_failed", "VIP dimension blocked for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }
            return VipService.addVip(uuid, playerName, record.getTierId(), record.getDuration(), playerName, false);
        }

        if (record.getType().equalsIgnoreCase("reward")) {
            ctx.put("reward_key_id", record.getRewardKeyId());
            EasyVipConfig.RewardKeyDefinition rkDef = EasyVipConfig.rewardKeys.list.get(record.getRewardKeyId());
            if (rkDef == null) {
                sendNotConsumedMessage(player, "&cReward not found or invalid. The key was not consumed.",
                        "&cRecompensa não encontrada ou inválida. A chave não foi consumida.", ctx);
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward definition missing for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }
            if (!isDimensionAllowed(dimensionId, EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                PersistenceManager.log(playerName, "redeem_key_failed", "Dimension blocked for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }
            if (!rkDef.allowedDimensions.isEmpty() && !isDimensionAllowed(dimensionId, rkDef.allowedDimensions, Collections.emptyList())) {
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward dimension blocked for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }

            List<Map<String, Object>> actions = record.getActions();
            if (actions == null || actions.isEmpty()) {
                actions = rkDef.actions;
                ctx.put("key_display", rkDef.displayName);
            }
            if (actions == null || actions.isEmpty()) {
                sendNotConsumedMessage(player, "&cReward not found or invalid. The key was not consumed.",
                        "&cRecompensa não encontrada ou inválida. A chave não foi consumida.", ctx);
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward actions missing for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }

            Long lastUsed = record.getLastUsedAtBy().get(uuid);
            long cooldownMs = rkDef.cooldownSeconds > 0 ? rkDef.cooldownSeconds * 1000L : 0L;
            if (cooldownMs > 0 && lastUsed != null && System.currentTimeMillis() - lastUsed < cooldownMs) {
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward cooldown active for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }

            boolean actionsOk = ActionExecutor.execute(player, actions, ctx);
            if (!actionsOk) {
                sendNotConsumedMessage(player, "&cReward not found or invalid. The key was not consumed.",
                        "&cRecompensa não encontrada ou inválida. A chave não foi consumida.", ctx);
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward actions failed for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }
            return true;
        }

        if (record.getType().equalsIgnoreCase("custom")) {
            if (!isDimensionAllowed(dimensionId, EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                PersistenceManager.log(playerName, "redeem_key_failed", "Dimension blocked for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }

            List<Map<String, Object>> actions = record.getActions();
            if (actions == null || actions.isEmpty()) {
                sendNotConsumedMessage(player, "&cCustom actions not found or invalid. The key was not consumed.",
                        "&cAções customizadas não encontradas ou inválidas. A chave não foi consumida.", ctx);
                PersistenceManager.log(playerName, "redeem_key_failed", "Custom actions missing for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }

            boolean actionsOk = ActionExecutor.execute(player, actions, ctx);
            if (!actionsOk) {
                sendNotConsumedMessage(player, "&cError executing custom actions. The key was not consumed.",
                        "&cErro ao executar ações customizadas. A chave não foi consumida.", ctx);
                PersistenceManager.log(playerName, "redeem_key_failed", "Custom actions failed for "
                        + KeySecurity.describeKeyForLog(code));
                return false;
            }
            return true;
        }

        return false;
    }

    private static String getDimensionId(Player player) {
        if (player == null || player.getWorld() == null) {
            return "minecraft:overworld";
        }
        String worldName = player.getWorld().getName();
        String worldKey = player.getWorld().getKey().toString();
        return worldKey;
    }

    private static void sendNotConsumedMessage(Player player, String en, String pt, Map<String, String> ctx) {
        TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                EasyVipConfig.messages.prefix + EasyVipConfig.localized(en, pt), ctx));
    }

    private static void consumeRecord(KeyRecord record, UUID uuid, String physicalInstanceId) {
        if ("reward".equalsIgnoreCase(record.getType()) && !isRewardConsumeOnUse(record)) {
            record.getLastUsedAtBy().put(uuid, System.currentTimeMillis());
        } else {
            record.setUsedCount(record.getUsedCount() + 1);
            record.getUsedBy().add(uuid);
            record.getLastUsedAtBy().put(uuid, System.currentTimeMillis());
        }
        if (physicalInstanceId != null) {
            record.markInstanceConsumed(physicalInstanceId);
        }
    }

    private static boolean isOnCooldown(UUID uuid, CommandThrottleType throttleType) {
        long cooldownMs = EasyVipConfig.common.commandCooldownTicks * 50L;
        if (cooldownMs <= 0) {
            return false;
        }

        long now = System.currentTimeMillis();
        Map<CommandThrottleType, Long> byType = commandCooldowns.computeIfAbsent(uuid, k -> new EnumMap<>(CommandThrottleType.class));
        Long lastUsed = byType.get(throttleType);
        return lastUsed != null && now - lastUsed < cooldownMs;
    }

    private static void markCooldown(UUID uuid, CommandThrottleType throttleType) {
        long cooldownMs = EasyVipConfig.common.commandCooldownTicks * 50L;
        if (cooldownMs <= 0) {
            return;
        }
        Map<CommandThrottleType, Long> byType = commandCooldowns.computeIfAbsent(uuid, k -> new EnumMap<>(CommandThrottleType.class));
        byType.put(throttleType, System.currentTimeMillis());
    }

    private static boolean isDimensionAllowed(String dimensionId, List<String> allowedList, List<String> denyList) {
        String normalized = dimensionId == null ? "" : dimensionId.toLowerCase(Locale.ROOT);
        for (String entry : denyList) {
            String entryNorm = entry.toLowerCase(Locale.ROOT);
            if (normalized.equals(entryNorm) || normalized.endsWith(":" + entryNorm)) {
                return false;
            }
        }
        if (allowedList == null || allowedList.isEmpty()) {
            return true;
        }
        for (String entry : allowedList) {
            String entryNorm = entry.toLowerCase(Locale.ROOT);
            if (normalized.equals(entryNorm) || normalized.endsWith(":" + entryNorm)) {
                return true;
            }
        }
        return false;
    }

    public static RedeemResult confirmPending(Player player) {
        UUID uuid = player.getUniqueId();
        PendingConfirmation pc = confirmations.get(uuid);
        if (pc == null || pc.isExpired()) {
            confirmations.remove(uuid);
            return RedeemResult.INVALID_KEY;
        }
        return redeemKey(player, pc.code, true, CommandThrottleType.CONFIRM, true, null);
    }

    // ─── Physical Item Key (PersistentDataContainer) ──────────────────

    private static NamespacedKey getKeyMarkerKey() {
        return new NamespacedKey("easyvip", EasyVipConfig.common.itemKeyMarker.toLowerCase(Locale.ROOT));
    }

    private static NamespacedKey getKeyCodeKey() {
        return new NamespacedKey("easyvip", "easyvip_key");
    }

    private static NamespacedKey getKeyInstanceKey() {
        return new NamespacedKey("easyvip", "easyvip_key_instance");
    }

    public static ItemStack createPhysicalKeyItem(String keyCode) {
        String rawItemId = EasyVipConfig.common.itemKeyItemId;
        String clean = rawItemId.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }

        Material material = Material.matchMaterial(clean);
        if (material == null) {
            material = Material.matchMaterial(clean.toUpperCase(Locale.ROOT));
        }
        if (material == null || material.isAir()) {
            material = Material.TRIPWIRE_HOOK;
        }

        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(getKeyMarkerKey(), PersistentDataType.BYTE, (byte) 1);
            pdc.set(getKeyCodeKey(), PersistentDataType.STRING, keyCode);
            pdc.set(getKeyInstanceKey(), PersistentDataType.STRING, UUID.randomUUID().toString());

            meta.displayName(TextUtil.toComponent("&6&lChave VIP &7(&e" + keyCode + "&7)"));
            meta.lore(List.of(
                    TextUtil.toComponent("&7Clique com o botão direito"),
                    TextUtil.toComponent("&7para ativar esta chave!")
            ));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static String getPhysicalKeyInstanceId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(getKeyInstanceKey(), PersistentDataType.STRING);
    }

    public static String getPhysicalKeyCode(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(getKeyCodeKey(), PersistentDataType.STRING);
    }

    public static boolean isPhysicalKeyItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }

        String rawItemId = EasyVipConfig.common.itemKeyItemId;
        String clean = rawItemId.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        Material expected = Material.matchMaterial(clean);
        if (expected == null) {
            expected = Material.matchMaterial(clean.toUpperCase(Locale.ROOT));
        }
        if (expected != null && stack.getType() != expected) {
            return false;
        }

        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte marker = pdc.get(getKeyMarkerKey(), PersistentDataType.BYTE);
        String code = pdc.get(getKeyCodeKey(), PersistentDataType.STRING);

        return marker != null && marker == (byte) 1 && code != null && !code.isBlank();
    }

    static RedeemResult redeemRewardKeyForTest(KeyRecord record, UUID uuid, String playerName, String dimensionId, boolean applyCooldown, Function<List<Map<String, Object>>, Boolean> actionRunner) {
        if (record == null || uuid == null) {
            return RedeemResult.ERROR;
        }

        String code = record.getCode();
        Object lock = KEY_LOCKS.computeIfAbsent(code, k -> new Object());
        synchronized (lock) {
            KeyRecord current = PersistenceManager.getKey(code);
            if (current == null) {
                return RedeemResult.INVALID_KEY;
            }

            if (current.isExpired()) {
                return RedeemResult.EXPIRED;
            }

            if (current.isFullyUsed()) {
                return RedeemResult.NO_USES_LEFT;
            }

            if (current.getBoundPlayerUuid() != null && !current.getBoundPlayerUuid().equals(uuid)) {
                return RedeemResult.BOUND_TO_OTHER;
            }

            boolean rewardNoConsume = "reward".equalsIgnoreCase(current.getType())
                    && !isRewardConsumeOnUse(current);
            if (!rewardNoConsume && current.getUsedBy().contains(uuid)) {
                return RedeemResult.ALREADY_USED;
            }

            if (applyCooldown) {
                if (isOnCooldown(uuid, CommandThrottleType.USE)) {
                    return RedeemResult.ON_COOLDOWN;
                }
                EasyVipConfig.RewardKeyDefinition rkDefCooldown = EasyVipConfig.rewardKeys.list.get(current.getRewardKeyId());
                if (rkDefCooldown != null) {
                    Long lastUsed = current.getLastUsedAtBy().get(uuid);
                    long cooldownMs = rkDefCooldown.cooldownSeconds > 0 ? rkDefCooldown.cooldownSeconds * 1000L : 0L;
                    if (cooldownMs > 0 && lastUsed != null && System.currentTimeMillis() - lastUsed < cooldownMs) {
                        return RedeemResult.ON_COOLDOWN;
                    }
                }
            }

            List<Map<String, Object>> actions = current.getActions();
            if ("reward".equalsIgnoreCase(current.getType())) {
                EasyVipConfig.RewardKeyDefinition rkDef = EasyVipConfig.rewardKeys.list.get(current.getRewardKeyId());
                if (rkDef == null) {
                    return RedeemResult.ERROR;
                }

                if (!isDimensionAllowed(dimensionId, EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                    return RedeemResult.ERROR;
                }
                if (!rkDef.allowedDimensions.isEmpty() && !isDimensionAllowed(dimensionId, rkDef.allowedDimensions, Collections.emptyList())) {
                    return RedeemResult.ERROR;
                }

                if (actions == null || actions.isEmpty()) {
                    actions = rkDef.actions;
                }
                if (actions == null || actions.isEmpty()) {
                    return RedeemResult.ERROR;
                }

                if (actionRunner == null || !Boolean.TRUE.equals(actionRunner.apply(actions))) {
                    return RedeemResult.ERROR;
                }

                if (rkDef.consumeOnUse) {
                    current.setUsedCount(current.getUsedCount() + 1);
                    current.getUsedBy().add(uuid);
                }
                current.getLastUsedAtBy().put(uuid, System.currentTimeMillis());
                PersistenceManager.putKey(current);
                if (applyCooldown) {
                    markCooldown(uuid, CommandThrottleType.USE);
                }
                return RedeemResult.SUCCESS;
            } else if ("custom".equalsIgnoreCase(current.getType())) {
                if (!isDimensionAllowed(dimensionId, EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                    return RedeemResult.ERROR;
                }

                if (actions == null || actions.isEmpty()) {
                    return RedeemResult.ERROR;
                }

                if (actionRunner == null || !Boolean.TRUE.equals(actionRunner.apply(actions))) {
                    return RedeemResult.ERROR;
                }

                current.setUsedCount(current.getUsedCount() + 1);
                current.getUsedBy().add(uuid);
                current.getLastUsedAtBy().put(uuid, System.currentTimeMillis());
                PersistenceManager.putKey(current);
                if (applyCooldown) {
                    markCooldown(uuid, CommandThrottleType.USE);
                }
                return RedeemResult.SUCCESS;
            }

            return RedeemResult.ERROR;
        }
    }

    static boolean isPhysicalKeyPayloadValid(String configuredItemId, String actualItemId, boolean markerPresent, boolean hasKeyValue) {
        if (configuredItemId == null || actualItemId == null) {
            return false;
        }
        if (!configuredItemId.equalsIgnoreCase(actualItemId)) {
            return false;
        }
        return markerPresent && hasKeyValue;
    }
}

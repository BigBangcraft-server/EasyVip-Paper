package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionContext;
import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.event.VipActivateEvent;
import br.com.pedrodalben.easyvip.event.VipExpireEvent;
import br.com.pedrodalben.easyvip.model.*;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.platform.TextUtil;
import br.com.pedrodalben.easyvip.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VipService {

    private static final Pattern SCRIPT_VARIABLE_ASSIGNMENT = Pattern.compile("^\\$([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$");

    private VipService() {
    }

    public static long parseDurationMillis(String durationStr) {
        return DurationParser.parseDurationMillis(durationStr);
    }

    public static boolean addVip(UUID uuid, String tierId, String durationStr, String operator) {
        return addVip(uuid, null, tierId, durationStr, operator, false);
    }

    public static boolean addFakePlayerVip(String playerName, String tierId, String durationStr, String operator) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        UUID uuid = UUID.nameUUIDFromBytes(("bigbang-fake-player:" + playerName.toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8));
        return addVip(uuid, playerName, tierId, durationStr, operator, true);
    }

    public static boolean addVip(UUID uuid, String knownPlayerName, String tierId,
                                 String durationStr, String operator, boolean activateWhileOffline) {
        EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(tierId);
        if (tierDef == null) {
            return false;
        }

        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            registry = new PlayerVipRegistry(uuid);
        }

        long duration = parseDurationMillis(durationStr);
        long now = System.currentTimeMillis();

        PlayerVipRecord record = registry.getVips().get(tierId);
        Player player = Bukkit.getPlayer(uuid);
        boolean isOnline = player != null && player.isOnline();
        String targetName = knownPlayerName != null ? knownPlayerName : resolvePlayerName(uuid);
        if (isOnline) {
            targetName = player.getName();
        }

        Map<String, String> ctx = new HashMap<>();
        ctx.put("tier_id", tierId);
        ctx.put("tier_display", tierDef.displayName);
        ctx.put("duration", DurationParser.formatDuration(duration));
        ctx.put("player", targetName);
        ctx.put("player_uuid", uuid.toString());

        if (record == null || record.isExpired()) {
            // New VIP tier activation
            long expiry = (duration == -1) ? -1 : now + duration;
            record = new PlayerVipRecord(tierId, now, expiry, false, !isOnline);
            registry.getVips().put(tierId, record);

            enrichVipContext(ctx, uuid, targetName, tierDef, duration, now, expiry);
            if (isOnline || activateWhileOffline) {
                executeVipActivationFlow(uuid, player, targetName, tierDef, ctx, "vip_activate", tierDef.messages.activated);
                broadcastVipActivation(targetName, tierDef.displayName);
            } else {
                record.setPendingActivateActions(true);
            }
        } else {
            // Extension or stack check
            if (!tierDef.allowStacking) {
                if (tierDef.activationMode.equalsIgnoreCase("replace")) {
                    long expiry = (duration == -1) ? -1 : now + duration;
                    record.setExpiryTime(expiry);
                    record.setStartTime(now);
                    enrichVipContext(ctx, uuid, targetName, tierDef, duration, now, expiry);
                    if (isOnline || activateWhileOffline) {
                        executeVipActivationFlow(uuid, player, targetName, tierDef, ctx, "vip_replace", tierDef.messages.activated);
                        broadcastVipActivation(targetName, tierDef.displayName);
                    } else {
                        record.setPendingActivateActions(true);
                    }
                } else {
                    return false; // Denied stacking
                }
            } else {
                // Stacking allowed
                if (record.getExpiryTime() == -1) {
                    // Already permanent
                    enrichVipContext(ctx, uuid, targetName, tierDef, duration, now, record.getExpiryTime());
                    if (isOnline || activateWhileOffline) {
                        executeVipActivationFlow(uuid, player, targetName, tierDef, ctx, "vip_stack_perm", EasyVipConfig.messages.vipExtended);
                    } else {
                        record.setPendingActivateActions(true);
                    }
                } else if (duration == -1) {
                    // Upgrading to permanent
                    record.setExpiryTime(-1);
                    enrichVipContext(ctx, uuid, targetName, tierDef, duration, now, record.getExpiryTime());
                    if (isOnline || activateWhileOffline) {
                        executeVipActivationFlow(uuid, player, targetName, tierDef, ctx, "vip_upgrade_perm", EasyVipConfig.messages.vipExtended);
                    } else {
                        record.setPendingActivateActions(true);
                    }
                } else {
                    // Standard duration extension
                    long currentExpiry = record.getExpiryTime();
                    long newExpiry = currentExpiry + duration;

                    // Cap stack duration if maxStackDurationSeconds is configured
                    if (tierDef.maxStackDurationSeconds > 0) {
                        long maxExpiry = record.getStartTime() + (tierDef.maxStackDurationSeconds * 1000L);
                        if (newExpiry > maxExpiry) {
                            newExpiry = maxExpiry;
                        }
                    }

                    long addedDuration = newExpiry - currentExpiry;
                    ctx.put("duration", DurationParser.formatDuration(addedDuration));
                    record.setExpiryTime(newExpiry);
                    enrichVipContext(ctx, uuid, targetName, tierDef, addedDuration, now, newExpiry);
                    if (isOnline || activateWhileOffline) {
                        executeVipActivationFlow(uuid, player, targetName, tierDef, ctx, "vip_extend", EasyVipConfig.messages.vipExtended);
                    } else {
                        record.setPendingActivateActions(true);
                    }
                }
            }
        }

        if (activateWhileOffline) {
            record.setPendingActivateActions(false);
        }
        registry.setPlayerName(targetName);
        evaluateActiveVip(uuid, registry);
        if (activateWhileOffline) {
            registry.setLastObservedActiveVip(registry.getVips().values().stream()
                    .filter(PlayerVipRecord::isActive)
                    .map(PlayerVipRecord::getTierId)
                    .findFirst()
                    .orElse(null));
        }
        PersistenceManager.updatePlayerVips(uuid, registry);

        PersistenceManager.log(operator, "add_vip", "VIP tier " + tierId + " added to " + targetName + " with duration " + durationStr);

        try {
            Bukkit.getPluginManager().callEvent(new VipActivateEvent(uuid, targetName, tierId, duration, operator));
        } catch (Throwable ignored) {
        }

        return true;
    }

    private static void broadcastVipActivation(String playerName, String tierDisplay) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("player", playerName);
        ctx.put("tier_display", tierDisplay);
        String message = ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.vipActivatedBroadcast, ctx);
        if (message != null && !message.isEmpty()) {
            TextUtil.broadcast(message);
        }
    }

    private static void executeVipActivationFlow(UUID uuid, Player player, String playerName,
                                                 EasyVipConfig.VipTierDefinition tierDef, Map<String, String> ctx,
                                                 String source, String messageTemplate) {
        if (tierDef == null) {
            return;
        }

        if (tierDef.actionsOnActivate != null && !tierDef.actionsOnActivate.isEmpty()) {
            executeTierActions(uuid, playerName, player, tierDef.actionsOnActivate, ctx, source + "_legacy");
        }

        if (tierDef.commands != null && tierDef.commands.activate != null && !tierDef.commands.activate.isEmpty()) {
            executeServerCommandList(uuid, player, playerName, tierDef.commands.activate, ctx, source + "_commands");
        }

        if (player != null && messageTemplate != null && !messageTemplate.isEmpty()) {
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + messageTemplate, ctx));
        }

        if (player != null) {
            executeActivationItems(player, tierDef, ctx, source);
        }
    }

    private static void executeVipExpireFlow(UUID uuid, Player player, String playerName,
                                             EasyVipConfig.VipTierDefinition tierDef, Map<String, String> ctx, String source) {
        if (tierDef == null) {
            return;
        }

        if (tierDef.actionsOnExpire != null && !tierDef.actionsOnExpire.isEmpty()) {
            executeTierActions(uuid, playerName, player, tierDef.actionsOnExpire, ctx, source + "_legacy");
        }

        if (tierDef.commands != null && tierDef.commands.expire != null && !tierDef.commands.expire.isEmpty()) {
            executeServerCommandList(uuid, player, playerName, tierDef.commands.expire, ctx, source + "_commands");
        }

        if (player != null && tierDef.messages != null && tierDef.messages.expired != null && !tierDef.messages.expired.isEmpty()) {
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + tierDef.messages.expired, ctx));
        }
    }

    public static void executeActivationItems(Player player, EasyVipConfig.VipTierDefinition tierDef,
                                              Map<String, String> ctx, String source) {
        if (player == null || tierDef == null || tierDef.activationItems.isEmpty()) {
            return;
        }

        String broadcastTemplate = (tierDef.messages != null && tierDef.messages.rareItemBroadcast != null && !tierDef.messages.rareItemBroadcast.isEmpty())
                ? tierDef.messages.rareItemBroadcast
                : EasyVipConfig.messages.vipLuckyItemBroadcast;

        for (EasyVipConfig.VipActivationItemDefinition itemDef : tierDef.activationItems) {
            if (itemDef == null) {
                continue;
            }

            double chance = Math.max(0.0d, Math.min(100.0d, itemDef.chance));
            boolean awarded = chanceSucceeded(chance, ThreadLocalRandom.current().nextDouble(100.0d));
            if (!awarded) {
                continue;
            }

            ItemStack stack = buildActivationItemStack(itemDef);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                for (ItemStack dropped : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                }
            }

            if (chance < 100.0d) {
                Map<String, String> luckyCtx = new HashMap<>(ctx);
                luckyCtx.put("item_name", stack.hasItemMeta() && stack.getItemMeta().hasDisplayName() ? stack.getItemMeta().getDisplayName() : stack.getType().getKey().getKey());
                luckyCtx.put("chance", formatChance(chance));
                String message = ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + broadcastTemplate, luckyCtx);
                TextUtil.broadcast(message);
            }
        }
    }

    static boolean chanceSucceeded(double chance, double roll) {
        if (chance >= 100.0d) {
            return true;
        }
        if (chance <= 0.0d) {
            return false;
        }
        return roll < chance;
    }

    private static ItemStack buildActivationItemStack(EasyVipConfig.VipActivationItemDefinition itemDef) {
        if (itemDef == null) {
            return null;
        }

        if (itemDef.stackSnbt != null && !itemDef.stackSnbt.isBlank()) {
            try {
                return Bukkit.getItemFactory().createItemStack(itemDef.stackSnbt.trim());
            } catch (Throwable ignored) {
            }
        }

        if (itemDef.itemId == null || itemDef.itemId.isBlank()) {
            return null;
        }

        String clean = itemDef.itemId.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }

        Material material = Material.matchMaterial(clean);
        if (material == null) {
            material = Material.matchMaterial(clean.toUpperCase(Locale.ROOT));
        }
        if (material == null || material.isAir()) {
            return null;
        }

        ItemStack stack = new ItemStack(material, Math.max(1, itemDef.amount));

        if (itemDef.enchants != null && !itemDef.enchants.isEmpty()) {
            for (Map.Entry<String, Integer> entry : itemDef.enchants.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 1) {
                    continue;
                }
                Enchantment ench = resolveEnchantment(entry.getKey());
                if (ench != null) {
                    stack.addUnsafeEnchantment(ench, entry.getValue());
                }
            }
        }

        return stack;
    }

    private static Enchantment resolveEnchantment(String enchantId) {
        if (enchantId == null || enchantId.isBlank()) {
            return null;
        }
        String clean = enchantId.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        NamespacedKey key = NamespacedKey.minecraft(clean);
        Enchantment ench = Registry.ENCHANTMENT.get(key);
        if (ench != null) {
            return ench;
        }
        return Enchantment.getByKey(key);
    }

    private static void executeServerCommandList(UUID uuid, Player player, String playerName,
                                                 List<String> commands, Map<String, String> ctx, String source) {
        if (commands == null || commands.isEmpty()) {
            return;
        }

        Map<String, String> scriptContext = new HashMap<>(ctx);
        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String trimmed = command.trim();
            Matcher matcher = SCRIPT_VARIABLE_ASSIGNMENT.matcher(trimmed);
            if (matcher.matches()) {
                String variableName = matcher.group(1);
                String valueExpression = matcher.group(2);
                String value = ActionExecutor.resolvePlaceholders(valueExpression, scriptContext);
                scriptContext.put("var." + variableName, value);
                continue;
            }

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "run_server_command");
            action.put("command", ActionExecutor.resolvePlaceholders(command, scriptContext));
            executeTierActions(uuid, playerName, player, List.of(action), scriptContext, source);
        }
    }

    private static String formatChance(double chance) {
        if (chance == Math.rint(chance)) {
            return String.valueOf((long) chance);
        }
        return String.valueOf(chance);
    }

    private static String formatTimestamp(long millis) {
        if (millis < 0) {
            return EasyVipConfig.messages.durationPermanent;
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(millis));
    }

    private static String formatDays(long millis) {
        if (millis < 0) {
            return EasyVipConfig.messages.durationPermanent;
        }
        long days = millis / (24L * 60L * 60L * 1000L);
        return String.valueOf(Math.max(0L, days));
    }

    private static void enrichVipContext(Map<String, String> ctx, UUID uuid, String playerName,
                                         EasyVipConfig.VipTierDefinition tierDef, long durationMillis,
                                         long startTime, long expiryTime) {
        if (ctx == null) {
            return;
        }

        ctx.put("uuid", uuid.toString());
        ctx.put("player_uuid", uuid.toString());
        ctx.put("player", playerName);
        if (tierDef != null) {
            ctx.put("vip_id", tierDef.id);
            ctx.put("vip_name", tierDef.displayName);
            ctx.put("tier_id", tierDef.id);
            ctx.put("tier_display", tierDef.displayName);
        }
        if (!ctx.containsKey("duration")) {
            ctx.put("duration", durationMillis == -1 ? EasyVipConfig.messages.durationPermanent : DurationParser.formatDuration(durationMillis));
        }
        ctx.put("days", formatDays(durationMillis));
        ctx.put("activation_date", formatTimestamp(startTime));
        ctx.put("expiration_date", formatTimestamp(expiryTime));
        long remainingMillis = expiryTime < 0 ? -1 : Math.max(0L, expiryTime - System.currentTimeMillis());
        ctx.put("remaining_days", formatDays(remainingMillis));
        ctx.put("remaining_time", remainingMillis < 0 ? EasyVipConfig.messages.durationPermanent : DurationParser.formatDuration(remainingMillis));
    }

    public static boolean removeVip(UUID uuid, String tierId, String operator) {
        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            return false;
        }

        PlayerVipRecord record = registry.getVips().remove(tierId);
        if (record == null) {
            return false;
        }

        Player player = Bukkit.getPlayer(uuid);
        EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(tierId);

        if (player != null && tierDef != null) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("tier_id", tierId);
            ctx.put("tier_display", tierDef.displayName);
            ctx.put("player", resolvePlayerName(uuid));
            ctx.put("player_uuid", uuid.toString());

            if (record.isActive()) {
                executeUnsetActiveActions(uuid, player, tierId, tierDef, ctx, "vip_remove_unset_active");
            } else {
                executeUnsetActiveActions(uuid, player, tierId, tierDef, ctx, "vip_remove_unset_active_offline");
            }
            executeTierActions(uuid, resolvePlayerName(uuid), player, tierDef.actionsOnRemove, ctx, "vip_remove");
        } else if (tierDef != null) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("tier_id", tierId);
            ctx.put("tier_display", tierDef.displayName);
            ctx.put("player", resolvePlayerName(uuid));
            ctx.put("player_uuid", uuid.toString());
            executeUnsetActiveActions(uuid, null, tierId, tierDef, ctx, "vip_remove_offline_unset");
            executeTierActions(uuid, resolvePlayerName(uuid), null, tierDef.actionsOnRemove, ctx, "vip_remove_offline");
        }

        registry.setPlayerName(resolvePlayerName(uuid));
        evaluateActiveVip(uuid, registry);
        PersistenceManager.updatePlayerVips(uuid, registry);

        String targetName = (player != null) ? player.getName() : uuid.toString();
        PersistenceManager.log(operator, "remove_vip", "VIP tier " + tierId + " removed from " + targetName);

        try {
            Bukkit.getPluginManager().callEvent(new VipExpireEvent(uuid, targetName, tierId));
        } catch (Throwable ignored) {
        }

        return true;
    }

    public static boolean setActiveVip(UUID uuid, String tierId, String operator) {
        if (!EasyVipConfig.common.allowPlayerActiveSelection) {
            return false;
        }

        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            return false;
        }

        PlayerVipRecord targetRecord = registry.getVips().get(tierId);
        if (targetRecord == null || targetRecord.isExpired()) {
            return false;
        }

        Player player = Bukkit.getPlayer(uuid);

        for (PlayerVipRecord record : registry.getVips().values()) {
            if (record.isActive() && !record.getTierId().equals(tierId)) {
                record.setActive(false);
                EasyVipConfig.VipTierDefinition oldDef = EasyVipConfig.tiers.list.get(record.getTierId());
                if (oldDef != null) {
                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", record.getTierId());
                    ctx.put("tier_display", oldDef.displayName);
                    ctx.put("player", resolvePlayerName(uuid));
                    ctx.put("player_uuid", uuid.toString());
                    executeUnsetActiveActions(uuid, player, record.getTierId(), oldDef, ctx, "vip_active_unset");
                }
            }
        }

        if (!targetRecord.isActive()) {
            targetRecord.setActive(true);
            EasyVipConfig.VipTierDefinition newDef = EasyVipConfig.tiers.list.get(tierId);
            if (newDef != null) {
                Map<String, String> ctx = new HashMap<>();
                ctx.put("tier_id", tierId);
                ctx.put("tier_display", newDef.displayName);
                ctx.put("player", resolvePlayerName(uuid));
                ctx.put("player_uuid", uuid.toString());
                executeSetActiveActions(uuid, player, newDef, ctx, "vip_active_set");
            }
        }

        if (player != null) {
            registry.setLastObservedActiveVip(tierId);
        }

        registry.setPlayerName(resolvePlayerName(uuid));
        PersistenceManager.updatePlayerVips(uuid, registry);
        PersistenceManager.log(operator, "change_active_vip", "Set active VIP tier " + tierId + " for " + uuid);
        return true;
    }

    public static void evaluateActiveVip(UUID uuid, PlayerVipRegistry registry) {
        if (registry == null) {
            return;
        }

        Player player = Bukkit.getPlayer(uuid);

        PlayerVipRecord highestVip = null;
        int highestPriority = -1;

        List<PlayerVipRecord> validVips = new ArrayList<>();
        PlayerVipRecord currentActive = null;

        for (PlayerVipRecord record : registry.getVips().values()) {
            if (!record.isExpired()) {
                validVips.add(record);
                EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(record.getTierId());
                int priority = (def != null) ? def.priority : 0;
                if (priority > highestPriority) {
                    highestPriority = priority;
                    highestVip = record;
                }
                if (record.isActive()) {
                    currentActive = record;
                }
            } else if (record.isActive()) {
                record.setActive(false);
            }
        }

        PlayerVipRecord targetActive = null;
        if (!validVips.isEmpty()) {
            boolean forceHighest = EasyVipConfig.common.forceHighestPriorityAsActive;
            if (forceHighest || currentActive == null || currentActive.isExpired()) {
                targetActive = (highestVip != null) ? highestVip : validVips.get(0);
            } else {
                targetActive = currentActive;
            }
        }

        if (currentActive != targetActive) {
            if (currentActive != null) {
                currentActive.setActive(false);
            }
            if (targetActive != null) {
                targetActive.setActive(true);
            }
        }

        String desiredActiveVip = (targetActive != null) ? targetActive.getTierId() : null;
        String lastObserved = registry.getLastObservedActiveVip();

        if (!Objects.equals(desiredActiveVip, lastObserved)) {
            if (lastObserved != null) {
                EasyVipConfig.VipTierDefinition oldDef = EasyVipConfig.tiers.list.get(lastObserved);
                Map<String, String> ctx = new HashMap<>();
                ctx.put("tier_id", lastObserved);
                ctx.put("tier_display", oldDef != null ? oldDef.displayName : lastObserved);
                ctx.put("player", resolvePlayerName(uuid));
                ctx.put("player_uuid", uuid.toString());
                executeUnsetActiveActions(uuid, player, lastObserved, oldDef, ctx, "vip_deactivate_old");
            }

            if (desiredActiveVip != null) {
                EasyVipConfig.VipTierDefinition newDef = EasyVipConfig.tiers.list.get(desiredActiveVip);
                if (newDef != null) {
                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", desiredActiveVip);
                    ctx.put("tier_display", newDef.displayName);
                    ctx.put("player", resolvePlayerName(uuid));
                    ctx.put("player_uuid", uuid.toString());
                    executeSetActiveActions(uuid, player, newDef, ctx, "vip_activate_new");
                }
            }

            if (player != null && player.isOnline()) {
                registry.setLastObservedActiveVip(desiredActiveVip);
            }
        }
    }

    public static int expireAllDueVips() {
        int expiredCount = 0;
        for (Map.Entry<UUID, PlayerVipRegistry> entry : PersistenceManager.getAllPlayerVips().entrySet()) {
            expiredCount += expireDueVipsForPlayer(entry.getKey());
        }
        return expiredCount;
    }

    public static int expireDueVipsForPlayer(UUID uuid) {
        return expireDueVipsForPlayer(uuid, resolvePlayerName(uuid), Bukkit.getPlayer(uuid));
    }

    static int expireDueVipsForTest(UUID uuid, String playerName) {
        return expireDueVipsForPlayer(uuid, playerName, null);
    }

    private static int expireDueVipsForPlayer(UUID uuid, String playerName, Player player) {
        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null || registry.getVips().isEmpty()) {
            return 0;
        }

        registry.setPlayerName(playerName);

        boolean changed = false;
        int expiredCount = 0;
        Iterator<Map.Entry<String, PlayerVipRecord>> it = registry.getVips().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PlayerVipRecord> entry = it.next();
            PlayerVipRecord record = entry.getValue();
            if (record.isExpired()) {
                it.remove();
                changed = true;
                expiredCount++;

                EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
                Map<String, String> ctx = new HashMap<>();
                ctx.put("tier_id", record.getTierId());
                ctx.put("tier_display", tierDef != null ? tierDef.displayName : record.getTierId());
                ctx.put("player", playerName);
                ctx.put("player_uuid", uuid.toString());

                if (tierDef != null) {
                    long originalDuration = record.getExpiryTime() == -1 ? -1 : (record.getExpiryTime() - record.getStartTime());
                    enrichVipContext(ctx, uuid, playerName, tierDef, originalDuration, record.getStartTime(), record.getExpiryTime());
                    if (record.isActive()) {
                        executeUnsetActiveActions(uuid, player, record.getTierId(), tierDef, ctx, "vip_expire_unset_active");
                    }
                    executeVipExpireFlow(uuid, player, playerName, tierDef, ctx, "vip_expire");
                }

                PersistenceManager.log("System", "vip_expired", "VIP tier " + record.getTierId() + " expired for " + playerName);

                try {
                    Bukkit.getPluginManager().callEvent(new VipExpireEvent(uuid, playerName, record.getTierId()));
                } catch (Throwable ignored) {
                }
            }
        }

        if (changed) {
            evaluateActiveVip(uuid, registry);
            PersistenceManager.updatePlayerVips(uuid, registry);
        }

        return expiredCount;
    }

    public static void checkExpirations() {
        expireAllDueVips();
    }

    public static void handlePlayerJoin(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            return;
        }

        registry.setPlayerName(player.getName());
        expireDueVipsForPlayer(uuid);

        for (PlayerVipRecord record : registry.getVips().values()) {
            if (record.isPendingActivateActions()) {
                EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
                if (tierDef != null) {
                    long remaining = record.getExpiryTime() == -1 ? -1 : (record.getExpiryTime() - record.getStartTime());
                    String formattedDuration = DurationParser.formatDuration(remaining);

                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", record.getTierId());
                    ctx.put("tier_display", tierDef.displayName);
                    ctx.put("duration", formattedDuration);
                    ctx.put("player", player.getName());
                    ctx.put("player_uuid", uuid.toString());
                    enrichVipContext(ctx, uuid, player.getName(), tierDef, remaining, record.getStartTime(), record.getExpiryTime());
                    executeVipActivationFlow(uuid, player, player.getName(), tierDef, ctx, "vip_pending_activate", tierDef.messages.activated);
                    broadcastVipActivation(player.getName(), tierDef.displayName);
                }
                record.setPendingActivateActions(false);
            }
        }

        evaluateActiveVip(uuid, registry);
        PersistenceManager.updatePlayerVips(uuid, registry);
    }

    public static String resolvePlayerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        if (offline.getName() != null && !offline.getName().isBlank()) {
            return offline.getName();
        }
        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry != null && registry.getPlayerName() != null && !registry.getPlayerName().isBlank()) {
            return registry.getPlayerName();
        }
        return uuid.toString();
    }

    private static void executeSetActiveActions(UUID uuid, Player player, EasyVipConfig.VipTierDefinition tierDef, Map<String, String> ctx, String source) {
        if (tierDef == null) return;
        List<Map<String, Object>> actions = new ArrayList<>();
        if (tierDef.actionsOnSetActive != null) {
            actions.addAll(tierDef.actionsOnSetActive);
        }
        if (EasyVipConfig.integrations.ftbRanksEnabled) {
            Map<String, Object> ftbAction = new HashMap<>();
            ftbAction.put("type", "add_ftb_rank");
            ftbAction.put("rank", tierDef.id);
            actions.add(ftbAction);
        }
        if (EasyVipConfig.integrations.luckpermsEnabled) {
            Map<String, Object> lpAction = new HashMap<>();
            lpAction.put("type", "add_luckperms_group");
            lpAction.put("group", tierDef.id);
            actions.add(lpAction);
        }
        executeTierActions(uuid, ctx.getOrDefault("player", resolvePlayerName(uuid)), player, actions, ctx, source);
    }

    private static void executeUnsetActiveActions(UUID uuid, Player player, String tierId, EasyVipConfig.VipTierDefinition tierDef, Map<String, String> ctx, String source) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (tierDef != null && tierDef.actionsOnUnsetActive != null) {
            actions.addAll(tierDef.actionsOnUnsetActive);
        }
        if (EasyVipConfig.integrations.ftbRanksEnabled) {
            Map<String, Object> ftbAction = new HashMap<>();
            ftbAction.put("type", "remove_ftb_rank");
            ftbAction.put("rank", tierId);
            actions.add(ftbAction);
        }
        if (EasyVipConfig.integrations.luckpermsEnabled) {
            Map<String, Object> lpAction = new HashMap<>();
            lpAction.put("type", "remove_luckperms_group");
            lpAction.put("group", tierId);
            actions.add(lpAction);
        }
        executeTierActions(uuid, ctx.getOrDefault("player", resolvePlayerName(uuid)), player, actions, ctx, source);
    }

    private static boolean executeTierActions(UUID uuid, String playerName, Player onlinePlayer,
                                              List<Map<String, Object>> actions, Map<String, String> ctx, String source) {
        ActionContext actionContext = (onlinePlayer != null && onlinePlayer.isOnline())
                ? ActionContext.online(onlinePlayer, source)
                : ActionContext.offline(uuid, playerName, source);
        return ActionExecutor.execute(actionContext, actions, ctx);
    }
}

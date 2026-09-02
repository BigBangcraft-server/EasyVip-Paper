package br.com.pedrodalben.easyvip.action;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.platform.EconomyBridge;
import br.com.pedrodalben.easyvip.platform.PermissionBridge;
import br.com.pedrodalben.easyvip.platform.PlatformBridge;
import br.com.pedrodalben.easyvip.platform.TextUtil;
import br.com.pedrodalben.easyvip.service.PackageService;
import br.com.pedrodalben.easyvip.service.VipService;
import br.com.pedrodalben.easyvip.util.CommandAllowlist;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ActionExecutor {

    private static PlatformBridge platform;
    private static EconomyBridge economy;

    private ActionExecutor() {
    }

    public static void setPlatform(PlatformBridge bridge) {
        platform = bridge;
    }

    public static void setEconomy(EconomyBridge bridge) {
        economy = bridge;
    }

    public static boolean execute(Player player, List<Map<String, Object>> actions, Map<String, String> context) {
        return execute(ActionContext.online(player, "bukkit_player"), actions, context);
    }

    public static boolean execute(ActionContext actionContext, List<Map<String, Object>> actions, Map<String, String> context) {
        if (actions == null || actions.isEmpty()) {
            return true;
        }

        Map<String, String> fullContext = new HashMap<>(context != null ? context : Collections.emptyMap());
        fullContext.put("player", actionContext.getPlayerName());
        fullContext.put("player_uuid", actionContext.getPlayerUuid().toString());

        boolean allOk = true;
        for (Map<String, Object> action : actions) {
            try {
                if (!executeSingle(actionContext, action, fullContext)) {
                    allOk = false;
                }
            } catch (Throwable e) {
                System.err.println("[EasyVip] Error executing action of type " + action.get("type")
                        + ": " + e.getClass().getSimpleName());
                allOk = false;
            }
        }
        return allOk;
    }

    /** Executes a package/action chain without making nested package claims synchronous. */
    public static CompletionStage<Boolean> executeAsync(Plugin plugin, Player player,
                                                          List<Map<String, Object>> actions,
                                                          Map<String, String> context) {
        if (player == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (plugin == null) {
            return CompletableFuture.completedFuture(execute(player, actions, context));
        }
        if (actions == null || actions.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        return executeAsyncAt(plugin, player, actions, context, 0, true);
    }

    private static CompletionStage<Boolean> executeAsyncAt(Plugin plugin, Player player,
                                                            List<Map<String, Object>> actions,
                                                            Map<String, String> context, int index,
                                                            boolean allOk) {
        if (index >= actions.size()) {
            return CompletableFuture.completedFuture(allOk);
        }
        Map<String, Object> action = actions.get(index);
        String type = getString(action, "type", "");
        CompletionStage<Boolean> step;
        if ("give_package".equalsIgnoreCase(type)) {
            String packageId = getString(action, "package_id", "");
            step = packageId.isEmpty()
                    ? CompletableFuture.completedFuture(false)
                    : PackageService.givePackageAsync(plugin, player, packageId);
        } else if ("give_permission_flag_internal".equalsIgnoreCase(type)
                || "remove_permission_flag_internal".equalsIgnoreCase(type)) {
            String permission = getString(action, "permission", "");
            boolean value = "give_permission_flag_internal".equalsIgnoreCase(type);
            step = permission.isEmpty() || platform == null
                    ? CompletableFuture.completedFuture(false)
                    : PermissionBridge.setPermissionAsync(player.getUniqueId(), permission, value);
        } else if ("add_luckperms_group".equalsIgnoreCase(type)
                || "remove_luckperms_group".equalsIgnoreCase(type)) {
            String group = getString(action, "group", "");
            boolean value = "add_luckperms_group".equalsIgnoreCase(type);
            step = group.isEmpty()
                    ? CompletableFuture.completedFuture(false)
                    : PermissionBridge.setGroupAsync(player.getUniqueId(), group, value);
        } else {
            step = VipService.runOnServerAsync(plugin, player,
                    () -> execute(player, List.of(action), context));
        }
        return step.handle((success, error) -> error == null && Boolean.TRUE.equals(success))
                .thenCompose(success -> executeAsyncAt(plugin, player, actions, context,
                        index + 1, allOk && success));
    }

    private static boolean executeSingle(ActionContext ctx, Map<String, Object> action, Map<String, String> context) {
        String type = getString(action, "type", "");
        if (type.isEmpty()) return false;

        Player player = ctx.getOnlinePlayer();

        switch (type.toLowerCase(Locale.ROOT)) {
            case "give_item": {
                if (player == null) {
                    return false;
                }
                String itemStr = getString(action, "item", "");
                int amount = getInt(action, "amount", 1);
                Material material = resolveMaterial(itemStr);
                if (material != null && !material.isAir()) {
                    ItemStack stack = new ItemStack(material, Math.max(1, amount));
                    giveItemToPlayer(player, stack);
                    return true;
                }
                break;
            }
            case "give_item_stack": {
                if (player == null) {
                    return false;
                }
                String stackSnbt = getString(action, "stack_snbt", getString(action, "stack", ""));
                if (!stackSnbt.isEmpty()) {
                    ItemStack stack = parseItemStack(stackSnbt);
                    if (stack != null && !stack.getType().isAir()) {
                        giveItemToPlayer(player, stack);
                        return true;
                    }
                }
                break;
            }
            case "give_experience": {
                if (player == null) {
                    return false;
                }
                int amount = getInt(action, "amount", 0);
                if (amount > 0) {
                    player.giveExp(amount);
                    return true;
                }
                break;
            }
            case "give_level": {
                if (player == null) {
                    return false;
                }
                int amount = getInt(action, "amount", 0);
                if (amount > 0) {
                    player.giveExpLevels(amount);
                    return true;
                }
                break;
            }
            case "give_effect": {
                if (player == null) {
                    return false;
                }
                String effectStr = getString(action, "effect", "");
                int durationSeconds = getInt(action, "duration", 30);
                int amplifier = getInt(action, "amplifier", 0);
                PotionEffectType effectType = resolvePotionEffectType(effectStr);
                if (effectType != null) {
                    player.addPotionEffect(new PotionEffect(effectType, durationSeconds * 20, amplifier));
                    return true;
                }
                break;
            }
            case "send_message": {
                String message = getString(action, "message", "");
                if (!message.isEmpty()) {
                    if (player != null) {
                        TextUtil.sendMessage(player, resolvePlaceholders(message, context));
                    }
                    return true;
                }
                break;
            }
            case "broadcast_message": {
                String message = getString(action, "message", "");
                if (!message.isEmpty()) {
                    TextUtil.broadcast(resolvePlaceholders(message, context));
                    return true;
                }
                break;
            }
            case "run_server_command": {
                String command = getString(action, "command", "");
                if (!command.isEmpty()) {
                    String cmd = resolvePlaceholders(command, context);
                    return executeServerCommand(cmd);
                }
                break;
            }
            case "run_player_command": {
                if (player == null) {
                    return false;
                }
                String command = getString(action, "command", "");
                if (!command.isEmpty()) {
                    String cmd = resolvePlaceholders(command, context);
                    return executePlayerCommand(player, cmd);
                }
                break;
            }
            case "give_package": {
                String pkgId = getString(action, "package_id", "");
                if (!pkgId.isEmpty() && player != null) {
                    return PackageService.givePackage(player, pkgId);
                }
                break;
            }
            case "set_scoreboard_tag": {
                if (player == null) {
                    return false;
                }
                String tag = getString(action, "tag", "");
                if (!tag.isEmpty()) {
                    player.addScoreboardTag(tag);
                    return true;
                }
                break;
            }
            case "remove_scoreboard_tag": {
                if (player == null) {
                    return false;
                }
                String tag = getString(action, "tag", "");
                if (!tag.isEmpty()) {
                    player.removeScoreboardTag(tag);
                    return true;
                }
                break;
            }
            case "add_to_team": {
                if (player == null) {
                    return false;
                }
                String teamName = getString(action, "team", "");
                if (!teamName.isEmpty()) {
                    Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
                    Team team = sb.getTeam(teamName);
                    if (team != null) {
                        team.addEntry(player.getName());
                        return true;
                    }
                }
                break;
            }
            case "remove_from_team": {
                if (player == null) {
                    return false;
                }
                String teamName = getString(action, "team", "");
                if (!teamName.isEmpty()) {
                    Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
                    Team team = sb.getTeam(teamName);
                    if (team != null) {
                        team.removeEntry(player.getName());
                        return true;
                    }
                }
                break;
            }
            case "give_permission_flag_internal": {
                if (player == null) {
                    return false;
                }
                String perm = getString(action, "permission", "");
                if (!perm.isEmpty() && platform != null) {
                    platform.setPermissionFlagInternal(player, perm, true);
                    return true;
                }
                break;
            }
            case "remove_permission_flag_internal": {
                if (player == null) {
                    return false;
                }
                String perm = getString(action, "permission", "");
                if (!perm.isEmpty() && platform != null) {
                    platform.setPermissionFlagInternal(player, perm, false);
                    return true;
                }
                break;
            }
            case "custom_event_hook": {
                if (player == null) {
                    return false;
                }
                String hook = getString(action, "hook", "");
                if (!hook.isEmpty() && platform != null) {
                    platform.fireCustomEventHook(player, hook, context);
                    return true;
                }
                break;
            }
            case "run_ftb_rank_command": {
                String command = getString(action, "command", "");
                if (!command.isEmpty()) {
                    String cmd = resolvePlaceholders(command, context);
                    return executeServerCommand(cmd);
                }
                break;
            }
            case "add_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksAddCommand, context, rank);
                    return executeFtbRankCommand(cmd);
                }
                break;
            }
            case "remove_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksRemoveCommand, context, rank);
                    return executeFtbRankCommand(cmd);
                }
                break;
            }
            case "set_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksSetCommand, context, rank);
                    return executeFtbRankCommand(cmd);
                }
                break;
            }
            case "add_luckperms_group": {
                String group = getString(action, "group", "");
                if (!group.isEmpty()) {
                    if (player != null) {
                        PermissionBridge.setGroup(player, group, true);
                    } else {
                        PermissionBridge.setGroup(ctx.getPlayerUuid(), group, true);
                        String playerName = context.get("player");
                        if (playerName != null && !playerName.isEmpty()) {
                            executeServerCommand("lp user " + playerName + " parent add " + group);
                        }
                    }
                    return true;
                }
                break;
            }
            case "remove_luckperms_group": {
                String group = getString(action, "group", "");
                if (!group.isEmpty()) {
                    if (player != null) {
                        PermissionBridge.setGroup(player, group, false);
                    } else {
                        PermissionBridge.setGroup(ctx.getPlayerUuid(), group, false);
                        String playerName = context.get("player");
                        if (playerName != null && !playerName.isEmpty()) {
                            executeServerCommand("lp user " + playerName + " parent remove " + group);
                        }
                    }
                    return true;
                }
                break;
            }
            case "economy_deposit":
            case "economy_give": {
                if (economy != null && player != null) {
                    double amount = getDouble(action, "amount", 0.0);
                    if (amount > 0) {
                        return economy.deposit(player, BigDecimal.valueOf(amount));
                    }
                }
                break;
            }
            case "economy_withdraw":
            case "economy_take": {
                if (economy != null && player != null) {
                    double amount = getDouble(action, "amount", 0.0);
                    if (amount > 0) {
                        return economy.withdraw(player, BigDecimal.valueOf(amount));
                    }
                }
                break;
            }
        }
        return false;
    }

    public static String resolvePlaceholders(String text, Map<String, String> context) {
        if (text == null) return "";
        if (context == null) {
            context = Collections.emptyMap();
        }
        String result = RandomPoolService.resolveRandomPlaceholders(text);
        for (Map.Entry<String, String> entry : context.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("{" + entry.getKey() + "}", value);
            result = result.replace("%" + entry.getKey() + "%", value);
            if (entry.getKey().startsWith("var.")) {
                result = result.replace("$" + entry.getKey().substring(4), value);
            }
        }
        return result.replace('&', '§');
    }

    public static boolean isCommandAllowed(String command) {
        return CommandAllowlist.isAllowed(command, EasyVipConfig.common.commandAllowlistEnabled, EasyVipConfig.common.commandAllowlist);
    }

    public static String sanitizeCommand(String command) {
        return CommandAllowlist.normalize(command);
    }

    private static boolean executeServerCommand(String cmd) {
        String normalized = sanitizeCommand(cmd);
        if (normalized == null) {
            return false;
        }
        if (!isCommandAllowed(normalized)) {
            System.err.println("[EasyVip] Command execution blocked by security allowlist: " + commandName(normalized));
            return false;
        }
        try {
            if (Bukkit.getServer() == null) return false;
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), normalized);
        } catch (Throwable exception) {
            System.err.println("[EasyVip] Command execution failed: " + commandName(normalized)
                    + " (" + exception.getClass().getSimpleName() + ")");
            return false;
        }
    }


    private static boolean executePlayerCommand(Player player, String cmd) {
        String normalized = sanitizeCommand(cmd);
        if (normalized == null) {
            System.err.println("[EasyVip] Player command blocked by security normalization");
            return false;
        }
        if (!isCommandAllowed(normalized)) {
            System.err.println("[EasyVip] Player command blocked by security allowlist: " + commandName(normalized));
            return false;
        }
        return player.performCommand(normalized);
    }

    private static String commandName(String normalized) {
        int separator = normalized.indexOf(' ');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private static boolean executeFtbRankCommand(String cmd) {
        if (!EasyVipConfig.integrations.ftbRanksEnabled) {
            return false;
        }
        return executeServerCommand(cmd);
    }

    private static String renderFtbRankCommand(String template, Map<String, String> context, String rank) {
        Map<String, String> full = new HashMap<>(context != null ? context : Collections.emptyMap());
        full.put("rank", rank);
        return resolvePlaceholders(template, full);
    }

    private static void giveItemToPlayer(Player player, ItemStack stack) {
        if (player == null || stack == null || stack.getType().isAir()) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            for (ItemStack dropped : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
            }
        }
    }

    private static Material resolveMaterial(String itemStr) {
        if (itemStr == null || itemStr.isBlank()) {
            return null;
        }
        String clean = itemStr.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        Material mat = Material.matchMaterial(clean);
        if (mat == null) {
            mat = Material.matchMaterial(clean.toUpperCase(Locale.ROOT));
        }
        return mat;
    }

    private static PotionEffectType resolvePotionEffectType(String effectStr) {
        if (effectStr == null || effectStr.isBlank()) {
            return null;
        }
        String clean = effectStr.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        NamespacedKey key = NamespacedKey.minecraft(clean);
        PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(key);
        if (type != null) {
            return type;
        }
        return PotionEffectType.getByName(clean.toUpperCase(Locale.ROOT));
    }

    private static ItemStack parseItemStack(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return Bukkit.getItemFactory().createItemStack(input.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val != null) {
            try {
                return Integer.parseInt(val.toString());
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return def;
    }

    private static double getDouble(Map<String, Object> map, String key, double def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val != null) {
            try {
                return Double.parseDouble(val.toString());
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return def;
    }
}

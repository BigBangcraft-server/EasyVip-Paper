package br.com.pedrodalben.easyvip.command;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.AuditLogRecord;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.model.PendingVariantSelection;
import br.com.pedrodalben.easyvip.model.PlayerVipRecord;
import br.com.pedrodalben.easyvip.model.PlayerVipRegistry;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.platform.PermissionBridge;
import br.com.pedrodalben.easyvip.platform.TextUtil;
import br.com.pedrodalben.easyvip.service.ExpirationService;
import br.com.pedrodalben.easyvip.service.KeyService;
import br.com.pedrodalben.easyvip.service.PackageService;
import br.com.pedrodalben.easyvip.service.VipService;
import br.com.pedrodalben.easyvip.util.DurationParser;
import br.com.pedrodalben.easyvip.util.KeySecurity;
import br.com.pedrodalben.easyvip.webstore.WebStoreFulfillmentService;
import br.com.pedrodalben.easyvip.webstore.WebStoreSyncService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class EasyVipCommandHandler implements CommandExecutor, TabCompleter {

    private final Plugin plugin;

    public EasyVipCommandHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] rawArgs) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        if (cmdName.equals("usekey") || cmdName.equals("activate")) {
            if (!checkPermission(sender, "easyvip.use")) return true;
            if (rawArgs.length < 1) {
                TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /" + label + " <key>", "Uso: /" + label + " <chave>"));
                return true;
            }
            return handleUseKey(sender, rawArgs[0]);
        }

        if (cmdName.equals("vip")) {
            if (!checkPermission(sender, "easyvip.use")) return true;
            if (rawArgs.length == 0) {
                return executeHelp(sender);
            }
            return handleUseKey(sender, rawArgs[0]);
        }

        if (cmdName.equals("viptime")) {
            if (!checkPermission(sender, "easyvip.use")) return true;
            String target = rawArgs.length > 0 ? rawArgs[0] : null;
            return handleInfo(sender, target);
        }

        if (cmdName.equals("link")) {
            if (!checkPermission(sender, "easyvip.use")) return true;
            return executeLink(sender);
        }

        // /easyvip
        List<String> args = parseArguments(rawArgs);
        if (args.isEmpty()) {
            if (!checkPermission(sender, "easyvip.use")) return true;
            return executeHelp(sender);
        }

        String sub = args.get(0).toLowerCase(Locale.ROOT);

        switch (sub) {
            case "help":
                if (!checkPermission(sender, "easyvip.use")) return true;
                return executeHelp(sender);

            case "use":
            case "activate":
                if (!checkPermission(sender, "easyvip.use")) return true;
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip use <key>", "Uso: /easyvip use <chave>"));
                    return true;
                }
                return handleUseKey(sender, args.get(1));

            case "confirm":
                if (!checkPermission(sender, "easyvip.use")) return true;
                return handleConfirmKey(sender);

            case "info":
            case "time":
                if (!checkPermission(sender, "easyvip.use")) return true;
                return handleInfo(sender, args.size() > 1 ? args.get(1) : null);

            case "select":
                if (!checkPermission(sender, "easyvip.use")) return true;
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip select <tier>", "Uso: /easyvip select <tier>"));
                    return true;
                }
                return handleSelectVip(sender, args.get(1));

            case "variant":
                if (!checkPermission(sender, "easyvip.use")) return true;
                return handleVariant(sender, args.subList(1, args.size()));

            case "reload":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                return handleConfigReload(sender);

            case "createvip":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                return handleCreateVip(sender, args.subList(1, args.size()));

            case "savevipactivation":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip savevipactivation <tier>", "Uso: /easyvip savevipactivation <tier>"));
                    return true;
                }
                return handleSaveVipActivation(sender, args.get(1));

            case "active":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                if (args.size() >= 4 && args.get(1).equalsIgnoreCase("set")) {
                    return handleActiveSet(sender, args.get(2), args.get(3));
                }
                TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip active set <player> <tier>", "Uso: /easyvip active set <player> <tier>"));
                return true;

            case "config":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                if (args.size() > 1 && args.get(1).equalsIgnoreCase("validate")) {
                    return handleConfigValidate(sender);
                }
                if (args.size() > 1 && args.get(1).equalsIgnoreCase("reload")) {
                    return handleConfigReload(sender);
                }
                TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip config reload|validate", "Uso: /easyvip config reload|validate"));
                return true;

            case "key":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                return handleKey(sender, args.subList(1, args.size()));

            case "package":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                return handlePackage(sender, args.subList(1, args.size()));

            case "admin":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                return handleAdmin(sender, args.subList(1, args.size()));

            case "network":
                if (!checkPermission(sender, "easyvip.admin")) return true;
                return handleNetworkDiagnostics(sender, args.subList(1, args.size()));

            default:
                TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Unknown subcommand. Use /easyvip for help.", "Subcomando desconhecido. Use /easyvip para ajuda."));
                return true;
        }
    }

    private boolean checkPermission(CommandSender sender, String node) {
        if (!PermissionBridge.hasPermission(sender, node)) {
            TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.noPermission);
            return false;
        }
        return true;
    }

    private boolean executeHelp(CommandSender sender) {
        TextUtil.sendMessage(sender, "§7[§eEasyVip§7] " + EasyVipConfig.localized("§eAvailable commands:", "§eComandos disponíveis:"));
        TextUtil.sendMessage(sender, "§7- §f/easyvip use <key> §8- §7" + EasyVipConfig.localized("redeem a key", "resgatar uma chave"));
        TextUtil.sendMessage(sender, "§7- §f/easyvip confirm §8- §7" + EasyVipConfig.localized("confirm key redemption", "confirmar o uso de uma chave"));
        TextUtil.sendMessage(sender, "§7- §f/easyvip info [player] §8- §7" + EasyVipConfig.localized("view VIP times", "ver tempos de VIP"));
        TextUtil.sendMessage(sender, "§7- §f/easyvip select <tier> §8- §7" + EasyVipConfig.localized("set active VIP", "definir VIP ativo"));
        TextUtil.sendMessage(sender, "§7- §f/easyvip variant choose <package> <variant> §8- §7" + EasyVipConfig.localized("choose a variant", "escolher variante"));
        TextUtil.sendMessage(sender, "§7- §f/easyvip variant pending [player] §8- §7" + EasyVipConfig.localized("view pending variants", "ver variantes pendentes"));
        TextUtil.sendMessage(sender, "§7- §f/easyvip time [player] §8- §7" + EasyVipConfig.localized("alias for info", "alias de info"));

        if (PermissionBridge.hasPermission(sender, "easyvip.admin")) {
            TextUtil.sendMessage(sender, "§7- §f/easyvip admin ... §8- §7" + EasyVipConfig.localized("administrative commands", "comandos administrativos"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip admin webstore status §8- §7" + EasyVipConfig.localized("fulfillment state", "estado do fulfillment"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip network status §8- §7" + EasyVipConfig.localized("DB/Redis/cache/delivery health", "saúde de DB/Redis/cache/delivery"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip admin network status §8- §7" + EasyVipConfig.localized("DB/Redis/cache/delivery health", "saúde de DB/Redis/cache/delivery"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip createvip <id> <display_name> [color] §8- §7" + EasyVipConfig.localized("create a new VIP definition", "criar uma nova definição de VIP"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip key ... §8- §7" + EasyVipConfig.localized("manage keys", "gerenciar chaves"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip package ... §8- §7" + EasyVipConfig.localized("manage packages", "gerenciar pacotes"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip active set <player> <tier> §8- §7" + EasyVipConfig.localized("change active VIP", "alterar VIP ativo"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip savevipactivation <tier> §8- §7" + EasyVipConfig.localized("save the current inventory as VIP activation items", "salvar o inventário atual como itens de ativação do VIP"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip reload §8- §7" + EasyVipConfig.localized("reload TOML configs without restarting", "recarregar os TOMLs sem reiniciar"));
            TextUtil.sendMessage(sender, "§7- §f/easyvip config reload|validate §8- §7" + EasyVipConfig.localized("reload or validate config", "recarregar/validar config"));
        }
        return true;
    }

    private boolean handleUseKey(CommandSender sender, String key) {
        if (!(sender instanceof Player player)) {
            TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
            return true;
        }

        KeyService.RedeemResult result = KeyService.redeemKey(player, key, false);
        sendRedeemFeedback(player, result, key);
        return true;
    }

    private boolean handleConfirmKey(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
            return true;
        }

        KeyService.RedeemResult result = KeyService.confirmPending(player);
        sendRedeemFeedback(player, result, "");
        return true;
    }

    public static void sendRedeemFeedback(Player player, KeyService.RedeemResult result, String key) {
        String msg;
        switch (result) {
            case SUCCESS:
                KeyRecord successRecord = PersistenceManager.getKey(key.trim().toUpperCase(Locale.ROOT));
                if (successRecord == null) successRecord = PersistenceManager.getKey(key.trim());
                if (successRecord != null && "reward".equalsIgnoreCase(successRecord.getType())) {
                    var rewardDef = EasyVipConfig.rewardKeys.list.get(successRecord.getRewardKeyId());
                    Map<String, String> rewardContext = new HashMap<>();
                    rewardContext.put("reward_display", rewardDef != null ? rewardDef.displayName : successRecord.getRewardKeyId());
                    msg = EasyVipConfig.messages.prefix + ActionExecutor.resolvePlaceholders(
                            EasyVipConfig.messages.rewardGiven, rewardContext);
                } else {
                    msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyConfirmed;
                }
                break;
            case INVALID_KEY:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidKey;
                break;
            case EXPIRED:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyExpired;
                break;
            case NO_USES_LEFT:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyNoUsesLeft;
                break;
            case ON_COOLDOWN:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.localized("&cPlease wait a moment before using another key.", "&cAguarde um momento antes de usar outra chave.");
                break;
            case ALREADY_USED:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyAlreadyUsed;
                break;
            case BOUND_TO_OTHER:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyBoundToOtherPlayer;
                break;
            case CONFIRMATION_REQUIRED: {
                KeyRecord rec = PersistenceManager.getKey(key.trim().toUpperCase(Locale.ROOT));
                if (rec == null) rec = PersistenceManager.getKey(key.trim());
                String tierDisplay = "";
                String duration = "";
                String rewardDisplay = "";
                boolean benefitKey = false;
                if (rec != null) {
                    if (rec.getType().equalsIgnoreCase("vip")) {
                        var tierDef = EasyVipConfig.tiers.list.get(rec.getTierId());
                        tierDisplay = (tierDef != null) ? tierDef.displayName : rec.getTierId();
                        duration = rec.getDuration();
                    } else if (rec.getType().equalsIgnoreCase("custom")) {
                        rewardDisplay = EasyVipConfig.localized("Custom Reward", "Recompensa Personalizada");
                        benefitKey = true;
                    } else {
                        var rkDef = EasyVipConfig.rewardKeys.list.get(rec.getRewardKeyId());
                        rewardDisplay = (rkDef != null) ? rkDef.displayName : (rec.getRewardKeyId() != null ? rec.getRewardKeyId() : EasyVipConfig.localized("Reward", "Recompensa"));
                        benefitKey = true;
                    }
                }
                Map<String, String> context = new HashMap<>();
                context.put("tier_display", tierDisplay);
                context.put("duration", duration);
                context.put("reward_display", rewardDisplay);
                String template = benefitKey
                        ? EasyVipConfig.messages.keyRewardConfirmRequired
                        : EasyVipConfig.messages.keyConfirmRequired;
                msg = EasyVipConfig.messages.prefix + template;
                TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(msg, context));
                return;
            }
            default:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.localized("&cError processing key.", "&cErro ao processar chave.");
                break;
        }
        TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(msg, new HashMap<>()));
    }

    private boolean handleInfo(CommandSender sender, String targetPlayerName) {
        UUID uuid;
        String name;

        if (targetPlayerName != null && !targetPlayerName.isBlank()) {
            if (!PermissionBridge.hasPermission(sender, "easyvip.admin")) {
                TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.noPermission);
                return true;
            }
            Player target = Bukkit.getPlayer(targetPlayerName);
            if (target != null) {
                uuid = target.getUniqueId();
                name = target.getName();
            } else {
                OfflinePlayer off = Bukkit.getOfflinePlayer(targetPlayerName);
                uuid = off.getUniqueId();
                name = off.getName() != null ? off.getName() : targetPlayerName;
            }
        } else {
            if (!(sender instanceof Player player)) {
                TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
                return true;
            }
            uuid = player.getUniqueId();
            name = player.getName();
        }

        if (plugin != null) {
            PersistenceManager.getPlayerVipsAsync(uuid).whenComplete((registry, error) -> {
                if (error != null) {
                    sendNetworkMessage(sender, "§c" + EasyVipConfig.localized(
                            "Unable to load VIP data.", "Não foi possível carregar os dados de VIP."));
                    return;
                }
                runOnServer(() -> sendInfo(sender, name, registry));
            });
            return true;
        }

        sendInfo(sender, name, PersistenceManager.getPlayerVips(uuid));
        return true;
    }

    private void sendInfo(CommandSender sender, String name, PlayerVipRegistry registry) {
        if (registry == null || registry.getVips().isEmpty()) {
            TextUtil.sendMessage(sender, "§7[EasyVip] " + name + " " + EasyVipConfig.localized("has no registered VIPs.", "não possui nenhum VIP registrado."));
            return;
        }

        TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.vipTimeHeader, new HashMap<>()));
        for (PlayerVipRecord record : registry.getVips().values()) {
            EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(record.getTierId());
            String display = (def != null) ? def.displayName : record.getTierId();
            String remaining;

            if (record.isExpired()) {
                remaining = EasyVipConfig.localized("expired", "expirado");
            } else if (record.getExpiryTime() == -1) {
                remaining = EasyVipConfig.localized("permanent", "permanente");
            } else {
                remaining = formatTimeLeft(record.getExpiryTime() - System.currentTimeMillis());
            }

            Map<String, String> context = new HashMap<>();
            context.put("tier_display", display);
            context.put("duration_left", remaining);

            String status = record.isActive()
                    ? " §a[" + EasyVipConfig.localized("Active", "Ativo") + "]"
                    : " §7[" + EasyVipConfig.localized("Inactive", "Inativo") + "]";
            TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.vipTimeLine, context) + status);
        }
    }

    private String formatTimeLeft(long diff) {
        long seconds = diff / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 24L / 60L; // wait, let's fix standard hours
        long totalHours = seconds / 3600L;
        long days = totalHours / 24L;
        long remHours = totalHours % 24L;
        long remMinutes = (seconds % 3600L) / 60L;
        long remSeconds = seconds % 60L;

        if (days > 0) {
            return days + "d " + remHours + "h";
        }
        if (remHours > 0) {
            return remHours + "h " + remMinutes + "m";
        }
        if (remMinutes > 0) {
            return remMinutes + "m " + remSeconds + "s";
        }
        return remSeconds + "s";
    }

    private boolean handleSelectVip(CommandSender sender, String tier) {
        if (!(sender instanceof Player player)) {
            TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
            return true;
        }

        boolean success = VipService.setActiveVip(player.getUniqueId(), tier, player.getName());
        if (success) {
            EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(tier);
            String display = (def != null) ? def.displayName : tier;
            Map<String, String> context = new HashMap<>();
            context.put("tier_display", display);
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.activeVipChanged, context));
        } else {
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.activeVipNotOwned, new HashMap<>()));
        }
        return true;
    }

    private boolean handleVariant(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip variant <choose|pending|clear>", "Uso: /easyvip variant <choose|pending|clear>"));
            return true;
        }

        String sub = args.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "choose":
                if (!(sender instanceof Player player)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
                    return true;
                }
                if (args.size() < 3) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip variant choose <package> <variant>", "Uso: /easyvip variant choose <pacote> <variante>"));
                    return true;
                }
                PackageService.chooseVariant(player, args.get(1), args.get(2));
                return true;

            case "pending": {
                UUID uuid;
                String name;
                if (args.size() > 1) {
                    if (!PermissionBridge.hasPermission(sender, "easyvip.admin")) {
                        TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.noPermission);
                        return true;
                    }
                    Player p = Bukkit.getPlayer(args.get(1));
                    if (p != null) {
                        uuid = p.getUniqueId();
                        name = p.getName();
                    } else {
                        OfflinePlayer off = Bukkit.getOfflinePlayer(args.get(1));
                        uuid = off.getUniqueId();
                        name = off.getName() != null ? off.getName() : args.get(1);
                    }
                } else {
                    if (!(sender instanceof Player player)) {
                        TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
                        return true;
                    }
                    uuid = player.getUniqueId();
                    name = player.getName();
                }

                PackageService.cleanupExpiredPendingVariants(uuid);
                List<PendingVariantSelection> pending = PersistenceManager.getPendingVariants(uuid);
                if (pending.isEmpty()) {
                    TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §7" + EasyVipConfig.localized("No pending variants for ", "Sem variantes pendentes para ") + name);
                    return true;
                }

                TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §e" + EasyVipConfig.localized("Pending variants for ", "Variantes pendentes de ") + name + ": " + pending.size());
                for (PendingVariantSelection sel : pending) {
                    TextUtil.sendMessage(sender, "§7- §f" + sel.getPackageId() + " §8| §7" + String.join(", ", sel.getVariants()));
                }
                return true;
            }

            case "clear": {
                if (!PermissionBridge.hasPermission(sender, "easyvip.admin")) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.noPermission);
                    return true;
                }
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip variant clear <player> [package_id]", "Uso: /easyvip variant clear <player> [package_id]"));
                    return true;
                }
                String targetName = args.get(1);
                Player p = Bukkit.getPlayer(targetName);
                UUID uuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

                List<PendingVariantSelection> pending = PersistenceManager.getPendingVariants(uuid);
                if (pending.isEmpty()) {
                    TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §7" + EasyVipConfig.localized("No pending variant found.", "Nenhuma variante pendente encontrada."));
                    return true;
                }

                String packageId = args.size() > 2 ? args.get(2) : null;
                if (packageId == null) {
                    for (PendingVariantSelection sel : new ArrayList<>(pending)) {
                        PersistenceManager.removePendingVariant(uuid, sel.getPackageId());
                    }
                } else {
                    PersistenceManager.removePendingVariant(uuid, packageId);
                }

                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Variant pending entries removed successfully.", "Pendências de variante removidas com sucesso."));
                return true;
            }
        }
        return true;
    }

    private boolean handleCreateVip(CommandSender sender, List<String> args) {
        if (args.size() < 2) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip createvip <id> <display_name> [color]", "Uso: /easyvip createvip <id> <nome_exibição> [cor]"));
            return true;
        }

        String rawId = args.get(0).trim();
        if (rawId.isEmpty()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Invalid VIP ID.", "ID de VIP inválido."));
            return true;
        }

        String id = rawId.toLowerCase(Locale.ROOT);
        if (EasyVipConfig.tiers.list.containsKey(id)) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("This VIP already exists.", "Este VIP já existe."));
            return true;
        }

        String displayName = args.get(1).trim();
        String color = args.size() > 2 ? args.get(2).trim() : "white";

        EasyVipConfig.VipTierDefinition def = new EasyVipConfig.VipTierDefinition();
        def.id = id;
        def.displayName = displayName;
        def.color = color;
        def.priority = nextVipPriority();
        def.defaultDuration = EasyVipConfig.tiers.defaults.duration;
        def.allowStacking = EasyVipConfig.tiers.defaults.stacking;
        def.activationMode = EasyVipConfig.tiers.defaults.activationMode;
        def.messages.activated = EasyVipConfig.tiers.defaults.messages.activated;
        def.messages.expired = EasyVipConfig.tiers.defaults.messages.expired;
        def.messages.rareItemBroadcast = EasyVipConfig.tiers.defaults.messages.rareItemBroadcast;
        def.commands.activate = new ArrayList<>(EasyVipConfig.tiers.defaults.commands.activate);
        def.commands.expire = new ArrayList<>(EasyVipConfig.tiers.defaults.commands.expire);

        EasyVipConfig.tiers.list.put(id, def);
        try {
            EasyVipConfig.saveTiers();
        } catch (IOException e) {
            Map<String, String> context = new HashMap<>();
            context.put("error", e.getClass().getSimpleName());
            TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.reloadError, context));
            return true;
        }

        if (PermissionBridge.isLuckPermsPresent() && EasyVipConfig.integrations.luckpermsEnabled) {
            PermissionBridge.createGroup(id);
        }

        Map<String, String> context = new HashMap<>();
        context.put("tier_display", def.displayName);
        context.put("tier_id", def.id);
        TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(
                EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                        "VIP {tier_display} created successfully.",
                        "VIP {tier_display} criado com sucesso."
                ), context));
        return true;
    }

    private int nextVipPriority() {
        int max = 0;
        for (EasyVipConfig.VipTierDefinition tier : EasyVipConfig.tiers.list.values()) {
            if (tier != null && tier.priority > max) {
                max = tier.priority;
            }
        }
        return max > 0 ? max + 10 : 10;
    }

    private boolean handleActiveSet(CommandSender sender, String playerName, String tier) {
        Player player = Bukkit.getPlayer(playerName);
        UUID uuid = player != null ? player.getUniqueId() : Bukkit.getOfflinePlayer(playerName).getUniqueId();

        boolean success = VipService.setActiveVip(uuid, tier, sender.getName());
        if (success) {
            TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Active VIP changed successfully.", "VIP ativo alterado com sucesso."));
        } else {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Could not change the active VIP.", "Não foi possível alterar o VIP ativo."));
        }
        return true;
    }

    private boolean handleSaveVipActivation(CommandSender sender, String tierId) {
        if (!(sender instanceof Player player)) {
            TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
            return true;
        }

        EasyVipConfig.VipTierDefinition tier = EasyVipConfig.tiers.list.get(tierId);
        if (tier == null) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.messages.invalidTier);
            return true;
        }

        List<EasyVipConfig.VipActivationItemDefinition> items = captureVipActivationItems(player);
        if (items.isEmpty()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Your inventory is empty.", "Seu inventário está vazio."));
            return true;
        }

        tier.activationItems.clear();
        tier.activationItems.addAll(items);

        try {
            EasyVipConfig.saveActivationItems(tier.id);
        } catch (IOException e) {
            Map<String, String> context = new HashMap<>();
            context.put("error", e.getClass().getSimpleName());
            TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.reloadError, context));
            return true;
        }

        Map<String, String> context = new HashMap<>();
        context.put("tier_display", tier.displayName != null ? tier.displayName : tier.id);
        context.put("tier_id", tier.id);
        context.put("items", String.valueOf(tier.activationItems.size()));
        TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(
                EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                        "Saved {items} item(s) into activation_items/{tier_id}.toml.",
                        "Salvei {items} item(ns) em activation_items/{tier_id}.toml."
                ), context));
        return true;
    }

    private List<EasyVipConfig.VipActivationItemDefinition> captureVipActivationItems(Player player) {
        List<EasyVipConfig.VipActivationItemDefinition> items = new ArrayList<>();
        captureStacks(items, player.getInventory().getStorageContents());
        captureStacks(items, player.getInventory().getArmorContents());
        captureStacks(items, player.getInventory().getExtraContents());
        return items;
    }

    private void captureStacks(List<EasyVipConfig.VipActivationItemDefinition> items, ItemStack[] stacks) {
        if (stacks == null) return;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) continue;

            EasyVipConfig.VipActivationItemDefinition item = new EasyVipConfig.VipActivationItemDefinition();
            item.itemId = stack.getType().getKey().toString();
            item.amount = stack.getAmount();
            item.chance = 100.0d;

            if (stack.hasItemMeta()) {
                ItemMeta meta = stack.getItemMeta();
                if (meta.hasEnchants()) {
                    for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                        item.enchants.put(entry.getKey().getKey().toString(), entry.getValue());
                    }
                }
                String comp = meta.getAsComponentString();
                if (comp != null && !comp.isEmpty()) {
                    item.stackSnbt = item.itemId + comp;
                }
            }

            items.add(item);
        }
    }

    private boolean handleConfigReload(CommandSender sender) {
        try {
            EasyVipConfig.loadAll();
            ExpirationService.reload(plugin);
            WebStoreFulfillmentService.reload(plugin.getDataFolder().toPath());
            TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.reloadSuccess, new HashMap<>()));
            return true;
        } catch (Exception e) {
            Map<String, String> context = new HashMap<>();
            context.put("error", e.getClass().getSimpleName());
            TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.reloadError, context));
            return true;
        }
    }

    private boolean handleConfigValidate(CommandSender sender) {
        List<String> errors = EasyVipConfig.validate();
        if (errors.isEmpty()) {
            TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §a" + EasyVipConfig.localized("All configuration settings are valid!", "Todas as configurações são válidas!"));
        } else {
            TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §c" + EasyVipConfig.localized("Configuration errors found:", "Erros de configuração encontrados:"));
            for (String error : errors) {
                TextUtil.sendMessage(sender, "§7- §c" + error);
            }
        }
        return true;
    }

    private boolean handleKey(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip key <list|info|delete|cleanup>", "Uso: /easyvip key <list|info|delete|cleanup>"));
            return true;
        }

        String sub = args.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list": {
                List<KeyRecord> keys = PersistenceManager.getAllKeys();
                TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §e" + EasyVipConfig.localized("Registered keys: ", "Keys cadastradas: ") + "§f" + keys.size());
                for (KeyRecord key : keys) {
                    String displayCode = KeySecurity.maskKey(key.getCode());
                    TextUtil.sendMessage(sender, "§7- §f" + displayCode + " §8| §e" + key.getType() + " §8| §7" + EasyVipConfig.localized("uses", "usos") + " " + key.getUsedCount() + "/" + key.getMaxUses());
                }
                return true;
            }
            case "info": {
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip key info <code> [reveal]", "Uso: /easyvip key info <codigo> [reveal]"));
                    return true;
                }
                String code = args.get(1).trim();
                boolean reveal = args.size() > 2 && args.get(2).equalsIgnoreCase("reveal");
                KeyRecord key = PersistenceManager.getKey(code);
                if (key == null && !EasyVipConfig.common.caseSensitiveKeys) {
                    key = PersistenceManager.getKey(code.toUpperCase(Locale.ROOT));
                }
                if (key == null) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Key not found.", "Chave não encontrada."));
                    return true;
                }

                String displayCode = reveal ? key.getCode() : KeySecurity.maskKey(key.getCode());
                if (reveal) {
                    PersistenceManager.log(sender.getName(), "key_info_reveal", "Key info requested for " + KeySecurity.describeKeyForLog(key.getCode()));
                }

                TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §a" + displayCode
                        + " §8| §7" + KeySecurity.describeKeyForLog(key.getCode())
                        + " §8| §f" + key.getType()
                        + " §8| §f" + EasyVipConfig.localized("used", "usado") + " " + key.getUsedCount() + "/" + key.getMaxUses());
                return true;
            }
            case "delete": {
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip key delete <code>", "Uso: /easyvip key delete <codigo>"));
                    return true;
                }
                String code = args.get(1).trim();
                KeyRecord key = PersistenceManager.getKey(code);
                if (key == null && !EasyVipConfig.common.caseSensitiveKeys) {
                    key = PersistenceManager.getKey(code.toUpperCase(Locale.ROOT));
                }
                if (key == null) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Key not found.", "Chave não encontrada."));
                    return true;
                }
                PersistenceManager.removeKey(key.getCode());
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Key removed.", "Chave removida."));
                return true;
            }
            case "cleanup": {
                List<KeyRecord> allKeys = PersistenceManager.getAllKeys();
                int removed = 0;
                for (KeyRecord key : allKeys) {
                    if (key.getUsedCount() <= 0) {
                        PersistenceManager.removeKey(key.getCode());
                        removed++;
                    }
                }
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Cleanup complete. Removed ", "Limpeza concluída. Removidas ") + "§e" + removed + " §a" + EasyVipConfig.localized("unused key(s).", "chave(s) não utilizadas."));
                return true;
            }
        }
        return true;
    }

    private boolean handlePackage(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip package <list|info>", "Uso: /easyvip package <list|info>"));
            return true;
        }

        String sub = args.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list": {
                TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §e" + EasyVipConfig.localized("Registered packages: ", "Pacotes cadastrados: ") + "§f" + EasyVipConfig.packages.list.size());
                for (EasyVipConfig.PackageDefinition pkg : EasyVipConfig.packages.list.values()) {
                    TextUtil.sendMessage(sender, "§7- §f" + pkg.id + " §8| §e" + pkg.displayName);
                }
                return true;
            }
            case "info": {
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip package info <id>", "Uso: /easyvip package info <id>"));
                    return true;
                }
                String id = args.get(1);
                EasyVipConfig.PackageDefinition pkg = EasyVipConfig.packages.list.get(id);
                if (pkg == null) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Package not found.", "Pacote não encontrado."));
                    return true;
                }
                TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §a" + pkg.id + " §8| §f" + pkg.displayName
                        + " §8| §7" + EasyVipConfig.localized("variants", "variantes") + " " + pkg.variants.size());
                return true;
            }
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin <addvip|addfakevip|removevip|generate|givepackage|giveitemkey|audit|webstore|network>", "Uso: /easyvip admin <...>"));
            return true;
        }

        String sub = args.get(0).toLowerCase(Locale.ROOT);

        switch (sub) {
            case "network":
                return handleNetworkDiagnostics(sender, args.subList(1, args.size()));

            case "addvip": {
                if (args.size() < 4) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin addvip <player> <tier> <duration>", "Uso: /easyvip admin addvip <player> <tier> <duração>"));
                    return true;
                }
                String targetName = args.get(1);
                String tier = args.get(2);
                String duration = args.get(3);

                Player p = Bukkit.getPlayer(targetName);
                UUID uuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

                if (!EasyVipConfig.tiers.list.containsKey(tier)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidTier);
                    return true;
                }

                long dur = DurationParser.parseDurationMillis(duration);
                if (dur == 0 || (dur < 0 && dur != -1)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidDuration);
                    return true;
                }

                boolean success = VipService.addVip(uuid, targetName, tier, duration, sender.getName(), false);
                if (success) {
                    EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(tier);
                    String display = (def != null) ? def.displayName : tier;
                    Map<String, String> context = new HashMap<>();
                    context.put("tier_display", display);
                    context.put("player", targetName);
                    context.put("duration", duration);
                    TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipSet, context));
                } else {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized(
                            "Error adding VIP. Check that the tier exists or that stacking rules do not block the operation.",
                            "Erro ao adicionar VIP. Verifique se o tier existe ou se as regras de stacking bloqueiam a operação."
                    ));
                }
                return true;
            }

            case "addfakevip": {
                if (args.size() < 4) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin addfakevip <fake_player> <tier> <duration>", "Uso: /easyvip admin addfakevip <fake_player> <tier> <duração>"));
                    return true;
                }
                String playerName = args.get(1);
                String tier = args.get(2);
                String duration = args.get(3);

                if (!EasyVipConfig.tiers.list.containsKey(tier)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidTier);
                    return true;
                }

                long parsedDuration = DurationParser.parseDurationMillis(duration);
                if (parsedDuration == 0 || (parsedDuration < 0 && parsedDuration != -1)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidDuration);
                    return true;
                }

                boolean success = VipService.addFakePlayerVip(playerName, tier, duration, sender.getName());
                if (success) {
                    EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(tier);
                    Map<String, String> context = new HashMap<>();
                    context.put("tier_display", def != null ? def.displayName : tier);
                    context.put("player", playerName);
                    context.put("duration", duration);
                    TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipSet, context));
                } else {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized(
                            "Error adding VIP. Check that the tier exists or that stacking rules do not block the operation.",
                            "Erro ao adicionar VIP. Verifique se o tier existe ou se as regras de stacking bloqueiam a operação."
                    ));
                }
                return true;
            }

            case "removevip": {
                if (args.size() < 3) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin removevip <player> <tier>", "Uso: /easyvip admin removevip <player> <tier>"));
                    return true;
                }
                String targetName = args.get(1);
                String tier = args.get(2);

                Player p = Bukkit.getPlayer(targetName);
                UUID uuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

                if (!EasyVipConfig.tiers.list.containsKey(tier)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidTier);
                    return true;
                }

                boolean success = VipService.removeVip(uuid, tier, sender.getName());
                if (success) {
                    EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(tier);
                    Map<String, String> context = new HashMap<>();
                    context.put("tier_display", def != null ? def.displayName : tier);
                    context.put("player", targetName);
                    TextUtil.sendMessage(sender, ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipRemoved, context));
                } else {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("The player does not have this active VIP tier.", "O jogador não possui este tier VIP ativo."));
                }
                return true;
            }

            case "generate":
                return handleGenerate(sender, args.subList(1, args.size()));

            case "givepackage": {
                if (args.size() < 3) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin givepackage <player> <package_id>", "Uso: /easyvip admin givepackage <player> <package_id>"));
                    return true;
                }
                Player p = Bukkit.getPlayer(args.get(1));
                if (p == null) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("The player must be online to receive the package.", "O jogador precisa estar online para receber o pacote."));
                    return true;
                }
                String pkgId = args.get(2);
                PackageService.givePackage(p, pkgId);
                return true;
            }

            case "giveitemkey": {
                if (args.size() < 3) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin giveitemkey <player> <code>", "Uso: /easyvip admin giveitemkey <player> <code>"));
                    return true;
                }
                Player p = Bukkit.getPlayer(args.get(1));
                if (p == null) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("The player must be online to receive the item.", "O jogador precisa estar online para receber o item."));
                    return true;
                }
                String code = args.get(2);
                KeyRecord record = PersistenceManager.getKey(code.trim().toUpperCase(Locale.ROOT));
                if (record == null && !EasyVipConfig.common.caseSensitiveKeys) {
                    record = PersistenceManager.getKey(code.trim());
                }
                if (record == null) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Key not found.", "Chave não encontrada."));
                    return true;
                }

                p.getInventory().addItem(KeyService.createPhysicalKeyItem(record.getCode()));
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Key item delivered successfully.", "Item de chave entregue com segurança."));
                return true;
            }

            case "audit": {
                int page = 1;
                if (args.size() > 1) {
                    try {
                        page = Math.max(1, Integer.parseInt(args.get(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                List<AuditLogRecord> logs = PersistenceManager.getAuditLogs();
                if (logs.isEmpty()) {
                    TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §7" + EasyVipConfig.localized("No audit log entries found.", "Nenhum log de auditoria encontrado."));
                    return true;
                }

                int perPage = 10;
                List<AuditLogRecord> ordered = new ArrayList<>(logs);
                Collections.reverse(ordered);

                int totalPages = Math.max(1, (int) Math.ceil(ordered.size() / (double) perPage));
                int currentPage = Math.min(page, totalPages);
                int fromIndex = (currentPage - 1) * perPage;
                int toIndex = Math.min(fromIndex + perPage, ordered.size());

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
                TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §e" + EasyVipConfig.localized("Audit log", "Audit log") + " §7(" + EasyVipConfig.localized("page", "página") + " " + currentPage + "/" + totalPages + ", " + ordered.size() + " " + EasyVipConfig.localized("entries", "entradas") + ")");

                for (int i = fromIndex; i < toIndex; i++) {
                    AuditLogRecord record = ordered.get(i);
                    String timestamp = formatter.format(Instant.ofEpochMilli(record.getTimestamp()));
                    String details = KeySecurity.sanitizeAuditDetails(record.getDetails());
                    if (details == null) details = "";
                    TextUtil.sendMessage(sender, String.format("§7- §f%s §8| §e%s §8| §a%s §8| §7%s", timestamp, record.getOperator(), record.getAction(), details));
                }
                return true;
            }

            case "webstore": {
                if (args.size() > 1 && args.get(1).equalsIgnoreCase("status")) {
                    String status = WebStoreFulfillmentService.statusSummary();
                    TextUtil.sendMessage(sender, "§7[§eEasyVip§7] §e" + EasyVipConfig.localized("WebStore fulfillment status:", "Status do fulfillment da WebStore:") + " §f" + status);
                    return true;
                }
                TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin webstore status", "Uso: /easyvip admin webstore status"));
                return true;
            }

            case "savevipactivation":
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin savevipactivation <tier>", "Uso: /easyvip admin savevipactivation <tier>"));
                    return true;
                }
                return handleSaveVipActivation(sender, args.get(1));
        }

        return true;
    }

    private boolean handleNetworkDiagnostics(CommandSender sender, List<String> args) {
        if (!(plugin instanceof br.com.pedrodalben.easyvip.paper.EasyVipPaperPlugin paper)) {
            TextUtil.sendMessage(sender, "§cEasyVip network diagnostics are unavailable.");
            return true;
        }
        String sub = args.isEmpty() ? "status" : args.get(0).toLowerCase(Locale.ROOT);
        if ("reconcile".equals(sub)) {
            return handleNetworkReconcile(sender, args.subList(1, args.size()), paper);
        }
        if ("doctor".equals(sub)) {
            paper.networkDoctorAsync().whenComplete((status, error) ->
                    sendNetworkMessage(sender, error == null
                            ? "§7[§eEasyVip§7] " + status
                            : "§cUnable to run network doctor."));
            return true;
        }
        if ("nodes".equals(sub)) {
            var nodes = paper.networkNodes();
            if (nodes == null) {
                TextUtil.sendMessage(sender, "§7[§eEasyVip§7] Redis is disabled; no node registry is available.");
                return true;
            }
            nodes.visibleNodes(java.time.Instant.now()).whenComplete((visible, error) ->
                    sendNetworkMessage(sender, error == null
                            ? "§7[§eEasyVip§7] Active nodes: " + visible.stream()
                            .map(node -> node.identity().nodeId()).sorted().toList()
                            : "§cUnable to query active nodes."));
            return true;
        }
        if (!Set.of("status", "cache", "redis", "database", "deliveries").contains(sub)) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized(
                    "Usage: /easyvip network <status|nodes|cache|redis|database|deliveries|reconcile|doctor>",
                    "Uso: /easyvip network <status|nodes|cache|redis|database|deliveries|reconcile|doctor>"));
            return true;
        }
        paper.networkStatusAsync().whenComplete((status, error) ->
                sendNetworkMessage(sender, error == null
                        ? "§7[§eEasyVip§7] " + status
                        : "§cUnable to collect network diagnostics."));
        return true;
    }

    private boolean handleNetworkReconcile(CommandSender sender, List<String> args,
                                           br.com.pedrodalben.easyvip.paper.EasyVipPaperPlugin paper) {
        if (args.size() != 1 || args.get(0).isBlank()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized(
                    "Usage: /easyvip network reconcile <player>",
                    "Uso: /easyvip network reconcile <jogador>"));
            return true;
        }
        String requestedName = args.get(0);
        Player online = Bukkit.getPlayerExact(requestedName);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(requestedName);
        if (online == null && !target.hasPlayedBefore()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized(
                    "Player has no known server profile.",
                    "O jogador não possui perfil conhecido neste servidor."));
            return true;
        }
        java.util.UUID playerUuid = target.getUniqueId();
        paper.playerCapabilitiesAsync(playerUuid)
                .thenCompose(view -> PermissionBridge.reconcileCapabilities(playerUuid, view.capabilities().keySet()))
                .whenComplete((result, error) -> sendNetworkMessage(sender, error == null
                        ? "§a[§eEasyVip§a] Reconciled " + requestedName + " (added="
                        + result.added().size() + ",removed=" + result.removed().size() + ")."
                        : "§cUnable to reconcile " + requestedName + "."));
        return true;
    }

    private void sendNetworkMessage(CommandSender sender, String message) {
        runOnServer(() -> TextUtil.sendMessage(sender, message));
    }

    private void runOnServer(Runnable task) {
        if (plugin == null) {
            task.run();
            return;
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (IllegalStateException ignored) {
            task.run();
        }
    }

    private boolean handleGenerate(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin generate <vip|reward|command|item|itemstack|custom> ...", "Uso: /easyvip admin generate <...>"));
            return true;
        }

        String type = args.get(0).toLowerCase(Locale.ROOT);

        switch (type) {
            case "vip": {
                if (args.size() < 3) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin generate vip <tier> <duration> [max_uses] [bound_player]", "Uso: /easyvip admin generate vip <tier> <duration> [max_uses] [bound_player]"));
                    return true;
                }
                String tier = args.get(1);
                String duration = args.get(2);
                int maxUses = args.size() > 3 ? parseInt(args.get(3), 1) : 1;
                String boundPlayerName = args.size() > 4 ? args.get(4) : null;
                UUID boundUuid = null;
                String boundDisplay = "qualquer um";

                if (boundPlayerName != null && !boundPlayerName.equalsIgnoreCase("none")) {
                    Player p = Bukkit.getPlayer(boundPlayerName);
                    boundUuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(boundPlayerName).getUniqueId();
                    boundDisplay = boundPlayerName;
                }

                if (!EasyVipConfig.tiers.list.containsKey(tier)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidTier);
                    return true;
                }

                long dur = DurationParser.parseDurationMillis(duration);
                if (dur == 0 || (dur < 0 && dur != -1)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidDuration);
                    return true;
                }

                KeyRecord record = KeyService.generateVipKey(tier, duration, maxUses, boundUuid, -1, null);
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Key generated successfully: ", "Chave gerada com sucesso: ")
                        + "§e" + record.getCode()
                        + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                        + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + boundDisplay + "§a)");
                return true;
            }

            case "reward": {
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin generate reward <reward_key_id> [max_uses] [bound_player]", "Uso: /easyvip admin generate reward <reward_key_id> [max_uses] [bound_player]"));
                    return true;
                }
                String rkId = args.get(1);
                int maxUses = args.size() > 2 ? parseInt(args.get(2), 1) : 1;
                String boundPlayerName = args.size() > 3 ? args.get(3) : null;
                UUID boundUuid = null;
                String boundDisplay = "qualquer um";

                if (boundPlayerName != null && !boundPlayerName.equalsIgnoreCase("none")) {
                    Player p = Bukkit.getPlayer(boundPlayerName);
                    boundUuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(boundPlayerName).getUniqueId();
                    boundDisplay = boundPlayerName;
                }

                if (!EasyVipConfig.rewardKeys.list.containsKey(rkId)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.prefix + EasyVipConfig.localized("Reward key not found.", "Chave de recompensa não encontrada."));
                    return true;
                }

                KeyRecord record = KeyService.generateRewardKey(rkId, maxUses, boundUuid, -1, null);
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Key generated successfully: ", "Chave gerada com sucesso: ")
                        + "§e" + record.getCode()
                        + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                        + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + boundDisplay + "§a)");
                return true;
            }

            case "command": {
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin generate command <command> OR <max_uses> <bound_player> <command>", "Uso: /easyvip admin generate command <comando>"));
                    return true;
                }

                int maxUses = 1;
                UUID boundUuid = null;
                String boundDisplay = "qualquer um";
                String commandStr;

                if (args.size() >= 4 && isNumeric(args.get(1))) {
                    maxUses = parseInt(args.get(1), 1);
                    String boundPlayerName = args.get(2);
                    if (!boundPlayerName.equalsIgnoreCase("none")) {
                        Player p = Bukkit.getPlayer(boundPlayerName);
                        boundUuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(boundPlayerName).getUniqueId();
                        boundDisplay = boundPlayerName;
                    }
                    commandStr = String.join(" ", args.subList(3, args.size()));
                } else {
                    commandStr = String.join(" ", args.subList(1, args.size()));
                }

                List<Map<String, Object>> actions = new ArrayList<>();
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("type", "run_server_command");
                action.put("command", commandStr);
                actions.add(action);

                KeyRecord record = KeyService.generateCustomKey(actions, maxUses, boundUuid, -1);
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Command key generated successfully: ", "Chave de comando gerada com sucesso: ")
                        + "§e" + record.getCode()
                        + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                        + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + boundDisplay + "§a)");
                return true;
            }

            case "item": {
                if (args.size() < 3) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin generate item <item_id> <amount> [max_uses] [bound_player]", "Uso: /easyvip admin generate item <item_id> <amount> [max_uses] [bound_player]"));
                    return true;
                }
                String itemId = args.get(1);
                int amount = parseInt(args.get(2), 1);
                int maxUses = args.size() > 3 ? parseInt(args.get(3), 1) : 1;
                String boundPlayerName = args.size() > 4 ? args.get(4) : null;
                UUID boundUuid = null;
                String boundDisplay = "qualquer um";

                if (boundPlayerName != null && !boundPlayerName.equalsIgnoreCase("none")) {
                    Player p = Bukkit.getPlayer(boundPlayerName);
                    boundUuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(boundPlayerName).getUniqueId();
                    boundDisplay = boundPlayerName;
                }

                List<Map<String, Object>> actions = new ArrayList<>();
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("type", "give_item");
                action.put("item", itemId);
                action.put("amount", amount);
                actions.add(action);

                KeyRecord record = KeyService.generateCustomKey(actions, maxUses, boundUuid, -1);
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Item key generated successfully: ", "Chave de item gerada com sucesso: ")
                        + "§e" + record.getCode()
                        + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                        + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + boundDisplay + "§a)");
                return true;
            }

            case "itemstack": {
                if (!(sender instanceof Player player)) {
                    TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
                    return true;
                }
                ItemStack stack = player.getInventory().getItemInMainHand();
                if (stack.getType().isAir()) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("You must hold an item in your main hand.", "Você precisa segurar um item na sua mão principal."));
                    return true;
                }

                int maxUses = args.size() > 1 ? parseInt(args.get(1), 1) : 1;
                String boundPlayerName = args.size() > 2 ? args.get(2) : null;
                UUID boundUuid = null;
                String boundDisplay = "qualquer um";

                if (boundPlayerName != null && !boundPlayerName.equalsIgnoreCase("none")) {
                    Player p = Bukkit.getPlayer(boundPlayerName);
                    boundUuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(boundPlayerName).getUniqueId();
                    boundDisplay = boundPlayerName;
                }

                String comp = stack.hasItemMeta() ? stack.getItemMeta().getAsComponentString() : "";
                String stackStr = stack.getType().getKey().toString() + (comp != null ? comp : "");

                List<Map<String, Object>> actions = new ArrayList<>();
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("type", "give_item_stack");
                action.put("stack_snbt", stackStr);
                actions.add(action);

                KeyRecord record = KeyService.generateCustomKey(actions, maxUses, boundUuid, -1);
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("ItemStack key generated successfully: ", "Chave de itemstack gerada com sucesso: ")
                        + "§e" + record.getCode()
                        + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                        + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + boundDisplay + "§a)");
                return true;
            }

            case "custom": {
                if (args.size() < 2) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Usage: /easyvip admin generate custom <actions_json> OR <max_uses> <bound_player> <actions_json>", "Uso: /easyvip admin generate custom <json>"));
                    return true;
                }

                int maxUses = 1;
                UUID boundUuid = null;
                String boundDisplay = "qualquer um";
                String json;

                if (args.size() >= 4 && isNumeric(args.get(1))) {
                    maxUses = parseInt(args.get(1), 1);
                    String boundPlayerName = args.get(2);
                    if (!boundPlayerName.equalsIgnoreCase("none")) {
                        Player p = Bukkit.getPlayer(boundPlayerName);
                        boundUuid = p != null ? p.getUniqueId() : Bukkit.getOfflinePlayer(boundPlayerName).getUniqueId();
                        boundDisplay = boundPlayerName;
                    }
                    json = String.join(" ", args.subList(3, args.size()));
                } else {
                    json = String.join(" ", args.subList(1, args.size()));
                }

                List<Map<String, Object>> actions;
                try {
                    Gson gson = new Gson();
                    java.lang.reflect.Type token = new TypeToken<List<Map<String, Object>>>(){}.getType();
                    actions = gson.fromJson(json, token);
                    if (actions == null || actions.isEmpty()) {
                        TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("The actions JSON list cannot be empty.", "A lista de ações em JSON não pode ser vazia."));
                        return true;
                    }
                } catch (Exception e) {
                    TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized("Invalid JSON format for actions: ", "Formato de JSON inválido para as ações: ") + e.getClass().getSimpleName());
                    return true;
                }

                KeyRecord record = KeyService.generateCustomKey(actions, maxUses, boundUuid, -1);
                TextUtil.sendMessage(sender, "§a" + EasyVipConfig.localized("Custom action key generated successfully: ", "Chave de ações personalizadas gerada com sucesso: ")
                        + "§e" + record.getCode()
                        + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                        + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + boundDisplay + "§a)");
                return true;
            }
        }
        return true;
    }

    private boolean executeLink(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            TextUtil.sendMessage(sender, EasyVipConfig.messages.playerOnly);
            return true;
        }

        if (!WebStoreSyncService.isEnabled()) {
            TextUtil.sendMessage(sender, "§c" + EasyVipConfig.localized(
                    "Web store integration is not configured. Contact an administrator.",
                    "A integração com a loja web não está configurada. Contate um administrador."
            ));
            return true;
        }

        String code = generateLinkCode();
        WebStoreSyncService.registerChallenge(player.getUniqueId(), code);

        TextUtil.sendMessage(player, "§7[§eEasyVip§7] §e" + EasyVipConfig.localized(
                "Link your account on the web store using this code:",
                "Vincule sua conta na loja web usando este código:"
        ));
        TextUtil.sendMessage(player, "§6§l" + code);
        TextUtil.sendMessage(player, "§7" + EasyVipConfig.localized(
                "The code expires in 5 minutes.",
                "O código expira em 5 minutos."
        ));
        return true;
    }

    private String generateLinkCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(8);
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean isNumeric(String s) {
        try {
            Integer.parseInt(s.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> parseArguments(String[] rawArgs) {
        if (rawArgs == null || rawArgs.length == 0) {
            return Collections.emptyList();
        }

        List<String> list = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        String combined = String.join(" ", rawArgs);
        for (int i = 0; i < combined.length(); i++) {
            char c = combined.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    list.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            list.add(current.toString());
        }

        return list;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        if (cmdName.equals("viptime")) {
            return filterMatches(onlinePlayerNames(), args.length > 0 ? args[0] : "");
        }
        if (cmdName.equals("usekey") || cmdName.equals("activate") || cmdName.equals("vip") || cmdName.equals("link")) {
            return Collections.emptyList();
        }

        // /easyvip
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("use", "confirm", "info", "time", "select", "variant", "help"));
            if (PermissionBridge.hasPermission(sender, "easyvip.admin")) {
                base.addAll(List.of("admin", "network", "createvip", "savevipactivation", "active", "key", "package", "reload", "config"));
            }
            return filterMatches(base, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("select")) {
            return filterMatches(new ArrayList<>(EasyVipConfig.tiers.list.keySet()), args[1]);
        }

        if (sub.equals("network")) {
            if (args.length == 2 && PermissionBridge.hasPermission(sender, "easyvip.admin")) {
                    return filterMatches(List.of("status", "nodes", "cache", "redis", "database", "deliveries", "reconcile", "doctor"), args[1]);
            }
            return Collections.emptyList();
        }

        if (sub.equals("info") || sub.equals("time")) {
            if (PermissionBridge.hasPermission(sender, "easyvip.admin")) {
                return filterMatches(onlinePlayerNames(), args[1]);
            }
            return Collections.emptyList();
        }

        if (sub.equals("active")) {
            if (args.length == 2) return filterMatches(List.of("set"), args[1]);
            if (args.length == 3) return filterMatches(onlinePlayerNames(), args[2]);
            if (args.length == 4) return filterMatches(new ArrayList<>(EasyVipConfig.tiers.list.keySet()), args[3]);
        }

        if (sub.equals("variant")) {
            if (args.length == 2) return filterMatches(List.of("choose", "pending", "clear"), args[1]);
            if (args[1].equalsIgnoreCase("choose")) {
                if (args.length == 3) return filterMatches(new ArrayList<>(EasyVipConfig.packages.list.keySet()), args[2]);
                if (args.length == 4) {
                    EasyVipConfig.PackageDefinition pkg = EasyVipConfig.packages.list.get(args[2]);
                    if (pkg != null && pkg.variants != null) {
                        return filterMatches(new ArrayList<>(pkg.variants.keySet()), args[3]);
                    }
                }
            }
            if (args[1].equalsIgnoreCase("pending") || args[1].equalsIgnoreCase("clear")) {
                if (args.length == 3 && PermissionBridge.hasPermission(sender, "easyvip.admin")) {
                    return filterMatches(onlinePlayerNames(), args[2]);
                }
            }
        }

        if (sub.equals("savevipactivation")) {
            return filterMatches(new ArrayList<>(EasyVipConfig.tiers.list.keySet()), args[1]);
        }

        if (sub.equals("config")) {
            if (args.length == 2) return filterMatches(List.of("reload", "validate"), args[1]);
        }

        if (sub.equals("key")) {
            if (args.length == 2) return filterMatches(List.of("list", "info", "delete", "cleanup"), args[1]);
            if (args.length == 3 && (args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("delete"))) {
                List<String> keyCodes = new ArrayList<>();
                for (KeyRecord kr : PersistenceManager.getAllKeys()) {
                    keyCodes.add(kr.getCode());
                }
                return filterMatches(keyCodes, args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("info")) {
                return filterMatches(List.of("reveal"), args[3]);
            }
        }

        if (sub.equals("package")) {
            if (args.length == 2) return filterMatches(List.of("list", "info"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("info")) {
                return filterMatches(new ArrayList<>(EasyVipConfig.packages.list.keySet()), args[2]);
            }
        }

        if (sub.equals("admin")) {
            if (args.length == 2) {
                return filterMatches(List.of("addvip", "addfakevip", "removevip", "generate", "givepackage", "giveitemkey", "audit", "webstore", "savevipactivation"), args[1]);
            }

            String adminSub = args[1].toLowerCase(Locale.ROOT);
            if (adminSub.equals("addvip") || adminSub.equals("removevip")) {
                if (args.length == 3) return filterMatches(onlinePlayerNames(), args[2]);
                if (args.length == 4) return filterMatches(new ArrayList<>(EasyVipConfig.tiers.list.keySet()), args[3]);
                if (args.length == 5 && adminSub.equals("addvip")) return filterMatches(List.of("30d", "7d", "1d", "permanent"), args[4]);
            }
            if (adminSub.equals("givepackage")) {
                if (args.length == 3) return filterMatches(onlinePlayerNames(), args[2]);
                if (args.length == 4) return filterMatches(new ArrayList<>(EasyVipConfig.packages.list.keySet()), args[3]);
            }
            if (adminSub.equals("giveitemkey")) {
                if (args.length == 3) return filterMatches(onlinePlayerNames(), args[2]);
            }
            if (adminSub.equals("webstore")) {
                if (args.length == 3) return filterMatches(List.of("status"), args[2]);
            }
            if (adminSub.equals("network")) {
                if (args.length == 3) return filterMatches(List.of("status", "nodes", "cache", "redis", "database", "deliveries", "reconcile", "doctor"), args[2]);
            }
            if (adminSub.equals("generate")) {
                if (args.length == 3) return filterMatches(List.of("vip", "reward", "command", "item", "itemstack", "custom"), args[2]);
                String genType = args[2].toLowerCase(Locale.ROOT);
                if (genType.equals("vip")) {
                    if (args.length == 4) return filterMatches(new ArrayList<>(EasyVipConfig.tiers.list.keySet()), args[3]);
                    if (args.length == 5) return filterMatches(List.of("30d", "7d", "1d", "permanent"), args[4]);
                    if (args.length == 6) return filterMatches(List.of("1", "5", "10"), args[5]);
                    if (args.length == 7) return filterMatches(onlinePlayerNames(), args[6]);
                }
                if (genType.equals("reward")) {
                    if (args.length == 4) return filterMatches(new ArrayList<>(EasyVipConfig.rewardKeys.list.keySet()), args[3]);
                    if (args.length == 5) return filterMatches(List.of("1", "3", "5"), args[4]);
                    if (args.length == 6) return filterMatches(onlinePlayerNames(), args[5]);
                }
            }
        }

        return Collections.emptyList();
    }

    private List<String> onlinePlayerNames() {
        List<String> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            list.add(p.getName());
        }
        return list;
    }

    private List<String> filterMatches(List<String> candidates, String current) {
        String lower = current.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(c);
            }
        }
        return matches;
    }
}

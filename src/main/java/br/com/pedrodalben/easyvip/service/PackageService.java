package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.PendingVariantSelection;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.platform.TextUtil;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;

public final class PackageService {

    private PackageService() {
    }

    public static int cleanupExpiredPendingVariants() {
        int removed = 0;
        for (Map.Entry<UUID, List<PendingVariantSelection>> entry : PersistenceManager.getAllPendingVariants().entrySet()) {
            removed += cleanupExpiredPendingVariants(entry.getKey());
        }
        return removed;
    }

    public static int cleanupExpiredPendingVariants(UUID uuid) {
        int removed = 0;
        int timeout = EasyVipConfig.common.variantSelectionTimeoutSeconds;
        for (PendingVariantSelection selection : new ArrayList<>(PersistenceManager.getPendingVariants(uuid))) {
            if (selection.isExpired(timeout)) {
                PersistenceManager.removePendingVariant(uuid, selection.getPackageId());
                removed++;
            }
        }
        return removed;
    }

    public static void notifyPendingVariantsOnLogin(Player player) {
        if (player == null) return;
        notifyPendingVariantsOnLogin(player.getUniqueId(), player.getName(), msg -> TextUtil.sendMessage(player, msg));
    }

    static void notifyPendingVariantsOnLogin(UUID uuid, String playerName, Consumer<String> messageSink) {
        cleanupExpiredPendingVariants(uuid);
        if (!EasyVipConfig.common.notifyPendingVariantOnLogin) {
            return;
        }
        List<PendingVariantSelection> pendingList = PersistenceManager.getPendingVariants(uuid);
        if (pendingList.isEmpty()) {
            return;
        }
        for (PendingVariantSelection selection : pendingList) {
            if (!selection.isExpired(EasyVipConfig.common.variantSelectionTimeoutSeconds)) {
                EasyVipConfig.PackageDefinition def = EasyVipConfig.packages.list.get(selection.getPackageId());
                String pkgDisplay = def != null ? def.displayName : selection.getPackageId();
                Map<String, String> ctx = new HashMap<>();
                ctx.put("package", pkgDisplay);
                ctx.put("package_id", selection.getPackageId());
                messageSink.accept(ActionExecutor.resolvePlaceholders(
                        EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantPending, ctx));
            }
        }
    }

    public static boolean givePackage(Player player, String packageId) {
        if (player == null) return false;
        EasyVipConfig.PackageDefinition def = EasyVipConfig.packages.list.get(packageId);
        if (def == null) {
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                    EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageNotFound, new HashMap<>()));
            return false;
        }

        if (!def.repeatable) {
            Map<String, Long> usage = PersistenceManager.getPackageUsage(player.getUniqueId());
            if (usage.containsKey(packageId)) {
                TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                        EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "&cThis package has already been redeemed before.",
                                "&cEste pacote já foi resgatado anteriormente."
                        ), new HashMap<>()));
                return false;
            }
        }

        if (def.cooldownSeconds > 0) {
            Map<String, Long> usage = PersistenceManager.getPackageUsage(player.getUniqueId());
            Long lastUsed = usage.get(packageId);
            if (lastUsed != null && System.currentTimeMillis() - lastUsed < (def.cooldownSeconds * 1000L)) {
                TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                        EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "&cThis package is still on cooldown.",
                                "&cEste pacote ainda está em cooldown."
                        ), new HashMap<>()));
                return false;
            }
        }

        Map<String, String> ctx = new HashMap<>();
        ctx.put("package", def.displayName);
        ctx.put("package_id", def.id);

        if (def.variants != null && !def.variants.isEmpty()) {
            List<String> variantNames = new ArrayList<>(def.variants.keySet());
            PendingVariantSelection pending = new PendingVariantSelection(player.getUniqueId(), packageId, variantNames);
            PersistenceManager.addPendingVariant(player.getUniqueId(), pending);
            markPackageUsage(player.getUniqueId(), packageId);

            String varMsg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantPending;
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(varMsg, ctx));
            return true;
        } else {
            // No variants, execute actions directly
            boolean ok = ActionExecutor.execute(player, def.actions, ctx);
            if (!ok) {
                return false;
            }
            markPackageUsage(player.getUniqueId(), packageId);
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                    EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageGiven, ctx));
            PersistenceManager.log(player.getName(), "give_package", "Given package " + packageId + " to " + player.getName());
            return true;
        }
    }

    public static boolean chooseVariant(Player player, String packageId, String variantName) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();
        List<PendingVariantSelection> pendingList = PersistenceManager.getPendingVariants(uuid);
        PendingVariantSelection match = null;

        for (PendingVariantSelection sel : pendingList) {
            if (sel.getPackageId().equals(packageId)) {
                match = sel;
                break;
            }
        }

        if (match == null) {
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                    EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageNotFound, new HashMap<>()));
            return false;
        }

        if (match.isExpired(EasyVipConfig.common.variantSelectionTimeoutSeconds)) {
            PersistenceManager.removePendingVariant(uuid, packageId);
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                    EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                            "&cThis variant choice has expired.",
                            "&cEsta escolha de variante expirou."
                    ), new HashMap<>()));
            return false;
        }

        EasyVipConfig.PackageDefinition def = EasyVipConfig.packages.list.get(packageId);
        if (def == null) {
            PersistenceManager.removePendingVariant(uuid, packageId);
            return false;
        }

        List<Map<String, Object>> variantActions = def.variants.get(variantName.toLowerCase(Locale.ROOT));
        if (variantActions == null) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("allowed_variants", String.join(", ", def.variants.keySet()));
            TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                    EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantInvalid, ctx));
            return false;
        }

        Map<String, String> ctx = new HashMap<>();
        ctx.put("package", def.displayName);
        ctx.put("package_id", def.id);
        ctx.put("variant", variantName);

        // Execute base actions + variant actions
        boolean ok = ActionExecutor.execute(player, def.actions, ctx);
        ok = ActionExecutor.execute(player, variantActions, ctx) && ok;
        if (!ok) {
            return false;
        }

        PersistenceManager.removePendingVariant(uuid, packageId);
        markPackageUsage(uuid, packageId);

        TextUtil.sendMessage(player, ActionExecutor.resolvePlaceholders(
                EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantSelected, ctx));

        PersistenceManager.log(player.getName(), "choose_variant",
                "Selected variant " + variantName + " for package " + packageId);

        return true;
    }

    public static void markPackageUsage(UUID uuid, String packageId) {
        Map<String, Long> usage = PersistenceManager.getPackageUsage(uuid);
        usage.put(packageId, System.currentTimeMillis());
        PersistenceManager.updatePackageUsage(uuid, usage);
    }
}

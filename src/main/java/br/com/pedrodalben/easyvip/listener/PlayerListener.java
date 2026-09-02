package br.com.pedrodalben.easyvip.listener;

import br.com.pedrodalben.easyvip.command.EasyVipCommandHandler;
import br.com.pedrodalben.easyvip.cache.CachedEntitlementApi;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.service.KeyService;
import br.com.pedrodalben.easyvip.service.PackageService;
import br.com.pedrodalben.easyvip.service.VipService;
import br.com.pedrodalben.easyvip.webstore.WebStoreSyncService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;


public final class PlayerListener implements Listener {
    private final CachedEntitlementApi api;
    private final Plugin plugin;

    public PlayerListener(CachedEntitlementApi api) {
        this(null, api);
    }

    public PlayerListener(Plugin plugin, CachedEntitlementApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        java.util.UUID playerUuid = player.getUniqueId();

        // 1. Process VIP expirations and pending activation actions off-thread.
        if (plugin == null) {
            VipService.handlePlayerJoin(player);
            PackageService.notifyPendingVariantsOnLogin(player);
        } else {
            VipService.handlePlayerJoinAsync(plugin, player)
                    .thenCompose(ignored -> PackageService.pendingVariantMessagesAsync(playerUuid))
                    .thenAccept(messages -> runOnPlayerThread(player, () -> messages.forEach(message ->
                            br.com.pedrodalben.easyvip.platform.TextUtil.sendMessage(player, message))))
                    .exceptionally(error -> null);
        }

        if (api != null) {
            api.playerAsync(player.getUniqueId(), br.com.pedrodalben.easyvip.api.ScopeContext.network())
                    .thenCompose(view -> br.com.pedrodalben.easyvip.platform.PermissionBridge.reconcileCapabilities(
                            player.getUniqueId(), view.capabilities().keySet()))
                    .exceptionally(error -> null);
        }

        // 2. WebStore player sync
        if (WebStoreSyncService.isEnabled() && (EasyVipConfig.webstore.syncOnJoin || EasyVipConfig.webstore.syncOnLogin)) {
            String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "";
            WebStoreSyncService.syncPlayer(player.getUniqueId(), player.getName(), ip);
        }
    }

    private void runOnPlayerThread(Player player, Runnable task) {
        if (plugin == null) {
            task.run();
            return;
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                task.run();
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        try {
            player.getScheduler().run(plugin, ignored -> task.run(), () -> { });
        } catch (Throwable ignored) {
            try {
                Bukkit.getScheduler().runTask(plugin, task);
            } catch (Throwable ignoredAgain) {
                // Player may have disconnected before the notification was scheduled.
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            return;
        }

        if (!KeyService.isPhysicalKeyItem(item)) {
            return;
        }

        // Cancel the interaction (so tripwire hooks or other blocks aren't placed)
        event.setCancelled(true);

        Player player = event.getPlayer();
        String keyCode = KeyService.getPhysicalKeyCode(item);
        String instanceId = KeyService.getPhysicalKeyInstanceId(item);

        if (keyCode == null || keyCode.isBlank()) {
            return;
        }

        KeyService.RedeemResult result = KeyService.redeemPhysicalKey(player, keyCode, instanceId);

        if (result == KeyService.RedeemResult.SUCCESS) {
            // Decrement the physical item stack
            item.setAmount(item.getAmount() - 1);
        }

        EasyVipCommandHandler.sendRedeemFeedback(player, result, keyCode);
    }
}

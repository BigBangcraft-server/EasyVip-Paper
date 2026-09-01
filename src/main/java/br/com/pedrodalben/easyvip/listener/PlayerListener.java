package br.com.pedrodalben.easyvip.listener;

import br.com.pedrodalben.easyvip.command.EasyVipCommandHandler;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.service.KeyService;
import br.com.pedrodalben.easyvip.service.PackageService;
import br.com.pedrodalben.easyvip.service.VipService;
import br.com.pedrodalben.easyvip.webstore.WebStoreSyncService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class PlayerListener implements Listener {

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1. Process VIP expirations and pending activation actions
        VipService.handlePlayerJoin(player);

        // 2. Notify player of any pending package variant selections
        PackageService.notifyPendingVariantsOnLogin(player);

        // 3. WebStore player sync
        if (WebStoreSyncService.isEnabled() && (EasyVipConfig.webstore.syncOnJoin || EasyVipConfig.webstore.syncOnLogin)) {
            String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "";
            WebStoreSyncService.syncPlayer(player.getUniqueId(), player.getName(), ip);
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

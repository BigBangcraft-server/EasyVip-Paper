package br.com.pedrodalben.easyvip.platform;

import br.com.pedrodalben.easyvip.event.EasyVipActionEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

public final class PaperPlatformBridge implements PlatformBridge {

    @Override
    public boolean hasPermission(Player player, String permission) {
        return PermissionBridge.hasPermission(player, permission);
    }

    @Override
    public void setPermissionFlagInternal(Player player, String permission, boolean active) {
        PermissionBridge.setPermission(player, permission, active);
    }

    @Override
    public void fireCustomEventHook(Player player, String hook, Map<String, String> context) {
        Bukkit.getPluginManager().callEvent(new EasyVipActionEvent(player, hook, context));
    }
}

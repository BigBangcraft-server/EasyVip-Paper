package br.com.pedrodalben.easyvip.platform;

import org.bukkit.entity.Player;
import java.util.Map;

public interface PlatformBridge {

    boolean hasPermission(Player player, String permission);

    void setPermissionFlagInternal(Player player, String permission, boolean active);

    void fireCustomEventHook(Player player, String hook, Map<String, String> context);
}

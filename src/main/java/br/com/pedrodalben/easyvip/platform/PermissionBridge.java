package br.com.pedrodalben.easyvip.platform;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class PermissionBridge {

    private static boolean luckPermsPresent = false;

    static {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider", false, PermissionBridge.class.getClassLoader());
            luckPermsPresent = true;
        } catch (Throwable e) {
            luckPermsPresent = false;
        }
    }

    private PermissionBridge() {
    }

    public static boolean isLuckPermsPresent() {
        return luckPermsPresent;
    }

    public static boolean hasPermission(CommandSender sender, String permission) {
        if (sender == null) {
            return false;
        }
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        if (sender instanceof Player player) {
            return hasPermission(player, permission);
        }
        return sender.hasPermission(permission);
    }

    public static boolean hasPermission(Player player, String permission) {
        if (player == null) {
            return false;
        }
        if (player.isOp()) {
            return true;
        }

        boolean luckPermsAllowed = false;
        if (luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            luckPermsAllowed = LuckPermsWrapper.hasPermission(player, permission);
        }

        boolean bukkitAllowed = player.hasPermission(permission);

        return resolvePermission(luckPermsAllowed, luckPermsAllowed, bukkitAllowed, player.isOp());
    }

    static boolean resolvePermission(boolean primaryAllowed, boolean fallbackLuckPermsAllowed, boolean fallbackBridgeAllowed, boolean opFallback) {
        if (primaryAllowed) {
            return true;
        }
        if (fallbackLuckPermsAllowed) {
            return true;
        }
        if (fallbackBridgeAllowed) {
            return true;
        }
        return opFallback;
    }


    public static void setPermission(Player player, String permission, boolean value) {
        if (player != null && luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            LuckPermsWrapper.setPermission(player, permission, value);
        }
    }

    public static void setPermission(UUID uuid, String permission, boolean value) {
        if (uuid != null && luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            LuckPermsWrapper.setPermission(uuid, permission, value);
        }
    }

    public static void setGroup(Player player, String group, boolean value) {
        if (player != null && luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            LuckPermsWrapper.setGroup(player, group, value);
        }
    }

    public static void setGroup(UUID uuid, String group, boolean value) {
        if (uuid != null && luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            LuckPermsWrapper.setGroup(uuid, group, value);
        }
    }

    public static boolean createGroup(String groupName) {
        if (luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            return LuckPermsWrapper.createGroup(groupName);
        }
        return false;
    }
}

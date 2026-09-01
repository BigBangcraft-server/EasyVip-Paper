package br.com.pedrodalben.easyvip.platform;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class LuckPermsWrapper {

    private LuckPermsWrapper() {
    }

    public static boolean hasPermission(UUID playerUuid, String permission) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(playerUuid);
            if (user != null) {
                return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean hasPermission(Player player, String permission) {
        if (player == null) return false;
        return hasPermission(player.getUniqueId(), permission);
    }

    public static void setPermission(UUID playerUuid, String permission, boolean value) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(playerUuid);
            if (user == null) {
                user = api.getUserManager().loadUser(playerUuid).join();
            }
            if (user != null) {
                Node node = PermissionNode.builder(permission).value(value).build();
                if (value) {
                    user.data().add(node);
                } else {
                    user.data().remove(node);
                }
                api.getUserManager().saveUser(user);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void setPermission(Player player, String permission, boolean value) {
        if (player != null) {
            setPermission(player.getUniqueId(), permission, value);
        }
    }

    public static void setGroup(UUID playerUuid, String group, boolean value) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(playerUuid);
            if (user == null) {
                user = api.getUserManager().loadUser(playerUuid).join();
            }
            if (user != null) {
                InheritanceNode node = InheritanceNode.builder(group).value(value).build();
                if (value) {
                    user.data().add(node);
                } else {
                    user.data().remove(node);
                }
                api.getUserManager().saveUser(user);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void setGroup(Player player, String group, boolean value) {
        if (player != null) {
            setGroup(player.getUniqueId(), group, value);
        }
    }

    public static boolean createGroup(String groupName) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            var groupManager = api.getGroupManager();

            if (groupManager.loadGroup(groupName).get().isPresent()) {
                return true;
            }

            Group group = groupManager.createAndLoadGroup(groupName).get();
            groupManager.saveGroup(group).get();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

package br.com.pedrodalben.easyvip.platform;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import br.com.pedrodalben.easyvip.projection.LuckPermsProjection;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

    /** Projects EasyVip capability nodes while preserving all unrelated user data. */
    public static CompletionStage<LuckPermsProjection.ProjectionResult> reconcileManagedPermissions(
            UUID playerUuid, Collection<String> desiredCapabilities) {
        if (playerUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerUuid cannot be null"));
        try {
            LuckPerms api = LuckPermsProvider.get();
            CompletionStage<User> loaded = CompletableFuture.completedFuture(api.getUserManager().getUser(playerUuid));
            if (api.getUserManager().getUser(playerUuid) == null) {
                loaded = api.getUserManager().loadUser(playerUuid);
            }
            return loaded.thenCompose(user -> {
                if (user == null) return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms user unavailable"));
                LuckPermsProjection.ManagedNodeStore store = new LuckPermsProjection.ManagedNodeStore() {
                    @Override
                    public Set<String> managedNodes(UUID ignored) {
                        Set<String> nodes = new HashSet<>();
                        for (Node node : user.getNodes()) {
                            if (node.getKey().startsWith(LuckPermsProjection.PREFIX)) nodes.add(node.getKey());
                        }
                        return nodes;
                    }

                    @Override
                    public void add(UUID ignored, String node) {
                        user.data().add(PermissionNode.builder(node).value(true).build());
                    }

                    @Override
                    public void remove(UUID ignored, String node) {
                        user.data().remove(PermissionNode.builder(node).value(true).build());
                    }
                };
                LuckPermsProjection.ProjectionResult result = new LuckPermsProjection()
                        .reconcile(playerUuid, desiredCapabilities, store);
                if (result.added().isEmpty() && result.removed().isEmpty()) {
                    return CompletableFuture.completedFuture(result);
                }
                return api.getUserManager().saveUser(user).thenApply(ignored -> result);
            });
        } catch (Throwable exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}

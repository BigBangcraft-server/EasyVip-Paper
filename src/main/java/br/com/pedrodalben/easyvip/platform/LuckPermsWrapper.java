package br.com.pedrodalben.easyvip.platform;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
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
import java.util.function.Consumer;

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
                setPermissionAsync(playerUuid, permission, value);
                return;
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

    /** Applies a permission without waiting on LuckPerms I/O. */
    public static CompletionStage<Boolean> setPermissionAsync(UUID playerUuid, String permission, boolean value) {
        if (playerUuid == null || permission == null || permission.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return mutateUserAsync(playerUuid, user -> {
            Node node = PermissionNode.builder(permission).value(value).build();
            if (value) {
                user.data().add(node);
            } else {
                user.data().remove(node);
            }
        });
    }

    public static void setGroup(UUID playerUuid, String group, boolean value) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(playerUuid);
            if (user == null) {
                setGroupAsync(playerUuid, group, value);
                return;
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

    /** Applies a group without waiting on LuckPerms I/O. */
    public static CompletionStage<Boolean> setGroupAsync(UUID playerUuid, String group, boolean value) {
        if (playerUuid == null || group == null || group.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return mutateUserAsync(playerUuid, user -> {
            InheritanceNode node = InheritanceNode.builder(group).value(value).build();
            if (value) {
                user.data().add(node);
            } else {
                user.data().remove(node);
            }
        });
    }

    public static boolean createGroup(String groupName) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            var groupManager = api.getGroupManager();
            createGroupAsync(groupName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Creates a group without blocking the command thread on LuckPerms futures. */
    public static CompletionStage<Boolean> createGroupAsync(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            LuckPerms api = LuckPermsProvider.get();
            var groupManager = api.getGroupManager();
            return groupManager.loadGroup(groupName).thenCompose(existing -> {
                if (existing.isPresent()) {
                    return CompletableFuture.completedFuture(true);
                }
                return groupManager.createAndLoadGroup(groupName)
                        .thenCompose(group -> groupManager.saveGroup(group))
                        .thenApply(ignored -> true);
            });
        } catch (Throwable exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static CompletionStage<Boolean> mutateUserAsync(UUID playerUuid, Consumer<User> mutation) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User existing = api.getUserManager().getUser(playerUuid);
            CompletionStage<User> loaded = existing == null
                    ? api.getUserManager().loadUser(playerUuid)
                    : CompletableFuture.completedFuture(existing);
            return loaded.thenCompose(user -> {
                if (user == null) {
                    return CompletableFuture.completedFuture(false);
                }
                mutation.accept(user);
                return api.getUserManager().saveUser(user).thenApply(ignored -> true);
            });
        } catch (Throwable exception) {
            return CompletableFuture.failedFuture(exception);
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

package br.com.pedrodalben.easyvip.projection;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Computes and applies only EasyVip-managed permission nodes. The store must
 * return the managed namespace, so unrelated LuckPerms nodes are never removed.
 */
public final class LuckPermsProjection {
    public static final String PREFIX = "easyvip.managed.";

    public ProjectionResult reconcile(UUID playerUuid, Collection<String> desiredCapabilities,
                                      ManagedNodeStore store) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(store, "store");
        Set<String> desired = new LinkedHashSet<>();
        if (desiredCapabilities != null) {
            for (String capability : desiredCapabilities) {
                if (capability != null && !capability.isBlank()) desired.add(managedNode(capability));
            }
        }
        Set<String> existing = normalize(store.managedNodes(playerUuid));
        Set<String> added = difference(desired, existing);
        Set<String> removed = difference(existing, desired);
        added.forEach(node -> store.add(playerUuid, node));
        removed.forEach(node -> store.remove(playerUuid, node));
        return new ProjectionResult(Set.copyOf(added), Set.copyOf(removed));
    }

    public static String managedNode(String capability) {
        String normalized = normalizeCapability(capability);
        return PREFIX + normalized;
    }

    private static Set<String> normalize(Collection<String> nodes) {
        Set<String> normalized = new LinkedHashSet<>();
        if (nodes == null) return normalized;
        for (String node : nodes) {
            if (node == null || node.isBlank()) continue;
            String value = node.trim().toLowerCase(Locale.ROOT);
            if (!value.startsWith(PREFIX)) continue;
            normalized.add(value);
        }
        return normalized;
    }

    private static String normalizeCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability cannot be blank");
        }
        String value = capability.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9._:-]+")) throw new IllegalArgumentException("capability contains unsupported characters");
        return value;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    public interface ManagedNodeStore {
        Set<String> managedNodes(UUID playerUuid);
        void add(UUID playerUuid, String node);
        void remove(UUID playerUuid, String node);
    }

    public record ProjectionResult(Set<String> added, Set<String> removed) {
        public ProjectionResult {
            added = Set.copyOf(added == null ? Set.of() : added);
            removed = Set.copyOf(removed == null ? Set.of() : removed);
        }
    }
}

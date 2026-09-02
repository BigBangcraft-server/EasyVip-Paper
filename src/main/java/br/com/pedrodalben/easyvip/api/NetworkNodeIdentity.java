package br.com.pedrodalben.easyvip.api;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Immutable identity for one Paper, Folia, or Velocity node. */
public record NetworkNodeIdentity(String nodeId, String group, String environment, Set<String> tags) {

    public NetworkNodeIdentity {
        nodeId = required(nodeId, "nodeId");
        group = required(group, "group");
        environment = required(environment, "environment");
        Set<String> normalizedTags = new TreeSet<>();
        if (tags != null) {
            for (String tag : tags) {
                normalizedTags.add(required(tag, "tag"));
            }
        }
        tags = Set.copyOf(normalizedTags);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

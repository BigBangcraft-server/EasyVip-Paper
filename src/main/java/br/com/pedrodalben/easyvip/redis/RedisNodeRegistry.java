package br.com.pedrodalben.easyvip.redis;

import br.com.pedrodalben.easyvip.api.NetworkNodeIdentity;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Ephemeral Redis visibility for nodes; it is never used as entitlement truth. */
public final class RedisNodeRegistry {
    private final RedisEventBus bus;
    private final Duration visibilityTtl;

    public RedisNodeRegistry(RedisEventBus bus, Duration visibilityTtl) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.visibilityTtl = Objects.requireNonNull(visibilityTtl, "visibilityTtl");
        if (visibilityTtl.isZero() || visibilityTtl.isNegative()) throw new IllegalArgumentException("visibilityTtl must be positive");
    }

    public CompletionStage<Boolean> heartbeat(NetworkNodeIdentity identity, String pluginVersion,
                                               String apiVersion, Instant now) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(now, "now");
        String nodeId = identity.nodeId();
        String hashKey = key("node:", nodeId);
        String indexKey = key("nodes:", "heartbeat");
        long epochMillis = now.toEpochMilli();
        int ttlSeconds = Math.max(1, (int) Math.ceil(visibilityTtl.toMillis() / 1000.0));
        Map<String, String> values = new HashMap<>();
        values.put("node_id", nodeId);
        values.put("group", identity.group());
        values.put("environment", identity.environment());
        values.put("tags", String.join(",", identity.tags()));
        values.put("plugin_version", pluginVersion == null ? "" : pluginVersion);
        values.put("api_version", apiVersion == null ? "" : apiVersion);
        values.put("heartbeat_at", Long.toString(epochMillis));
        return bus.execute(jedis -> {
            jedis.hset(hashKey, values);
            jedis.expire(hashKey, ttlSeconds);
            jedis.zadd(indexKey, epochMillis, nodeId);
            return true;
        });
    }

    public CompletionStage<List<NodeSnapshot>> visibleNodes(Instant now) {
        Objects.requireNonNull(now, "now");
        long cutoff = now.minus(visibilityTtl).toEpochMilli();
        String indexKey = key("nodes:", "heartbeat");
        return bus.execute(jedis -> readVisible(jedis, indexKey, cutoff));
    }

    private List<NodeSnapshot> readVisible(Jedis jedis, String indexKey, long cutoff) {
        jedis.zremrangeByScore(indexKey, Double.NEGATIVE_INFINITY, cutoff - 1);
        List<String> ids = jedis.zrangeByScore(indexKey, cutoff, Double.POSITIVE_INFINITY);
        List<NodeSnapshot> nodes = new ArrayList<>();
        for (String nodeId : ids) {
            Map<String, String> values = jedis.hgetAll(key("node:", nodeId));
            if (values.isEmpty()) continue;
            try {
                NetworkNodeIdentity identity = new NetworkNodeIdentity(values.get("node_id"), values.get("group"),
                        values.get("environment"), Arrays.stream(values.getOrDefault("tags", "").split(","))
                                .filter(tag -> !tag.isBlank()).collect(java.util.stream.Collectors.toSet()));
                Instant heartbeat = Instant.ofEpochMilli(Long.parseLong(values.get("heartbeat_at")));
                nodes.add(new NodeSnapshot(identity, values.getOrDefault("plugin_version", ""),
                        values.getOrDefault("api_version", ""), heartbeat));
            } catch (RuntimeException ignored) {
                // Malformed ephemeral data is ignored and never treated as a live node.
            }
        }
        nodes.sort(Comparator.comparing(node -> node.identity().nodeId()));
        return List.copyOf(nodes);
    }

    private String key(String prefix, String value) {
        return bus.config().keyPrefix() + prefix + value;
    }

    public record NodeSnapshot(NetworkNodeIdentity identity, String pluginVersion,
                               String apiVersion, Instant heartbeatAt) {
        public NodeSnapshot {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(pluginVersion, "pluginVersion");
            Objects.requireNonNull(apiVersion, "apiVersion");
            Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        }
    }
}

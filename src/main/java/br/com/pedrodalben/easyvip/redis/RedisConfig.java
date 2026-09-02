package br.com.pedrodalben.easyvip.redis;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Non-secret Redis transport settings. The URI may contain an ACL password and is never logged. */
public record RedisConfig(String uri, String channel, int timeoutMillis, int ioThreads, String keyPrefix) {
    public RedisConfig {
        uri = requireUri(uri);
        channel = required(channel, "channel");
        if (timeoutMillis < 100) throw new IllegalArgumentException("timeoutMillis must be at least 100");
        if (ioThreads < 1) throw new IllegalArgumentException("ioThreads must be positive");
        keyPrefix = required(keyPrefix, "keyPrefix");
        if (!keyPrefix.matches("[A-Za-z0-9:_-]+")) throw new IllegalArgumentException("keyPrefix contains unsupported characters");
    }

    public RedisConfig(String uri, String channel, int timeoutMillis, int ioThreads) {
        this(uri, channel, timeoutMillis, ioThreads, "easyvip:");
    }

    public RedisConfig(String uri, String channel, Duration timeout, int ioThreads) {
        this(uri, channel, Math.toIntExact(Objects.requireNonNull(timeout, "timeout").toMillis()), ioThreads, "easyvip:");
    }

    private static String requireUri(String value) {
        String uri = required(value, "uri");
        URI parsed = URI.create(uri);
        if (!"redis".equalsIgnoreCase(parsed.getScheme()) && !"rediss".equalsIgnoreCase(parsed.getScheme())) {
            throw new IllegalArgumentException("Redis URI must use redis:// or rediss://");
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("Redis URI must include a host");
        }
        return uri;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value.trim();
    }
}

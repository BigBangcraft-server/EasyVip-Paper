package br.com.pedrodalben.easyvip.network;

import br.com.pedrodalben.easyvip.api.DomainEvent;
import br.com.pedrodalben.easyvip.api.DomainEventType;
import br.com.pedrodalben.easyvip.api.NetworkNodeIdentity;
import br.com.pedrodalben.easyvip.cache.CachedEntitlementApi;
import br.com.pedrodalben.easyvip.event.VipActivateEvent;
import br.com.pedrodalben.easyvip.event.VipExpireEvent;
import br.com.pedrodalben.easyvip.redis.RedisEventBus;
import br.com.pedrodalben.easyvip.redis.RedisEventCodec;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bridges local lifecycle events to cache invalidation and optional Redis propagation. */
public final class NetworkEventListener implements Listener {
    private final CachedEntitlementApi api;
    private final RedisEventBus bus;
    private final NetworkNodeIdentity node;
    private final Clock clock;

    public NetworkEventListener(CachedEntitlementApi api, RedisEventBus bus,
                                NetworkNodeIdentity node, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.bus = bus;
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVipActivate(VipActivateEvent event) {
        invalidateAndPublish(event.getPlayerUuid(), DomainEventType.ENTITLEMENT_GRANTED,
                Map.of("tier_id", safe(event.getTierId()), "source", safe(event.getSource())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVipExpire(VipExpireEvent event) {
        invalidateAndPublish(event.getPlayerUuid(), DomainEventType.ENTITLEMENT_EXPIRED,
                Map.of("tier_id", safe(event.getTierId())));
    }

    private void invalidateAndPublish(UUID playerUuid, DomainEventType type, Map<String, String> attributes) {
        api.invalidate(playerUuid, 0L);
        if (bus == null || !bus.isRunning()) return;
        DomainEvent event = new DomainEvent(UUID.randomUUID(), type, RedisEventCodec.SCHEMA_VERSION,
                playerUuid, 0L, node.nodeId(), Instant.now(clock), attributes);
        bus.publish(event);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

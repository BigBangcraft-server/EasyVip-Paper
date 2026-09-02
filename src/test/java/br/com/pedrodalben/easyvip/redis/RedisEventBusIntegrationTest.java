package br.com.pedrodalben.easyvip.redis;

import br.com.pedrodalben.easyvip.api.DomainEvent;
import br.com.pedrodalben.easyvip.api.DomainEventType;
import br.com.pedrodalben.easyvip.api.NetworkNodeIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "EASYVIP_TEST_REDIS_URI", matches = ".+")
class RedisEventBusIntegrationTest {
    @Test
    void publishesEventsAndTracksEphemeralNodeHeartbeat() throws Exception {
        String channel = "easyvip.test." + UUID.randomUUID();
        RedisConfig config = new RedisConfig(System.getenv("EASYVIP_TEST_REDIS_URI"), channel, 1000, 1, "easyvip-test:");
        try (RedisEventBus publisher = new RedisEventBus(config); RedisEventBus subscriber = new RedisEventBus(config)) {
            AtomicReference<DomainEvent> received = new AtomicReference<>();
            subscriber.start(received::set);
            subscriber.subscriptionReady().toCompletableFuture().get(3, TimeUnit.SECONDS);

            DomainEvent event = new DomainEvent(UUID.randomUUID(), DomainEventType.CAPABILITIES_CHANGED, 1,
                    UUID.randomUUID(), 8L, "test-node", Instant.now(), Map.of("source", "integration"));
            publisher.publish(event).toCompletableFuture().get(3, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (received.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(event, received.get());

            RedisNodeRegistry registry = new RedisNodeRegistry(publisher, Duration.ofSeconds(30));
            NetworkNodeIdentity identity = new NetworkNodeIdentity("test-node", "test", "ci", Set.of("redis"));
            assertTrue(registry.heartbeat(identity, "test", "1.1", Instant.now()).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(registry.visibleNodes(Instant.now()).toCompletableFuture().get(3, TimeUnit.SECONDS).stream()
                    .anyMatch(node -> node.identity().equals(identity)));
        }
    }

    @Test
    void redisFailureCompletesAsynchronouslyWithoutThrowingOnCallerThread() throws Exception {
        RedisConfig config = new RedisConfig("redis://127.0.0.1:1", "easyvip.test.failure", 100, 1, "easyvip-test:");
        try (RedisEventBus bus = new RedisEventBus(config)) {
            assertThrows(Exception.class, () -> bus.ping().toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }
}

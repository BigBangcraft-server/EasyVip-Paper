package br.com.pedrodalben.easyvip.redis;

import br.com.pedrodalben.easyvip.api.DomainEvent;
import br.com.pedrodalben.easyvip.api.DomainEventType;
import br.com.pedrodalben.easyvip.api.EasyVipApi;
import br.com.pedrodalben.easyvip.api.EffectiveEntitlementView;
import br.com.pedrodalben.easyvip.api.PlayerEntitlementView;
import br.com.pedrodalben.easyvip.api.ScopeContext;
import br.com.pedrodalben.easyvip.cache.CachedEntitlementApi;
import br.com.pedrodalben.easyvip.cache.EntitlementCache;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RedisInfrastructureTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final ScopeContext CONTEXT = ScopeContext.network();

    @Test
    void eventCodecRoundTripsVersionedAndEscapedAttributes() {
        DomainEvent event = new DomainEvent(UUID.randomUUID(), DomainEventType.CAPABILITIES_CHANGED, 1,
                PLAYER, 42L, "bedwars|03", Instant.parse("2026-09-01T12:00:00Z"),
                Map.of("tier", "diamond,plus", "message", "a:b|c"));
        RedisEventCodec codec = new RedisEventCodec();

        String payload = codec.encode(event);
        assertEquals(event, codec.decode(payload));
        String[] fields = payload.split("\\|", -1);
        fields[3] = "2";
        assertThrows(IllegalArgumentException.class, () -> codec.decode(String.join("|", fields)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("ev1|bad"));
    }

    @Test
    void versionProcessorDropsDuplicatesAndOutOfOrderEvents() {
        RedisMetrics metrics = new RedisMetrics();
        List<DomainEvent> accepted = new CopyOnWriteArrayList<>();
        VersionAwareEventProcessor processor = new VersionAwareEventProcessor(10, 10, accepted::add, metrics);
        DomainEvent v2 = event(UUID.randomUUID(), 2L);
        DomainEvent v1 = event(UUID.randomUUID(), 1L);

        assertTrue(processor.accept(v2));
        assertFalse(processor.accept(v2));
        assertFalse(processor.accept(v1));
        assertEquals(List.of(v2), accepted);
        assertEquals(2, metrics.snapshot().ignoredEvents());
    }

    @Test
    void cacheIsBoundedByInvalidationAndAvoidsSecondLoad() {
        EntitlementCache cache = new EntitlementCache(1, Duration.ofMinutes(1));
        AtomicInteger loads = new AtomicInteger();
        EffectiveEntitlementView view = new EffectiveEntitlementView(PLAYER, CONTEXT, List.of(),
                new PlayerEntitlementView(Map.of("queue.priority", br.com.pedrodalben.easyvip.api.CapabilityValue.of(30))));

        assertSame(view, cache.get(PLAYER, CONTEXT, () -> { loads.incrementAndGet(); return view; }));
        assertSame(view, cache.get(PLAYER, CONTEXT, () -> { loads.incrementAndGet(); return view; }));
        assertEquals(1, loads.get());
        cache.invalidate(PLAYER, 4L);
        assertSame(view, cache.get(PLAYER, CONTEXT, () -> { loads.incrementAndGet(); return view; }));
        assertEquals(2, loads.get());
        assertEquals(1L, cache.stats().hitCount());
    }

    @Test
    void cachedApiDelegatesOnlyOnColdCacheAndSupportsAsyncMisses() {
        AtomicInteger loads = new AtomicInteger();
        EasyVipApi delegate = new EasyVipApi() {
            @Override public br.com.pedrodalben.easyvip.api.EntitlementService entitlements() { return this::player; }
            @Override public br.com.pedrodalben.easyvip.api.BenefitService benefits() { return this::player; }
            @Override public PlayerEntitlementView player(UUID uuid, ScopeContext context) {
                loads.incrementAndGet();
                return new PlayerEntitlementView(Map.of("economy.multiplier",
                        br.com.pedrodalben.easyvip.api.CapabilityValue.of(new BigDecimal("1.25"))));
            }
        };
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            CachedEntitlementApi api = new CachedEntitlementApi(delegate, new EntitlementCache(10, Duration.ofMinutes(1)), executor);
            assertEquals(new BigDecimal("1.25"), api.playerAsync(PLAYER, CONTEXT).toCompletableFuture().join()
                    .getDecimal("economy.multiplier", BigDecimal.ZERO));
            assertEquals(new BigDecimal("1.25"), api.player(PLAYER, CONTEXT).getDecimal("economy.multiplier", BigDecimal.ZERO));
            assertEquals(1, loads.get());
        }
    }

    @Test
    void cachedApiFailsClosedWhenAsyncExecutorIsSaturated() {
        EasyVipApi delegate = new EasyVipApi() {
            @Override public br.com.pedrodalben.easyvip.api.EntitlementService entitlements() { return this::player; }
            @Override public br.com.pedrodalben.easyvip.api.BenefitService benefits() { return this::player; }
            @Override public PlayerEntitlementView player(UUID uuid, ScopeContext context) {
                return new PlayerEntitlementView(Map.of());
            }
        };
        CachedEntitlementApi api = new CachedEntitlementApi(delegate, new EntitlementCache(10, Duration.ofMinutes(1)), command -> {
            throw new java.util.concurrent.RejectedExecutionException("test");
        });

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> api.playerAsync(PLAYER, CONTEXT).toCompletableFuture().join());
    }

    private static DomainEvent event(UUID eventId, long version) {
        return new DomainEvent(eventId, DomainEventType.ENTITLEMENT_UPDATED, 1, PLAYER, version,
                "node", Instant.parse("2026-09-01T12:00:00Z"), Map.of());
    }
}

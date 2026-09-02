package br.com.pedrodalben.easyvip.velocity;

import br.com.pedrodalben.easyvip.api.DomainEvent;
import br.com.pedrodalben.easyvip.api.EasyVipApi;
import br.com.pedrodalben.easyvip.api.NetworkNodeIdentity;
import br.com.pedrodalben.easyvip.cache.CachedEntitlementApi;
import br.com.pedrodalben.easyvip.cache.EntitlementCache;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.network.LegacyVipCapabilityBridge;
import br.com.pedrodalben.easyvip.redis.RedisConfig;
import br.com.pedrodalben.easyvip.redis.RedisEventBus;
import br.com.pedrodalben.easyvip.redis.RedisNodeRegistry;
import br.com.pedrodalben.easyvip.redis.VersionAwareEventProcessor;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(id = "easyvip-velocity", name = "EasyVip Velocity", version = "1.2.0",
        authors = {"pedro-dalben"}, description = "Network-wide EasyVip entitlement adapter")
public final class EasyVipVelocityPlugin implements AutoCloseable {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile CachedEntitlementApi api;
    private volatile EntitlementCache cache;
    private volatile RedisEventBus redis;
    private volatile RedisNodeRegistry nodes;
    private volatile NetworkNodeIdentity nodeIdentity;
    private volatile ExecutorService entitlementExecutor;
    private volatile ScheduledTask heartbeatTask;
    private final AtomicBoolean closed = new AtomicBoolean();

    @Inject
    public EasyVipVelocityPlugin(ProxyServer proxy, Logger logger,
                                 @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            EasyVipConfig.initialize(dataDirectory);
            EasyVipConfig.loadAll();
            var errors = EasyVipConfig.validate();
            if (!errors.isEmpty()) {
                errors.forEach(error -> logger.error(error));
                return;
            }
            PersistenceManager.initialize(dataDirectory);
            EasyVipApi legacy = LegacyVipCapabilityBridge.create(
                    () -> EasyVipConfig.tiers.list,
                    PersistenceManager::getPlayerVips,
                    Clock.systemUTC());
            cache = new EntitlementCache(EasyVipConfig.network.cacheMaximumEntries,
                    Duration.ofSeconds(EasyVipConfig.network.cacheTtlSeconds));
            entitlementExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(256), runnable -> {
                        Thread thread = new Thread(runnable, "EasyVip-Velocity-Entitlement");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            api = new CachedEntitlementApi(legacy, cache, entitlementExecutor);
            nodeIdentity = new NetworkNodeIdentity(EasyVipConfig.network.nodeId,
                    EasyVipConfig.network.group, EasyVipConfig.network.environment,
                    new HashSet<>(EasyVipConfig.network.tags));
            startRedis();
            CommandManager commands = proxy.getCommandManager();
            CommandMeta meta = commands.metaBuilder("easyvip").aliases("vip").plugin(this).build();
            commands.register(meta, new EasyVipVelocityCommand(this));
            logger.info("EasyVip Velocity enabled with API {}", EasyVipApi.API_VERSION);
        } catch (Exception exception) {
            logger.error("EasyVip Velocity failed to initialize safely: {}", exception.getClass().getSimpleName());
            close();
        }
    }

    private void startRedis() {
        if (!EasyVipConfig.network.redisEnabled) return;
        try {
            redis = new RedisEventBus(new RedisConfig(EasyVipConfig.network.redisUri,
                    EasyVipConfig.network.redisChannel, EasyVipConfig.network.redisTimeoutMillis,
                    EasyVipConfig.network.redisIoThreads, EasyVipConfig.network.redisKeyPrefix));
            VersionAwareEventProcessor processor = new VersionAwareEventProcessor(
                    EasyVipConfig.network.cacheMaximumEntries, EasyVipConfig.network.cacheMaximumEntries,
                    event -> api.invalidate(event.aggregateId(), event.aggregateVersion()), redis.metrics());
            redis.start(processor::accept);
            nodes = new RedisNodeRegistry(redis,
                    Duration.ofSeconds(Math.max(10, EasyVipConfig.network.heartbeatIntervalSeconds * 3L)));
            Runnable heartbeat = () -> nodes.heartbeat(nodeIdentity, "1.2.0", EasyVipApi.API_VERSION,
                    Clock.systemUTC().instant());
            heartbeat.run();
            heartbeatTask = proxy.getScheduler().buildTask(this, heartbeat)
                    .repeat(EasyVipConfig.network.heartbeatIntervalSeconds, TimeUnit.SECONDS).schedule();
            redis.ping().whenComplete((result, error) -> {
                if (error != null) logger.warn("Redis unavailable; SQL remains authoritative ({})",
                        error.getClass().getSimpleName());
                else logger.info("Redis network event bus connected ({})", result);
            });
        } catch (RuntimeException exception) {
            logger.warn("Redis disabled for this proxy runtime; SQL remains authoritative ({})",
                    exception.getClass().getSimpleName());
            if (redis != null) {
                redis.close();
                redis = null;
            }
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        close();
    }

    public CachedEntitlementApi api() { return api; }
    public EntitlementCache cache() { return cache; }
    public RedisEventBus redis() { return redis; }
    public RedisNodeRegistry nodes() { return nodes; }
    public NetworkNodeIdentity nodeIdentity() { return nodeIdentity; }

    public java.util.concurrent.CompletionStage<Integer> queuePriority(java.util.UUID playerUuid) {
        return capabilityInt(playerUuid, "queue.priority", 0);
    }

    public java.util.concurrent.CompletionStage<Boolean> reservedSlot(java.util.UUID playerUuid) {
        return capabilityBoolean(playerUuid, "network.reserved_slot", false);
    }

    public java.util.concurrent.CompletionStage<Boolean> maintenanceBypass(java.util.UUID playerUuid) {
        return capabilityBoolean(playerUuid, "network.maintenance_bypass", false);
    }

    private java.util.concurrent.CompletionStage<Integer> capabilityInt(java.util.UUID playerUuid,
                                                                         String capability, int fallback) {
        CachedEntitlementApi current = api;
        if (current == null) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("EasyVip is unavailable"));
        return current.playerAsync(playerUuid, br.com.pedrodalben.easyvip.api.ScopeContext.network())
                .thenApply(view -> view.getInt(capability, fallback));
    }

    private java.util.concurrent.CompletionStage<Boolean> capabilityBoolean(java.util.UUID playerUuid,
                                                                             String capability, boolean fallback) {
        CachedEntitlementApi current = api;
        if (current == null) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("EasyVip is unavailable"));
        return current.playerAsync(playerUuid, br.com.pedrodalben.easyvip.api.ScopeContext.network())
                .thenApply(view -> view.getBoolean(capability, fallback));
    }

    public java.util.concurrent.CompletionStage<Long> publish(DomainEvent event) {
        RedisEventBus transport = redis;
        if (transport == null || !transport.isRunning()) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Redis is disabled"));
        }
        return transport.publish(event);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ScheduledTask task = heartbeatTask;
        if (task != null) {
            task.cancel();
            heartbeatTask = null;
        }
        RedisEventBus transport = redis;
        if (transport != null) {
            transport.close();
            redis = null;
        }
        ExecutorService executor = entitlementExecutor;
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            entitlementExecutor = null;
        }
        if (cache != null) {
            cache.invalidateAll();
            cache = null;
        }
        PersistenceManager.shutdown();
        api = null;
    }
}

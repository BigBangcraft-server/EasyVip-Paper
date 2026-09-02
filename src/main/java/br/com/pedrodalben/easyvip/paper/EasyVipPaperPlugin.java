package br.com.pedrodalben.easyvip.paper;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.api.EasyVipApi;
import br.com.pedrodalben.easyvip.api.NetworkNodeIdentity;
import br.com.pedrodalben.easyvip.api.PlayerEntitlementView;
import br.com.pedrodalben.easyvip.cache.CachedEntitlementApi;
import br.com.pedrodalben.easyvip.cache.EntitlementCache;
import br.com.pedrodalben.easyvip.command.EasyVipCommandHandler;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.listener.PlayerListener;
import br.com.pedrodalben.easyvip.network.LegacyVipCapabilityBridge;
import br.com.pedrodalben.easyvip.network.NetworkEventListener;
import br.com.pedrodalben.easyvip.redis.RedisConfig;
import br.com.pedrodalben.easyvip.redis.RedisEventBus;
import br.com.pedrodalben.easyvip.redis.RedisNodeRegistry;
import br.com.pedrodalben.easyvip.redis.VersionAwareEventProcessor;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager;
import br.com.pedrodalben.easyvip.platform.PaperPlatformBridge;
import br.com.pedrodalben.easyvip.platform.PermissionBridge;
import br.com.pedrodalben.easyvip.platform.TextUtil;
import br.com.pedrodalben.easyvip.platform.VaultEconomyBridge;
import br.com.pedrodalben.easyvip.service.ExpirationService;
import br.com.pedrodalben.easyvip.webstore.WebStoreFulfillmentService;
import br.com.pedrodalben.easyvip.webstore.WebStoreSyncService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class EasyVipPaperPlugin extends JavaPlugin {

    private static EasyVipPaperPlugin instance;
    private EasyVipApi easyVipApi;
    private CachedEntitlementApi cachedEntitlementApi;
    private EntitlementCache entitlementCache;
    private RedisEventBus redisEventBus;
    private RedisNodeRegistry networkNodes;
    private ScheduledExecutorService networkScheduler;
    private ExecutorService entitlementExecutor;

    public static EasyVipPaperPlugin getInstance() {
        return instance;
    }

    /** Runs health and delivery inspection off the server thread and never includes secrets. */
    public CompletionStage<String> networkStatusAsync() {
        ExecutorService executor = entitlementExecutor;
        if (executor == null) {
            return CompletableFuture.completedFuture("state=unavailable");
        }
        return CompletableFuture.supplyAsync(() -> {
            var sql = SqlDatabaseManager.healthSnapshot();
            String sqlState = !EasyVipConfig.integrations.sqlEnabled ? "disabled"
                    : (sql.healthy() ? "healthy" : "unhealthy");
            String redisState = redisEventBus == null ? "disabled"
                    : (redisEventBus.isRunning() ? "running" : "stopped");
            String cacheState = entitlementCache == null ? "disabled"
                    : "entries=" + entitlementCache.estimatedSize()
                    + ",hits=" + entitlementCache.stats().hitCount()
                    + ",misses=" + entitlementCache.stats().missCount();
            var metrics = redisEventBus == null ? null : redisEventBus.metrics().snapshot();
            String redisMetrics = metrics == null ? "n/a"
                    : "published=" + metrics.published() + ",received=" + metrics.received()
                    + ",invalid=" + metrics.invalidEvents() + ",ignored=" + metrics.ignoredEvents();
            return "node=" + EasyVipConfig.network.nodeId
                    + " sql=" + sqlState
                    + " pool=" + sql.active() + "/" + sql.total() + ",waiting=" + sql.waiting()
                    + " deliveries=claimed:" + sql.claimedDeliveries()
                    + ",delivered:" + sql.deliveredDeliveries()
                    + ",failed:" + sql.failedDeliveries()
                    + " redis=" + redisState + " (" + redisMetrics + ")"
                    + " cache=" + cacheState;
        }, executor);
    }

    public CompletionStage<String> networkDoctorAsync() {
        return networkStatusAsync().thenApply(status -> {
            boolean sqlReady = status.contains("sql=healthy") || status.contains("sql=disabled");
            boolean redisReady = !EasyVipConfig.network.redisEnabled || status.contains("redis=running");
            String verdict = sqlReady && redisReady ? "PASS" : "WARN";
            return "doctor=" + verdict + " " + status;
        });
    }

    public CompletionStage<PlayerEntitlementView> playerCapabilitiesAsync(java.util.UUID playerUuid) {
        CachedEntitlementApi api = cachedEntitlementApi;
        if (api == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("EasyVip is unavailable"));
        }
        return api.playerAsync(playerUuid, br.com.pedrodalben.easyvip.api.ScopeContext.network());
    }

    public RedisNodeRegistry networkNodes() {
        return networkNodes;
    }

    /** Platform entry point for other plugins; the API itself has no Paper dependency. */
    public EasyVipApi getEasyVipApi() {
        if (easyVipApi == null) {
            throw new IllegalStateException("EasyVip is not enabled");
        }
        return easyVipApi;
    }

    @Override
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();

        getLogger().info("=========================================");
        getLogger().info(" EasyVip - Modern VIP & Key Management  ");
        getLogger().info(" Version: " + getDescription().getVersion());
        getLogger().info(" Platform: Paper 26.2 (Java 25)");
        getLogger().info("=========================================");

        Path dataDir = getDataFolder().toPath();

        // 1. Initialize configuration system
        try {
            EasyVipConfig.initialize(dataDir);
            EasyVipConfig.loadAll();
            java.util.List<String> configErrors = EasyVipConfig.validate();
            if (!configErrors.isEmpty()) {
                for (String error : configErrors) {
                    getLogger().severe(error);
                }
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("Configurations loaded: " + EasyVipConfig.tiers.list.size() + " tiers, "
                    + EasyVipConfig.packages.list.size() + " packages, "
                    + EasyVipConfig.rewardKeys.list.size() + " reward keys.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize EasyVip configurations: " + e.getClass().getSimpleName());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Initialize persistence (JSON atomic with backup or SQL)
        try {
            PersistenceManager.initialize(dataDir);
            getLogger().info("Persistence initialized in "
                    + (PersistenceManager.isSqlMode() ? "SQL" : "JSON") + " mode.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize EasyVip persistence manager: " + e.getClass().getSimpleName());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        EasyVipApi legacyApi = LegacyVipCapabilityBridge.create(
                () -> EasyVipConfig.tiers.list,
                PersistenceManager::getPlayerVips,
                Clock.systemUTC());
        entitlementCache = new EntitlementCache(EasyVipConfig.network.cacheMaximumEntries,
                Duration.ofSeconds(EasyVipConfig.network.cacheTtlSeconds));
        entitlementExecutor = Executors.newFixedThreadPool(2, daemonFactory("EasyVip-Entitlement"));
        cachedEntitlementApi = new CachedEntitlementApi(legacyApi, entitlementCache, entitlementExecutor);
        easyVipApi = cachedEntitlementApi;

        NetworkNodeIdentity networkNode = new NetworkNodeIdentity(EasyVipConfig.network.nodeId,
                EasyVipConfig.network.group, EasyVipConfig.network.environment,
                new java.util.HashSet<>(EasyVipConfig.network.tags));
        if (EasyVipConfig.network.redisEnabled) {
            try {
                RedisConfig redisConfig = new RedisConfig(EasyVipConfig.network.redisUri,
                        EasyVipConfig.network.redisChannel, EasyVipConfig.network.redisTimeoutMillis,
                        EasyVipConfig.network.redisIoThreads, EasyVipConfig.network.redisKeyPrefix);
                redisEventBus = new RedisEventBus(redisConfig);
                VersionAwareEventProcessor processor = new VersionAwareEventProcessor(
                        EasyVipConfig.network.cacheMaximumEntries, EasyVipConfig.network.cacheMaximumEntries,
                        event -> cachedEntitlementApi.invalidate(event.aggregateId(), event.aggregateVersion()),
                        redisEventBus.metrics());
                redisEventBus.start(processor::accept);
                networkNodes = new RedisNodeRegistry(redisEventBus,
                        Duration.ofSeconds(Math.max(10, EasyVipConfig.network.heartbeatIntervalSeconds * 3L)));
                networkScheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("EasyVip-Network"));
                Runnable heartbeat = () -> networkNodes.heartbeat(networkNode, getDescription().getVersion(),
                        br.com.pedrodalben.easyvip.api.EasyVipApi.API_VERSION, Clock.systemUTC().instant());
                heartbeat.run();
                networkScheduler.scheduleAtFixedRate(heartbeat,
                        EasyVipConfig.network.heartbeatIntervalSeconds,
                        EasyVipConfig.network.heartbeatIntervalSeconds, TimeUnit.SECONDS);
                redisEventBus.ping().whenComplete((result, error) -> {
                    if (error != null) getLogger().warning("Redis unavailable; SQL remains authoritative and cache will use TTL: " + error.getClass().getSimpleName());
                    else getLogger().info("Redis network event bus connected (" + result + ").");
                });
            } catch (RuntimeException exception) {
                getLogger().warning("Redis disabled for this runtime; SQL remains authoritative: " + exception.getClass().getSimpleName());
                if (redisEventBus != null) {
                    redisEventBus.close();
                    redisEventBus = null;
                }
                networkNodes = null;
            }
        }
        getServer().getPluginManager().registerEvents(new NetworkEventListener(cachedEntitlementApi,
                redisEventBus, networkNode, Clock.systemUTC()), this);

        // 3. Setup bridges
        ActionExecutor.setPlatform(new PaperPlatformBridge());
        ActionExecutor.setEconomy(new VaultEconomyBridge());

        // 4. Initialize webstore sync
        WebStoreSyncService.init(dataDir);

        // 5. Register command executor and tab completers
        EasyVipCommandHandler commandHandler = new EasyVipCommandHandler(this);
        registerCommand("easyvip", commandHandler);
        registerCommand("usekey", commandHandler);
        registerCommand("activate", commandHandler);
        registerCommand("vip", commandHandler);
        registerCommand("viptime", commandHandler);
        registerCommand("link", commandHandler);

        // 6. Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(cachedEntitlementApi), this);

        // 7. Start background services
        ExpirationService.start(this);
        WebStoreFulfillmentService.start(dataDir);

        // 8. Log integrations
        if (PermissionBridge.isLuckPermsPresent() && EasyVipConfig.integrations.luckpermsEnabled) {
            getLogger().info("Integration: LuckPerms hooked successfully.");
        }
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            getLogger().info("Integration: Vault detected and hooked.");
        }

        long loadDuration = System.currentTimeMillis() - startTime;
        getLogger().info("EasyVip enabled successfully in " + loadDuration + "ms!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling EasyVip...");

        WebStoreFulfillmentService.stop();
        ExpirationService.stop();
        PersistenceManager.shutdown();

        closeNetworkRuntime();
        easyVipApi = null;
        instance = null;
        getLogger().info("EasyVip disabled cleanly. Goodbye!");
    }

    private void registerCommand(String name, EasyVipCommandHandler handler) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            getLogger().warning("Could not register command /" + name + " (missing in plugin.yml)");
        }
    }

    private void closeNetworkRuntime() {
        if (networkScheduler != null) {
            networkScheduler.shutdownNow();
            networkScheduler = null;
        }
        if (redisEventBus != null) {
            redisEventBus.close();
            redisEventBus = null;
        }
        networkNodes = null;
        if (entitlementExecutor != null) {
            entitlementExecutor.shutdownNow();
            entitlementExecutor = null;
        }
        if (entitlementCache != null) {
            entitlementCache.invalidateAll();
            entitlementCache = null;
        }
        cachedEntitlementApi = null;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        };
    }
}

package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ExpirationService {

    private static ScheduledExecutorService scheduler;
    private static Plugin pluginInstance;

    private ExpirationService() {
    }

    public static synchronized void start(Plugin plugin) {
        pluginInstance = plugin;
        if (scheduler != null) return;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "EasyVip-Expiration-Scheduler");
            thread.setDaemon(true);
            return thread;
        });

        runOnServer(VipService::expireAllDueVips);
        scheduler.execute(ExpirationService::cleanupPendingVariants);

        long interval = EasyVipConfig.common.autoExpireIntervalSeconds;
        if (interval < 5) {
            interval = 5; // Enforce minimum interval
        }

        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupPendingVariants();
            } catch (Exception e) {
                System.err.println("[EasyVip] Error in expiration scheduler tick: " + e.getClass().getSimpleName());
            }
            runOnServer(VipService::expireAllDueVips);
        }, interval, interval, TimeUnit.SECONDS);
    }

    private static void cleanupPendingVariants() {
        try {
            PackageService.cleanupExpiredPendingVariants();
        } catch (Throwable exception) {
            System.err.println("[EasyVip] Pending variant cleanup failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    public static synchronized void reload(Plugin plugin) {
        stop();
        start(plugin);
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            scheduler = null;
        }
    }

    private static void runOnServer(Runnable task) {
        if (pluginInstance == null || !pluginInstance.isEnabled()) {
            return;
        }

        try {
            // Check Folia global region scheduler
            Method getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object globalScheduler = getGlobalRegionScheduler.invoke(null);
            Method runMethod = globalScheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
            Consumer<Object> consumer = (scheduledTask) -> task.run();
            runMethod.invoke(globalScheduler, pluginInstance, consumer);
            return;
        } catch (Throwable ignored) {
            // Standard Paper / Bukkit
        }

        try {
            Bukkit.getScheduler().runTask(pluginInstance, task);
        } catch (Throwable ignored) {
            // In case Bukkit is not fully running or in test environment
            try {
                task.run();
            } catch (Throwable ignored2) {
            }
        }
    }
}

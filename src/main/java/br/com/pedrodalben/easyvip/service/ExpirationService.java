package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

        runExpirationAsync();
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
            runExpirationAsync();
        }, interval, interval, TimeUnit.SECONDS);
    }

    private static void runExpirationAsync() {
        Plugin plugin = pluginInstance;
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            VipService.expireAllDueVipsAsync(plugin).whenComplete((expired, error) -> {
                if (error != null) {
                    System.err.println("[EasyVip] Expiration pass failed: "
                            + error.getClass().getSimpleName());
                }
            });
        } catch (Throwable error) {
            System.err.println("[EasyVip] Expiration pass could not start: "
                    + error.getClass().getSimpleName());
        }
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

}

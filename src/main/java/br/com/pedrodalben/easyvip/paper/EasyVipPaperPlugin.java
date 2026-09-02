package br.com.pedrodalben.easyvip.paper;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.api.EasyVipApi;
import br.com.pedrodalben.easyvip.command.EasyVipCommandHandler;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.listener.PlayerListener;
import br.com.pedrodalben.easyvip.network.LegacyVipCapabilityBridge;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
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

public final class EasyVipPaperPlugin extends JavaPlugin {

    private static EasyVipPaperPlugin instance;
    private EasyVipApi easyVipApi;

    public static EasyVipPaperPlugin getInstance() {
        return instance;
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
            getLogger().severe("Failed to initialize EasyVip configurations: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Initialize persistence (JSON atomic with backup or SQL)
        try {
            PersistenceManager.initialize(dataDir);
            getLogger().info("Persistence initialized in "
                    + (PersistenceManager.isSqlMode() ? "SQL (" + EasyVipConfig.integrations.sqlUrl + ")" : "JSON") + " mode.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize EasyVip persistence manager: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        easyVipApi = LegacyVipCapabilityBridge.create(
                () -> EasyVipConfig.tiers.list,
                PersistenceManager::getPlayerVips,
                Clock.systemUTC());

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
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);

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
}

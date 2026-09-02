package br.com.pedrodalben.easyvip.config;

import br.com.pedrodalben.easyvip.webstore.WebStoreConfig;
import br.com.pedrodalben.easyvip.webstore.FulfillmentConfig;
import br.com.pedrodalben.easyvip.webstore.FulfillmentKeyConfig;
import br.com.pedrodalben.easyvip.webstore.FulfillmentProductConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class EasyVipConfig {

    private static Path configDir;

    public static final CommonConfig common = new CommonConfig();
    public static final MessagesConfig messages = new MessagesConfig();
    public static final PoolsConfig pools = new PoolsConfig();
    public static final TiersConfig tiers = new TiersConfig();
    public static final PackagesConfig packages = new PackagesConfig();
    public static final RewardKeysConfig rewardKeys = new RewardKeysConfig();
    public static final IntegrationsConfig integrations = new IntegrationsConfig();
    public static final WebStoreConfig webstore = new WebStoreConfig();
    public static final FulfillmentConfig fulfillment = new FulfillmentConfig();

    private EasyVipConfig() {
    }

    public static synchronized void initialize(Path dir) {
        configDir = dir;
    }

    public static void loadAll() throws IllegalArgumentException, IOException {
        Files.createDirectories(configDir);

        loadCommon();
        loadMessages();
        loadPools();
        loadTiers();
        loadPackages();
        loadRewardKeys();
        loadIntegrations();
        loadWebStore();
    }

    // ─── Pools Config ───────────────────────────────────────
    public static class PoolsConfig {
        public final Map<String, RandomPoolDefinition> list = new LinkedHashMap<>();
    }

    public static class RandomPoolDefinition {
        public final List<String> values = new ArrayList<>();
        public final List<RandomPoolEntry> weighted = new ArrayList<>();
    }

    public static class RandomPoolEntry {
        public String value;
        public double weight = 1.0d;
    }

    private static Path activationItemsDir() {
        return configDir.resolve("activation_items");
    }

    private static Path activationItemsFile(String tierId) {
        return activationItemsDir().resolve(tierId + ".toml");
    }

    private static void loadPools() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("pools.toml");
        if (!Files.exists(file)) {
            TomlWriter.writeFile(file, buildPoolsToml());
        }

        pools.list.clear();

        Map<String, Object> parsed = TomlParser.parseFile(file);
        Map<String, Object> poolsData = asMap(parsed.get("pools"));
        if (poolsData == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : poolsData.entrySet()) {
            Map<String, Object> poolData = asMap(entry.getValue());
            if (poolData == null) {
                continue;
            }

            RandomPoolDefinition def = new RandomPoolDefinition();
            def.values.addAll(getStringList(poolData, "values", def.values));

            Object weightedData = poolData.get("weighted");
            if (weightedData instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> weightedList = (List<Object>) weightedData;
                for (Object item : weightedList) {
                    Map<String, Object> itemMap = asMap(item);
                    if (itemMap == null) {
                        continue;
                    }
                    RandomPoolEntry poolEntry = new RandomPoolEntry();
                    poolEntry.value = getString(itemMap, "value", "");
                    poolEntry.weight = getDouble(itemMap, "weight", 1.0d);
                    def.weighted.add(poolEntry);
                }
            }

            pools.list.put(entry.getKey(), def);
        }
    }

    private static Map<String, Object> buildPoolsToml() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> poolsTable = new LinkedHashMap<>();
        map.put("pools", poolsTable);

        Map<String, Object> shinyPokemon = new LinkedHashMap<>();
        shinyPokemon.put("values", Arrays.asList(
                "Pikachu",
                "Bulbasaur",
                "Charmander",
                "Squirtle",
                "Charizard",
                "Gengar",
                "Lucario",
                "Gardevoir"
        ));
        poolsTable.put("shiny_pokemon", shinyPokemon);

        Map<String, Object> vipItems = new LinkedHashMap<>();
        vipItems.put("values", Arrays.asList("diamond", "emerald", "nether_star", "experience_bottle"));
        poolsTable.put("vip_items", vipItems);

        return map;
    }

    // ─── Common Config ──────────────────────────────────────
    public static class CommonConfig {
        public String language = "en-us";
        public int keyLength = 12;
        public String keyPrefix = "EVIP-";
        public String keyCharset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        public boolean caseSensitiveKeys = false;
        public boolean confirmBeforeUse = true;
        public int confirmTimeoutSeconds = 30;
        public int commandCooldownTicks = 20;
        public List<String> allowedDimensions = new ArrayList<>();
        public List<String> denyDimensions = new ArrayList<>();
        public int autoExpireIntervalSeconds = 30;
        public int variantSelectionTimeoutSeconds = 86400;
        public boolean notifyPendingVariantOnLogin = true;
        public String itemKeyItemId = "minecraft:tripwire_hook";
        public String itemKeyMarker = "easyvip_item_key";
        public String defaultActivationMode = "extend";
        public boolean forceHighestPriorityAsActive = false;
        public boolean allowPlayerActiveSelection = true;
        public boolean logToFile = true;
        public boolean debug = false;
        public boolean commandAllowlistEnabled = true;
        public List<String> commandAllowlist = Arrays.asList("ftbranks ", "team ", "effect ", "give ", "broadcast ");
    }

    private static void loadCommon() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("common.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("language", common.language);
            map.put("key_length", common.keyLength);
            map.put("key_prefix", common.keyPrefix);
            map.put("key_charset", common.keyCharset);
            map.put("case_sensitive_keys", common.caseSensitiveKeys);
            map.put("confirm_before_use", common.confirmBeforeUse);
            map.put("confirm_timeout_seconds", common.confirmTimeoutSeconds);
            map.put("command_cooldown_ticks", common.commandCooldownTicks);
            map.put("allowed_dimensions", common.allowedDimensions);
            map.put("deny_dimensions", common.denyDimensions);
            map.put("auto_expire_interval_seconds", common.autoExpireIntervalSeconds);
            map.put("variant_selection_timeout_seconds", common.variantSelectionTimeoutSeconds);
            map.put("notify_pending_variant_on_login", common.notifyPendingVariantOnLogin);
            map.put("item_key_item_id", common.itemKeyItemId);
            map.put("item_key_marker", common.itemKeyMarker);
            map.put("default_activation_mode", common.defaultActivationMode);
            map.put("force_highest_priority_as_active", common.forceHighestPriorityAsActive);
            map.put("allow_player_active_selection", common.allowPlayerActiveSelection);
            map.put("log_to_file", common.logToFile);
            map.put("debug", common.debug);
            map.put("command_allowlist_enabled", common.commandAllowlistEnabled);
            map.put("command_allowlist", common.commandAllowlist);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> data = TomlParser.parseFile(file);
        common.language = getString(data, "language", "en-us");
        common.keyLength = getInt(data, "key_length", 12);
        common.keyPrefix = getString(data, "key_prefix", "EVIP-");
        common.keyCharset = getString(data, "key_charset", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        common.caseSensitiveKeys = getBoolean(data, "case_sensitive_keys", false);
        common.confirmBeforeUse = getBoolean(data, "confirm_before_use", true);
        common.confirmTimeoutSeconds = getInt(data, "confirm_timeout_seconds", 30);
        common.commandCooldownTicks = getInt(data, "command_cooldown_ticks", 20);
        common.allowedDimensions = getStringList(data, "allowed_dimensions", new ArrayList<>());
        common.denyDimensions = getStringList(data, "deny_dimensions", new ArrayList<>());
        common.autoExpireIntervalSeconds = getInt(data, "auto_expire_interval_seconds", 30);
        common.variantSelectionTimeoutSeconds = getInt(data, "variant_selection_timeout_seconds", 86400);
        common.notifyPendingVariantOnLogin = getBoolean(data, "notify_pending_variant_on_login", true);
        common.itemKeyItemId = getString(data, "item_key_item_id", "minecraft:tripwire_hook");
        common.itemKeyMarker = getString(data, "item_key_marker", "easyvip_item_key");
        common.defaultActivationMode = getString(data, "default_activation_mode", "extend");
        common.forceHighestPriorityAsActive = getBoolean(data, "force_highest_priority_as_active", false);
        common.allowPlayerActiveSelection = getBoolean(data, "allow_player_active_selection", true);
        common.logToFile = getBoolean(data, "log_to_file", true);
        common.debug = getBoolean(data, "debug", false);
        common.commandAllowlistEnabled = getBoolean(data, "command_allowlist_enabled", true);
        common.commandAllowlist = getStringList(data, "command_allowlist", common.commandAllowlist);
    }

    // ─── Messages Config ────────────────────────────────────
    public static class MessagesConfig {
        public String prefix = "&7[&eEasyVip&7] ";
        public String noPermission = "&cVocê não tem permissão para usar este comando.";
        public String playerOnly = "&cEste comando só pode ser usado por jogadores.";
        public String adminOnly = "&cEste comando só pode ser usado por administradores.";
        public String invalidPlayer = "&cJogador não encontrado.";
        public String invalidTier = "&cTier VIP inválido.";
        public String invalidDuration = "&cDuração inválida.";
        public String invalidKey = "&cA chave inserida é inválida.";
        public String keyExpired = "&cEsta chave expirou.";
        public String keyNoUsesLeft = "&cEsta chave não possui mais usos.";
        public String keyAlreadyUsed = "&cEsta chave já foi usada.";
        public String keyBoundToOtherPlayer = "&cEsta chave está vinculada a outro jogador.";
        public String keyConfirmRequired = "&eEsta chave ativará o VIP {tier_display} por {duration}. Digite /easyvip confirm para confirmar.";
        public String keyRewardConfirmRequired = "&eEsta chave entregará o pacote de benefícios {reward_display}. Digite /easyvip confirm para confirmar.";
        public String keyConfirmed = "&aChave confirmada com sucesso!";
        public String rewardGiven = "&aPacote de benefícios {reward_display} entregue com sucesso!";
        public String vipActivated = "&aVIP {tier_display} ativado por {duration}!";
        public String vipExtended = "&aVIP {tier_display} estendido por mais {duration}!";
        public String vipSet = "&aVIP {tier_display} definido para {player} por {duration}!";
        public String vipRemoved = "&aVIP {tier_display} removido de {player}!";
        public String vipExpired = "&cSeu VIP {tier_display} expirou.";
        public String vipNotFound = "&cVocê não possui este VIP ativo.";
        public String vipTimeHeader = "&e--- Seus VIPs ---";
        public String vipTimeLine = "&7- {tier_display}: &f{duration_left} restante";
        public String activeVipChanged = "&aSeu VIP ativo foi alterado para {tier_display}.";
        public String activeVipNotOwned = "&cVocê não possui este tier VIP.";
        public String packageGiven = "&aVocê recebeu o pacote {package}!";
        public String packageNotFound = "&cPacote não encontrado.";
        public String variantPending = "&eVocê possui uma escolha de variante pendente para o pacote {package}. Use /easyvip variant choose {package} <variante> para escolher.";
        public String variantSelected = "&aVariante {variant} selecionada com sucesso!";
        public String variantInvalid = "&cVariante inválida. Escolhas permitidas: {allowed_variants}";
        public String reloadSuccess = "&aConfigurações recarregadas com sucesso!";
        public String reloadError = "&cErro ao recarregar configurações: {error}";
        public String configInvalid = "&cConfiguração inválida encontrada.";
        public String vipActivatedBroadcast = "&6[&eEasyVip&6] &aO player &e{player} &aativou o VIP &b{tier_display}&a. Parabéns!";
        public String vipLuckyItemBroadcast = "&6[&eEasyVip&6] &e%player% &6ganhou um item lendário ao ativar o VIP &b%tier_display%&6!";
        public String durationPermanent = "permanente";
    }

    private static void loadMessages() throws IllegalArgumentException, IOException {
        applyMessageDefaults(normalizeLanguage(common.language));
        Path file = configDir.resolve("messages.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("prefix", messages.prefix);
            map.put("no_permission", messages.noPermission);
            map.put("player_only", messages.playerOnly);
            map.put("admin_only", messages.adminOnly);
            map.put("invalid_player", messages.invalidPlayer);
            map.put("invalid_tier", messages.invalidTier);
            map.put("invalid_duration", messages.invalidDuration);
            map.put("invalid_key", messages.invalidKey);
            map.put("key_expired", messages.keyExpired);
            map.put("key_no_uses_left", messages.keyNoUsesLeft);
            map.put("key_already_used", messages.keyAlreadyUsed);
            map.put("key_bound_to_other_player", messages.keyBoundToOtherPlayer);
            map.put("key_confirm_required", messages.keyConfirmRequired);
            map.put("key_reward_confirm_required", messages.keyRewardConfirmRequired);
            map.put("key_confirmed", messages.keyConfirmed);
            map.put("reward_given", messages.rewardGiven);
            map.put("vip_activated", messages.vipActivated);
            map.put("vip_extended", messages.vipExtended);
            map.put("vip_set", messages.vipSet);
            map.put("vip_removed", messages.vipRemoved);
            map.put("vip_expired", messages.vipExpired);
            map.put("vip_not_found", messages.vipNotFound);
            map.put("vip_time_header", messages.vipTimeHeader);
            map.put("vip_time_line", messages.vipTimeLine);
            map.put("active_vip_changed", messages.activeVipChanged);
            map.put("active_vip_not_owned", messages.activeVipNotOwned);
            map.put("package_given", messages.packageGiven);
            map.put("package_not_found", messages.packageNotFound);
            map.put("variant_pending", messages.variantPending);
            map.put("variant_selected", messages.variantSelected);
            map.put("variant_invalid", messages.variantInvalid);
            map.put("reload_success", messages.reloadSuccess);
            map.put("reload_error", messages.reloadError);
            map.put("config_invalid", messages.configInvalid);
            map.put("vip_activated_broadcast", messages.vipActivatedBroadcast);
            map.put("vip_lucky_item_broadcast", messages.vipLuckyItemBroadcast);
            map.put("duration_permanent", messages.durationPermanent);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> data = TomlParser.parseFile(file);
        messages.prefix = getString(data, "prefix", messages.prefix);
        messages.noPermission = getString(data, "no_permission", messages.noPermission);
        messages.playerOnly = getString(data, "player_only", messages.playerOnly);
        messages.adminOnly = getString(data, "admin_only", messages.adminOnly);
        messages.invalidPlayer = getString(data, "invalid_player", messages.invalidPlayer);
        messages.invalidTier = getString(data, "invalid_tier", messages.invalidTier);
        messages.invalidDuration = getString(data, "invalid_duration", messages.invalidDuration);
        messages.invalidKey = getString(data, "invalid_key", messages.invalidKey);
        messages.keyExpired = getString(data, "key_expired", messages.keyExpired);
        messages.keyNoUsesLeft = getString(data, "key_no_uses_left", messages.keyNoUsesLeft);
        messages.keyAlreadyUsed = getString(data, "key_already_used", messages.keyAlreadyUsed);
        messages.keyBoundToOtherPlayer = getString(data, "key_bound_to_other_player", messages.keyBoundToOtherPlayer);
        messages.keyConfirmRequired = getString(data, "key_confirm_required", messages.keyConfirmRequired);
        messages.keyRewardConfirmRequired = getString(data, "key_reward_confirm_required", messages.keyRewardConfirmRequired);
        messages.keyConfirmed = getString(data, "key_confirmed", messages.keyConfirmed);
        messages.rewardGiven = getString(data, "reward_given", messages.rewardGiven);
        messages.vipActivated = getString(data, "vip_activated", messages.vipActivated);
        messages.vipExtended = getString(data, "vip_extended", messages.vipExtended);
        messages.vipSet = getString(data, "vip_set", messages.vipSet);
        messages.vipRemoved = getString(data, "vip_removed", messages.vipRemoved);
        messages.vipExpired = getString(data, "vip_expired", messages.vipExpired);
        messages.vipNotFound = getString(data, "vip_not_found", messages.vipNotFound);
        messages.vipTimeHeader = getString(data, "vip_time_header", messages.vipTimeHeader);
        messages.vipTimeLine = getString(data, "vip_time_line", messages.vipTimeLine);
        messages.activeVipChanged = getString(data, "active_vip_changed", messages.activeVipChanged);
        messages.activeVipNotOwned = getString(data, "active_vip_not_owned", messages.activeVipNotOwned);
        messages.packageGiven = getString(data, "package_given", messages.packageGiven);
        messages.packageNotFound = getString(data, "package_not_found", messages.packageNotFound);
        messages.variantPending = getString(data, "variant_pending", messages.variantPending);
        messages.variantSelected = getString(data, "variant_selected", messages.variantSelected);
        messages.variantInvalid = getString(data, "variant_invalid", messages.variantInvalid);
        messages.reloadSuccess = getString(data, "reload_success", messages.reloadSuccess);
        messages.reloadError = getString(data, "reload_error", messages.reloadError);
        messages.configInvalid = getString(data, "config_invalid", messages.configInvalid);
        messages.vipActivatedBroadcast = getString(data, "vip_activated_broadcast", messages.vipActivatedBroadcast);
        messages.vipLuckyItemBroadcast = getString(data, "vip_lucky_item_broadcast", messages.vipLuckyItemBroadcast);
        messages.durationPermanent = getString(data, "duration_permanent", messages.durationPermanent);
    }

    // ─── Tiers Config ───────────────────────────────────────
    public static class TiersConfig {
        public final Map<String, VipTierDefinition> list = new LinkedHashMap<>();
        public final VipDefaultsConfig defaults = new VipDefaultsConfig();
    }

    public static class VipDefaultsConfig {
        public String duration = "30d";
        public boolean stacking = true;
        public String activationMode = "extend";
        public final VipMessagesConfig messages = new VipMessagesConfig();
        public final VipCommandsConfig commands = new VipCommandsConfig();
    }

    public static class VipMessagesConfig {
        public String activated = "";
        public String expired = "";
        public String rareItemBroadcast = "";
    }

    public static class VipCommandsConfig {
        public List<String> activate = new ArrayList<>();
        public List<String> expire = new ArrayList<>();
    }

    public static class VipActivationItemDefinition {
        public String itemId;
        public int amount = 1;
        public final Map<String, Integer> enchants = new LinkedHashMap<>();
        public String stackSnbt;
        public double chance = 100.0;
    }

    public static class VipTierDefinition {
        public String id;
        public String displayName;
        public String description;
        public int priority;
        public String defaultDuration = "30d";
        public boolean allowStacking = true;
        public String activationMode = "extend";
        public long maxStackDurationSeconds = 0;
        public String color = "white";
        public final VipMessagesConfig messages = new VipMessagesConfig();
        public final VipCommandsConfig commands = new VipCommandsConfig();
        public final List<VipActivationItemDefinition> activationItems = new ArrayList<>();
        public List<Map<String, Object>> actionsOnActivate = new ArrayList<>();
        public List<Map<String, Object>> actionsOnExpire = new ArrayList<>();
        public List<Map<String, Object>> actionsOnRemove = new ArrayList<>();
        public List<Map<String, Object>> actionsOnSetActive = new ArrayList<>();
        public List<Map<String, Object>> actionsOnUnsetActive = new ArrayList<>();
    }

    private static void loadTiers() throws IllegalArgumentException, IOException {
        String language = normalizeLanguage(common.language);
        Path file = configDir.resolve("tiers.toml");
        boolean created = !Files.exists(file);
        if (created) {
            TomlWriter.writeFile(file, buildTiersToml(language));
        }

        resetTierDefaults(language);

        Map<String, Object> parsed = TomlParser.parseFile(file);
        tiers.list.clear();

        if (parsed.containsKey("vips")) {
            loadSimplifiedTiers(parsed);
        } else if (parsed.containsKey("tiers")) {
            loadLegacyTiers(parsed);
        } else {
            loadLegacyTiers(parsed);
        }

        if (created) {
            writeDefaultActivationItemFiles();
        }
        loadActivationItemFiles();
    }

    private static void resetTierDefaults(String language) {
        tiers.defaults.duration = "30d";
        tiers.defaults.stacking = true;
        tiers.defaults.activationMode = common.defaultActivationMode != null ? common.defaultActivationMode : "extend";
        tiers.defaults.messages.activated = localized(language,
                "&a%player% activated VIP %vip_name% for %duration%.",
                "&a%player% ativou o VIP %vip_name% por %duration%.");
        tiers.defaults.messages.expired = localized(language,
                "&cYour VIP %vip_name% expired.",
                "&cSeu VIP %vip_name% expirou.");
        tiers.defaults.messages.rareItemBroadcast = localized(language,
                "&6%player% won a legendary item while activating VIP %vip_name%!",
                "&6%player% ganhou um item lendário ao ativar o VIP %vip_name%!");
        tiers.defaults.commands.activate = new ArrayList<>();
        tiers.defaults.commands.expire = new ArrayList<>();
    }

    private static void loadSimplifiedTiers(Map<String, Object> parsed) {
        Map<String, Object> defaultsData = asMap(parsed.get("defaults"));
        if (defaultsData != null) {
            tiers.defaults.duration = getString(defaultsData, "duration", tiers.defaults.duration);
            tiers.defaults.stacking = getBoolean(defaultsData, "stacking", tiers.defaults.stacking);
            tiers.defaults.activationMode = getString(defaultsData, "activation_mode", tiers.defaults.activationMode);

            Map<String, Object> messagesData = asMap(defaultsData.get("messages"));
            if (messagesData != null) {
                tiers.defaults.messages.activated = getString(messagesData, "activated", tiers.defaults.messages.activated);
                tiers.defaults.messages.expired = getString(messagesData, "expired", tiers.defaults.messages.expired);
                tiers.defaults.messages.rareItemBroadcast = getString(messagesData, "rare_item_broadcast", tiers.defaults.messages.rareItemBroadcast);
            }

            Map<String, Object> commandsData = asMap(defaultsData.get("commands"));
            if (commandsData != null) {
                tiers.defaults.commands.activate = getStringList(commandsData, "activate", tiers.defaults.commands.activate);
                tiers.defaults.commands.expire = getStringList(commandsData, "expire", tiers.defaults.commands.expire);
            }
        }

        Map<String, Object> vipsData = asMap(parsed.get("vips"));
        if (vipsData == null) {
            return;
        }

        int index = 0;
        for (Map.Entry<String, Object> entry : vipsData.entrySet()) {
            Map<String, Object> vipData = asMap(entry.getValue());
            if (vipData == null) {
                continue;
            }

            VipTierDefinition def = new VipTierDefinition();
            def.id = entry.getKey();
            applyVipDefaults(def, index++);

            def.displayName = getString(vipData, "display_name", def.id);
            def.description = getString(vipData, "description", def.description);
            def.priority = getInt(vipData, "priority", def.priority);
            def.defaultDuration = getString(vipData, "default_duration", def.defaultDuration);
            def.allowStacking = getBoolean(vipData, "allow_stacking", def.allowStacking);
            def.activationMode = getString(vipData, "activation_mode", def.activationMode);
            def.maxStackDurationSeconds = getLong(vipData, "max_stack_duration_seconds", def.maxStackDurationSeconds);
            def.color = getString(vipData, "color", def.color);

            Map<String, Object> messagesData = asMap(vipData.get("messages"));
            if (messagesData != null) {
                def.messages.activated = getString(messagesData, "activated", def.messages.activated);
                def.messages.expired = getString(messagesData, "expired", def.messages.expired);
                def.messages.rareItemBroadcast = getString(messagesData, "rare_item_broadcast", def.messages.rareItemBroadcast);
            }

            Map<String, Object> commandsData = asMap(vipData.get("commands"));
            if (commandsData != null) {
                def.commands.activate = getStringList(commandsData, "activate", def.commands.activate);
                def.commands.expire = getStringList(commandsData, "expire", def.commands.expire);
            }

            def.activationItems.addAll(parseActivationItems(vipData.get("activation_items")));

            def.actionsOnActivate = getActionList(vipData, "actions_on_activate");
            def.actionsOnExpire = getActionList(vipData, "actions_on_expire");
            def.actionsOnRemove = getActionList(vipData, "actions_on_remove");
            def.actionsOnSetActive = getActionList(vipData, "actions_on_set_active");
            def.actionsOnUnsetActive = getActionList(vipData, "actions_on_unset_active");

            tiers.list.put(def.id, def);
        }
    }

    private static void loadLegacyTiers(Map<String, Object> parsed) {
        Map<String, Object> tiersObj = asMap(parsed.get("tiers"));
        if (tiersObj == null) {
            return;
        }

        int index = 0;
        for (Map.Entry<String, Object> entry : tiersObj.entrySet()) {
            Map<String, Object> tierData = asMap(entry.getValue());
            if (tierData == null) {
                continue;
            }

            VipTierDefinition def = new VipTierDefinition();
            def.id = entry.getKey();
            def.displayName = getString(tierData, "display_name", def.id);
            def.description = getString(tierData, "description", "");
            def.priority = getInt(tierData, "priority", (index + 1) * 10);
            def.defaultDuration = getString(tierData, "default_duration", tiers.defaults.duration);
            def.allowStacking = getBoolean(tierData, "allow_stacking", tiers.defaults.stacking);
            def.activationMode = getString(tierData, "activation_mode", tiers.defaults.activationMode);
            def.maxStackDurationSeconds = getLong(tierData, "max_stack_duration_seconds", 0);
            def.color = getString(tierData, "color", "white");
            def.actionsOnActivate = getActionList(tierData, "actions_on_activate");
            def.actionsOnExpire = getActionList(tierData, "actions_on_expire");
            def.actionsOnRemove = getActionList(tierData, "actions_on_remove");
            def.actionsOnSetActive = getActionList(tierData, "actions_on_set_active");
            def.actionsOnUnsetActive = getActionList(tierData, "actions_on_unset_active");
            tiers.list.put(def.id, def);
            index++;
        }
    }

    private static void applyVipDefaults(VipTierDefinition def, int index) {
        def.priority = (index + 1) * 10;
        def.defaultDuration = tiers.defaults.duration;
        def.allowStacking = tiers.defaults.stacking;
        def.activationMode = tiers.defaults.activationMode;
        def.messages.activated = tiers.defaults.messages.activated;
        def.messages.expired = tiers.defaults.messages.expired;
        def.messages.rareItemBroadcast = tiers.defaults.messages.rareItemBroadcast;
        def.commands.activate = new ArrayList<>(tiers.defaults.commands.activate);
        def.commands.expire = new ArrayList<>(tiers.defaults.commands.expire);
    }

    public static synchronized void saveTiers() throws IOException {
        Files.createDirectories(configDir);
        TomlWriter.writeFile(configDir.resolve("tiers.toml"), buildTiersToml(common.language));
    }

    public static synchronized void saveActivationItems(String tierId) throws IOException {
        if (tierId == null || tierId.trim().isEmpty()) {
            return;
        }
        VipTierDefinition tier = tiers.list.get(tierId);
        if (tier == null) {
            return;
        }
        Files.createDirectories(activationItemsDir());
        TomlWriter.writeFile(activationItemsFile(tierId), buildActivationItemsToml(tier.activationItems));
    }

    private static void loadActivationItemFiles() throws IOException {
        Path dir = activationItemsDir();
        if (!Files.exists(dir)) {
            return;
        }

        for (VipTierDefinition tier : tiers.list.values()) {
            Path file = activationItemsFile(tier.id);
            if (!Files.exists(file)) {
                if (tier.activationItems != null && !tier.activationItems.isEmpty()) {
                    TomlWriter.writeFile(file, buildActivationItemsToml(tier.activationItems));
                }
                continue;
            }

            Map<String, Object> parsed = TomlParser.parseFile(file);
            List<VipActivationItemDefinition> items = parseActivationItems(parsed.containsKey("items") ? parsed.get("items") : parsed.get("activation_items"));
            if (!items.isEmpty()) {
                tier.activationItems.clear();
                tier.activationItems.addAll(items);
            } else if (parsed.containsKey("items") || parsed.containsKey("activation_items")) {
                tier.activationItems.clear();
            }
        }
    }

    private static void writeDefaultActivationItemFiles() throws IOException {
        Files.createDirectories(activationItemsDir());
        for (VipTierDefinition sample : buildDefaultVipSamples().values()) {
            Path file = activationItemsFile(sample.id);
            if (!Files.exists(file)) {
                TomlWriter.writeFile(file, buildActivationItemsToml(sample.activationItems));
            }
        }
    }

    private static Map<String, VipTierDefinition> buildDefaultVipSamples() {
        Map<String, VipTierDefinition> samples = new LinkedHashMap<>();
        samples.put("pokeball", sampleVip("Pokeball", "red", 10));
        samples.put("ultraball", sampleVip("Ultra Ball", "yellow", 20));
        samples.put("masterball", sampleVip("Master Ball", "light_purple", 30));
        return samples;
    }

    private static Map<String, Object> buildTiersToml(String language) {
        Map<String, Object> map = new LinkedHashMap<>();

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("duration", tiers.defaults.duration);
        defaults.put("stacking", tiers.defaults.stacking);
        defaults.put("activation_mode", tiers.defaults.activationMode);

        Map<String, Object> defaultMessages = new LinkedHashMap<>();
        defaultMessages.put("activated", tiers.defaults.messages.activated.isEmpty()
                ? localized(language, "&a%player% activated VIP %vip_name% for %duration%.", "&a%player% ativou o VIP %vip_name% por %duration%.")
                : tiers.defaults.messages.activated);
        defaultMessages.put("expired", tiers.defaults.messages.expired.isEmpty()
                ? localized(language, "&cYour VIP %vip_name% expired.", "&cSeu VIP %vip_name% expirou.")
                : tiers.defaults.messages.expired);
        defaultMessages.put("rare_item_broadcast", tiers.defaults.messages.rareItemBroadcast.isEmpty()
                ? localized(language, "&6%player% won a legendary item while activating VIP %vip_name%!", "&6%player% ganhou um item lendário ao ativar o VIP %vip_name%!")
                : tiers.defaults.messages.rareItemBroadcast);
        defaults.put("messages", defaultMessages);

        Map<String, Object> defaultCommands = new LinkedHashMap<>();
        defaultCommands.put("activate", new ArrayList<>(tiers.defaults.commands.activate));
        defaultCommands.put("expire", new ArrayList<>(tiers.defaults.commands.expire));
        defaults.put("commands", defaultCommands);

        map.put("defaults", defaults);

        Map<String, Object> vipsMap = new LinkedHashMap<>();
        map.put("vips", vipsMap);

        if (tiers.list.isEmpty()) {
            for (VipTierDefinition sample : buildDefaultVipSamples().values()) {
                vipsMap.put(sample.id, buildVipToml(sample));
            }
            return map;
        }

        for (VipTierDefinition def : tiers.list.values()) {
            vipsMap.put(def.id, buildVipToml(def));
        }

        return map;
    }

    private static VipTierDefinition sampleVip(String displayName, String color, int priority) {
        VipTierDefinition def = new VipTierDefinition();
        def.id = displayName.toLowerCase(Locale.ROOT).replace(" ", "");
        def.displayName = displayName;
        def.color = color;
        def.priority = priority;
        def.defaultDuration = tiers.defaults.duration;
        def.allowStacking = tiers.defaults.stacking;
        def.activationMode = tiers.defaults.activationMode;
        def.messages.activated = tiers.defaults.messages.activated;
        def.messages.expired = tiers.defaults.messages.expired;
        def.messages.rareItemBroadcast = tiers.defaults.messages.rareItemBroadcast;
        if ("pokeball".equals(def.id)) {
            VipActivationItemDefinition diamonds = new VipActivationItemDefinition();
            diamonds.itemId = "minecraft:diamond";
            diamonds.amount = 16;
            def.activationItems.add(diamonds);

            VipActivationItemDefinition emeralds = new VipActivationItemDefinition();
            emeralds.itemId = "minecraft:emerald";
            emeralds.amount = 16;
            def.activationItems.add(emeralds);

            VipActivationItemDefinition bottles = new VipActivationItemDefinition();
            bottles.itemId = "minecraft:experience_bottle";
            bottles.amount = 64;
            def.activationItems.add(bottles);
        } else if ("ultraball".equals(def.id)) {
            VipActivationItemDefinition diamonds = new VipActivationItemDefinition();
            diamonds.itemId = "minecraft:diamond";
            diamonds.amount = 32;
            def.activationItems.add(diamonds);

            VipActivationItemDefinition gold = new VipActivationItemDefinition();
            gold.itemId = "minecraft:gold_ingot";
            gold.amount = 32;
            def.activationItems.add(gold);

            VipActivationItemDefinition pearls = new VipActivationItemDefinition();
            pearls.itemId = "minecraft:ender_pearl";
            pearls.amount = 16;
            def.activationItems.add(pearls);
        } else if ("masterball".equals(def.id)) {
            VipActivationItemDefinition diamonds = new VipActivationItemDefinition();
            diamonds.itemId = "minecraft:diamond";
            diamonds.amount = 16;
            def.activationItems.add(diamonds);

            VipActivationItemDefinition star = new VipActivationItemDefinition();
            star.itemId = "minecraft:nether_star";
            star.amount = 1;
            def.activationItems.add(star);

            VipActivationItemDefinition pickaxe = new VipActivationItemDefinition();
            pickaxe.itemId = "minecraft:diamond_pickaxe";
            pickaxe.amount = 1;
            pickaxe.enchants.put("efficiency", 10);
            pickaxe.enchants.put("fortune", 5);
            pickaxe.enchants.put("unbreaking", 10);
            def.activationItems.add(pickaxe);
        }
        return def;
    }

    private static Map<String, Object> buildVipToml(VipTierDefinition def) {
        Map<String, Object> tier = new LinkedHashMap<>();
        if (def == null) {
            return tier;
        }

        tier.put("display_name", def.displayName != null ? def.displayName : def.id);
        tier.put("priority", def.priority);
        if (def.description != null && !def.description.isEmpty()) {
            tier.put("description", def.description);
        }
        if (def.defaultDuration != null && !def.defaultDuration.isEmpty() && !def.defaultDuration.equals(tiers.defaults.duration)) {
            tier.put("default_duration", def.defaultDuration);
        }
        if (def.allowStacking != tiers.defaults.stacking) {
            tier.put("allow_stacking", def.allowStacking);
        }
        if (def.activationMode != null && !def.activationMode.isEmpty() && !def.activationMode.equals(tiers.defaults.activationMode)) {
            tier.put("activation_mode", def.activationMode);
        }
        if (def.maxStackDurationSeconds > 0) {
            tier.put("max_stack_duration_seconds", def.maxStackDurationSeconds);
        }
        if (def.color != null && !def.color.isEmpty()) {
            tier.put("color", def.color);
        }

        Map<String, Object> messagesMap = new LinkedHashMap<>();
        if (def.messages != null) {
            if (def.messages.activated != null && !def.messages.activated.isEmpty() && !def.messages.activated.equals(tiers.defaults.messages.activated)) {
                messagesMap.put("activated", def.messages.activated);
            }
            if (def.messages.expired != null && !def.messages.expired.isEmpty() && !def.messages.expired.equals(tiers.defaults.messages.expired)) {
                messagesMap.put("expired", def.messages.expired);
            }
            if (def.messages.rareItemBroadcast != null && !def.messages.rareItemBroadcast.isEmpty() && !def.messages.rareItemBroadcast.equals(tiers.defaults.messages.rareItemBroadcast)) {
                messagesMap.put("rare_item_broadcast", def.messages.rareItemBroadcast);
            }
        }
        if (!messagesMap.isEmpty()) {
            tier.put("messages", messagesMap);
        }

        Map<String, Object> commandsMap = new LinkedHashMap<>();
        if (def.commands != null) {
            if (def.commands.activate != null && !def.commands.activate.isEmpty() && !def.commands.activate.equals(tiers.defaults.commands.activate)) {
                commandsMap.put("activate", new ArrayList<>(def.commands.activate));
            }
            if (def.commands.expire != null && !def.commands.expire.isEmpty() && !def.commands.expire.equals(tiers.defaults.commands.expire)) {
                commandsMap.put("expire", new ArrayList<>(def.commands.expire));
            }
        }
        if (!commandsMap.isEmpty()) {
            tier.put("commands", commandsMap);
        }

        if (def.actionsOnActivate != null && !def.actionsOnActivate.isEmpty()) {
            tier.put("actions_on_activate", new ArrayList<>(def.actionsOnActivate));
        }
        if (def.actionsOnExpire != null && !def.actionsOnExpire.isEmpty()) {
            tier.put("actions_on_expire", new ArrayList<>(def.actionsOnExpire));
        }
        if (def.actionsOnRemove != null && !def.actionsOnRemove.isEmpty()) {
            tier.put("actions_on_remove", new ArrayList<>(def.actionsOnRemove));
        }
        if (def.actionsOnSetActive != null && !def.actionsOnSetActive.isEmpty()) {
            tier.put("actions_on_set_active", new ArrayList<>(def.actionsOnSetActive));
        }
        if (def.actionsOnUnsetActive != null && !def.actionsOnUnsetActive.isEmpty()) {
            tier.put("actions_on_unset_active", new ArrayList<>(def.actionsOnUnsetActive));
        }

        return tier;
    }

    private static Map<String, Object> buildActivationItemsToml(List<VipActivationItemDefinition> activationItems) {
        Map<String, Object> map = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();

        if (activationItems != null) {
            for (VipActivationItemDefinition item : activationItems) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> itemMap = new LinkedHashMap<>();
                if (item.stackSnbt != null && !item.stackSnbt.isEmpty()) {
                    itemMap.put("stack_snbt", item.stackSnbt);
                } else {
                    if (item.itemId != null && !item.itemId.isEmpty()) {
                        itemMap.put("item", item.itemId);
                    }
                    if (item.amount > 1) {
                        itemMap.put("amount", item.amount);
                    }
                    if (item.enchants != null && !item.enchants.isEmpty()) {
                        itemMap.put("enchants", new LinkedHashMap<>(item.enchants));
                    }
                }
                if (item.chance < 100.0d) {
                    itemMap.put("chance", item.chance);
                }
                if (itemMap.size() > 1 || itemMap.containsKey("stack_snbt")) {
                    items.add(itemMap);
                }
            }
        }

        map.put("items", items);
        return map;
    }

    // ─── Packages Config ────────────────────────────────────
    public static class PackagesConfig {
        public final Map<String, PackageDefinition> list = new LinkedHashMap<>();
    }

    public static class PackageDefinition {
        public String id;
        public String displayName;
        public String description;
        public List<Map<String, Object>> actions = new ArrayList<>();
        public Map<String, List<Map<String, Object>>> variants = new LinkedHashMap<>();
        public boolean repeatable = true;
        public long cooldownSeconds = 0;
    }

    private static void loadPackages() throws IllegalArgumentException, IOException {
        String language = normalizeLanguage(common.language);
        Path file = configDir.resolve("packages.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            Map<String, Object> packagesMap = new LinkedHashMap<>();
            map.put("packages", packagesMap);

            // Default Package kit_inicial
            Map<String, Object> kit = new LinkedHashMap<>();
            kit.put("display_name", localized(language, "Starter Kit", "Kit Inicial"));
            kit.put("description", localized(language,
                    "Choose a variant to receive your items.",
                    "Escolha uma variante para receber seus itens."));
            kit.put("repeatable", true);
            kit.put("cooldown_seconds", 3600);

            List<Map<String, Object>> baseActions = new ArrayList<>();
            Map<String, Object> baseMsg = new LinkedHashMap<>();
            baseMsg.put("type", "send_message");
            baseMsg.put("message", localized(language,
                    "&aYou redeemed the Starter Kit!",
                    "&aVocê resgatou o Kit Inicial!"));
            baseActions.add(baseMsg);
            kit.put("actions", baseActions);

            Map<String, Object> variantsMap = new LinkedHashMap<>();
            
            List<Map<String, Object>> warActions = new ArrayList<>();
            Map<String, Object> warItem = new LinkedHashMap<>();
            warItem.put("type", "give_item");
            warItem.put("item", "minecraft:iron_sword");
            warItem.put("amount", 1);
            warActions.add(warItem);
            variantsMap.put(localized(language, "warrior", "guerreiro"), warActions);

            List<Map<String, Object>> arcActions = new ArrayList<>();
            Map<String, Object> arcItem = new LinkedHashMap<>();
            arcItem.put("type", "give_item");
            arcItem.put("item", "minecraft:bow");
            arcItem.put("amount", 1);
            arcActions.add(arcItem);
            variantsMap.put(localized(language, "archer", "arqueiro"), arcActions);

            kit.put("variants", variantsMap);
            packagesMap.put("kit_inicial", kit);

            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> parsed = TomlParser.parseFile(file);
        packages.list.clear();
        Object packagesObj = parsed.get("packages");
        if (packagesObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> packagesMap = (Map<String, Object>) packagesObj;
            for (Map.Entry<String, Object> entry : packagesMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pkgData = (Map<String, Object>) entry.getValue();
                    PackageDefinition def = new PackageDefinition();
                    def.id = entry.getKey();
                    def.displayName = getString(pkgData, "display_name", def.id);
                    def.description = getString(pkgData, "description", "");
                    def.repeatable = getBoolean(pkgData, "repeatable", true);
                    def.cooldownSeconds = getLong(pkgData, "cooldown_seconds", 0);
                    def.actions = getActionList(pkgData, "actions");

                    Object variantsObj = pkgData.get("variants");
                    if (variantsObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> varMap = (Map<String, Object>) variantsObj;
                        for (Map.Entry<String, Object> vEntry : varMap.entrySet()) {
                            if (vEntry.getValue() instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> actList = (List<Map<String, Object>>) vEntry.getValue();
                                def.variants.put(vEntry.getKey(), actList);
                            }
                        }
                    }
                    packages.list.put(def.id, def);
                }
            }
        }
    }

    // ─── Reward Keys Config ─────────────────────────────────
    public static class RewardKeysConfig {
        public final Map<String, RewardKeyDefinition> list = new LinkedHashMap<>();
    }

    public static class RewardKeyDefinition {
        public String id;
        public String displayName;
        public List<Map<String, Object>> actions = new ArrayList<>();
        public boolean consumeOnUse = true;
        public long cooldownSeconds = 0;
        public List<String> allowedDimensions = new ArrayList<>();
    }

    private static void loadRewardKeys() throws IllegalArgumentException, IOException {
        String language = normalizeLanguage(common.language);
        Path file = configDir.resolve("reward_keys.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            Map<String, Object> keysMap = new LinkedHashMap<>();
            map.put("reward_keys", keysMap);

            Map<String, Object> coins = new LinkedHashMap<>();
            coins.put("display_name", localized(language, "Coins Key", "Chave de Moedas"));
            coins.put("consume_on_use", true);
            coins.put("cooldown_seconds", 0);

            List<Map<String, Object>> actionsList = new ArrayList<>();
            Map<String, Object> runCmd = new LinkedHashMap<>();
            runCmd.put("type", "run_server_command");
            runCmd.put("command", "give {player} minecraft:gold_nugget 5");
            actionsList.add(runCmd);
            coins.put("actions", actionsList);

            keysMap.put("coins_5", coins);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> parsed = TomlParser.parseFile(file);
        rewardKeys.list.clear();
        Object rkObj = parsed.get("reward_keys");
        if (rkObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> keysMap = (Map<String, Object>) rkObj;
            for (Map.Entry<String, Object> entry : keysMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rkData = (Map<String, Object>) entry.getValue();
                    RewardKeyDefinition def = new RewardKeyDefinition();
                    def.id = entry.getKey();
                    def.displayName = getString(rkData, "display_name", def.id);
                    def.consumeOnUse = getBoolean(rkData, "consume_on_use", true);
                    def.cooldownSeconds = getLong(rkData, "cooldown_seconds", 0);
                    def.allowedDimensions = getStringList(rkData, "allowed_dimensions", new ArrayList<>());
                    def.actions = getActionList(rkData, "actions");

                    rewardKeys.list.put(def.id, def);
                }
            }
        }
    }

    // ─── Integrations Config ────────────────────────────────
    public static class IntegrationsConfig {
        public boolean ftbRanksEnabled = true;
        public boolean luckpermsEnabled = true;
        public String primaryPermissionBridge = "ftbranks"; // ftbranks, luckperms, vanilla
        public String ftbRanksAddCommand = "ftbranks add {player} {rank}";
        public String ftbRanksRemoveCommand = "ftbranks remove {player} {rank}";
        public String ftbRanksSetCommand = "ftbranks set {player} {rank}";
        public boolean sqlEnabled = false;
        public String sqlUrl = "jdbc:mysql://localhost:3306/easyvip";
        public String sqlUsername = "";
        public String sqlPassword = "";
        public int sqlPoolSize = 10;
        public int sqlMinimumIdle = 2;
        public int sqlConnectionTimeoutSeconds = 10;
        public int sqlIdleTimeoutSeconds = 600;
        public int sqlMaxLifetimeMinutes = 30;
        public int sqlLeakDetectionThresholdSeconds = 0;
    }

    private static void loadIntegrations() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("integrations.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ftb_ranks_enabled", integrations.ftbRanksEnabled);
            map.put("luckperms_enabled", integrations.luckpermsEnabled);
            map.put("primary_permission_bridge", integrations.primaryPermissionBridge);
            map.put("ftb_ranks_add_command", integrations.ftbRanksAddCommand);
            map.put("ftb_ranks_remove_command", integrations.ftbRanksRemoveCommand);
            map.put("ftb_ranks_set_command", integrations.ftbRanksSetCommand);
            map.put("sql_enabled", integrations.sqlEnabled);
            map.put("sql_url", integrations.sqlUrl);
            map.put("sql_username", integrations.sqlUsername);
            map.put("sql_password", integrations.sqlPassword);
            map.put("sql_pool_size", integrations.sqlPoolSize);
            map.put("sql_minimum_idle", integrations.sqlMinimumIdle);
            map.put("sql_connection_timeout_seconds", integrations.sqlConnectionTimeoutSeconds);
            map.put("sql_idle_timeout_seconds", integrations.sqlIdleTimeoutSeconds);
            map.put("sql_max_lifetime_minutes", integrations.sqlMaxLifetimeMinutes);
            map.put("sql_leak_detection_threshold_seconds", integrations.sqlLeakDetectionThresholdSeconds);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> data = TomlParser.parseFile(file);
        integrations.ftbRanksEnabled = getBoolean(data, "ftb_ranks_enabled", true);
        integrations.luckpermsEnabled = getBoolean(data, "luckperms_enabled", true);
        integrations.primaryPermissionBridge = getString(data, "primary_permission_bridge", "ftbranks");
        integrations.ftbRanksAddCommand = getString(data, "ftb_ranks_add_command", integrations.ftbRanksAddCommand);
        integrations.ftbRanksRemoveCommand = getString(data, "ftb_ranks_remove_command", integrations.ftbRanksRemoveCommand);
        integrations.ftbRanksSetCommand = getString(data, "ftb_ranks_set_command", integrations.ftbRanksSetCommand);
        integrations.sqlEnabled = getBoolean(data, "sql_enabled", false);
        integrations.sqlUrl = getString(data, "sql_url", integrations.sqlUrl);
        integrations.sqlUsername = getString(data, "sql_username", "");
        integrations.sqlPassword = getString(data, "sql_password", "");
        integrations.sqlPoolSize = getInt(data, "sql_pool_size", 10);
        integrations.sqlMinimumIdle = getInt(data, "sql_minimum_idle", 2);
        integrations.sqlConnectionTimeoutSeconds = getInt(data, "sql_connection_timeout_seconds", 10);
        integrations.sqlIdleTimeoutSeconds = getInt(data, "sql_idle_timeout_seconds", 600);
        integrations.sqlMaxLifetimeMinutes = getInt(data, "sql_max_lifetime_minutes", 30);
        integrations.sqlLeakDetectionThresholdSeconds = getInt(data, "sql_leak_detection_threshold_seconds", 0);
    }

    private static void loadWebStore() throws IllegalArgumentException, IOException {
        Path file = configDir.resolve("webstore.toml");
        if (!Files.exists(file)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enabled", webstore.enabled);
            map.put("api_url", webstore.apiUrl);
            map.put("api_token", webstore.apiToken);
            map.put("server_id", webstore.serverId);
            map.put("sync_on_register", webstore.syncOnRegister);
            map.put("sync_on_login", webstore.syncOnLogin);
            map.put("sync_on_join", webstore.syncOnJoin);
            map.put("sync_on_nick_change", webstore.syncOnNickChange);
            map.put("retry_max_attempts", webstore.retryMaxAttempts);
            map.put("retry_delay_seconds", webstore.retryDelaySeconds);
            addFulfillmentDefaults(map);
            TomlWriter.writeFile(file, map);
        }

        Map<String, Object> data = TomlParser.parseFile(file);
        webstore.enabled = getBoolean(data, "enabled", false);
        webstore.apiUrl = getString(data, "api_url", "http://localhost:3000").replaceAll("/+$", "");
        webstore.apiToken = getString(data, "api_token", "");
        webstore.serverId = getString(data, "server_id", "");
        webstore.syncOnRegister = getBoolean(data, "sync_on_register", true);
        webstore.syncOnLogin = getBoolean(data, "sync_on_login", true);
        webstore.syncOnJoin = getBoolean(data, "sync_on_join", true);
        webstore.syncOnNickChange = getBoolean(data, "sync_on_nick_change", true);
        webstore.retryMaxAttempts = getInt(data, "retry_max_attempts", 3);
        webstore.retryDelaySeconds = getInt(data, "retry_delay_seconds", 5);
        loadFulfillmentConfig(data);
    }

    @SuppressWarnings("unchecked")
    private static void addFulfillmentDefaults(Map<String, Object> map) {
        Map<String, Object> fulfillmentMap = new LinkedHashMap<>();
        fulfillmentMap.put("enabled", false);
        fulfillmentMap.put("server_id", "");
        fulfillmentMap.put("poll_interval_seconds", 15);
        fulfillmentMap.put("claim_limit", 20);
        fulfillmentMap.put("request_timeout_seconds", 10);
        fulfillmentMap.put("timestamp_tolerance_seconds", 60);
        fulfillmentMap.put("key_id", "");
        fulfillmentMap.put("key_prefix", "EVIP-");
        fulfillmentMap.put("secret_env", "EASYVIP_FULFILLMENT_SECRET");
        fulfillmentMap.put("token_env", "EASYVIP_FULFILLMENT_TOKEN");
        map.put("fulfillment", fulfillmentMap);

        Map<String, Object> products = new LinkedHashMap<>();
        Map<String, Object> gems50 = new LinkedHashMap<>();
        gems50.put("type", "reward");
        gems50.put("reward_key_id", "gems_50");
        gems50.put("max_uses", 1);
        gems50.put("expires_after", "365d");
        gems50.put("bind_to_player", true);
        products.put("gems_50", gems50);

        Map<String, Object> vip30 = new LinkedHashMap<>();
        vip30.put("type", "vip");
        vip30.put("tier_id", "ultraball");
        vip30.put("duration", "30d");
        vip30.put("max_uses", 1);
        vip30.put("expires_after", "365d");
        vip30.put("bind_to_player", true);
        products.put("vip_ultraball_30d", vip30);
        map.put("products", products);
    }

    @SuppressWarnings("unchecked")
    private static void loadFulfillmentConfig(Map<String, Object> data) {
        fulfillment.products.clear();
        fulfillment.keys.keys.clear();

        Map<String, Object> fulfillmentData = asMap(data.get("fulfillment"));
        if (fulfillmentData != null) {
            fulfillment.enabled = getBoolean(fulfillmentData, "enabled", false);
            fulfillment.serverId = getString(fulfillmentData, "server_id", "");
            fulfillment.pollIntervalSeconds = getInt(fulfillmentData, "poll_interval_seconds", 15);
            fulfillment.claimLimit = getInt(fulfillmentData, "claim_limit", 20);
            fulfillment.requestTimeoutSeconds = getInt(fulfillmentData, "request_timeout_seconds", 10);
            fulfillment.timestampToleranceSeconds = getInt(fulfillmentData, "timestamp_tolerance_seconds", 60);
            fulfillment.keyId = getString(fulfillmentData, "key_id", "");
            fulfillment.keyPrefix = getString(fulfillmentData, "key_prefix", "EVIP-");
            fulfillment.secretEnv = getString(fulfillmentData, "secret_env", "EASYVIP_FULFILLMENT_SECRET");
            fulfillment.tokenEnv = getString(fulfillmentData, "token_env", "EASYVIP_FULFILLMENT_TOKEN");
            fulfillment.token = getString(fulfillmentData, "token", "");

            Map<String, Object> keysData = asMap(fulfillmentData.get("keys"));
            if (keysData != null) {
                for (Map.Entry<String, Object> entry : keysData.entrySet()) {
                    Map<String, Object> keyData = asMap(entry.getValue());
                    if (keyData == null) continue;
                    FulfillmentKeyConfig.KeyEntry ke = new FulfillmentKeyConfig.KeyEntry();
                    ke.secretEnv = getString(keyData, "secret_env", "");
                    ke.secret = getString(keyData, "secret", "");
                    if ("current".equals(entry.getKey())) {
                        fulfillment.keys.current = ke;
                    }
                    fulfillment.keys.keys.put(entry.getKey(), ke);
                }
            }
        }

        Map<String, Object> productsData = asMap(data.get("products"));
        if (productsData != null) {
            for (Map.Entry<String, Object> entry : productsData.entrySet()) {
                Map<String, Object> productData = asMap(entry.getValue());
                if (productData == null) continue;
                FulfillmentProductConfig pc = new FulfillmentProductConfig();
                pc.sku = entry.getKey();
                pc.type = getString(productData, "type", getString(productData, "kind", ""));
                pc.kind = getString(productData, "kind", "");
                pc.tierId = getString(productData, "tier_id", "");
                pc.duration = getString(productData, "duration", "");
                pc.rewardKeyId = getString(productData, "reward_key_id", "");
                pc.maxUses = getInt(productData, "max_uses", 1);
                pc.expiresAfter = getString(productData, "expires_after", "");
                pc.bindToPlayer = getBoolean(productData, "bind_to_player", true);
                fulfillment.products.put(pc.sku, pc);
            }
        }
    }

    public static List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (!normalizeLanguage(common.language).equals("en-us") && !normalizeLanguage(common.language).equals("pt-br")) {
            errors.add(localized(
                    "common.toml: language must be en-us or pt-br.",
                    "common.toml: language deve ser en-us ou pt-br."
            ));
        }
        if (common.keyLength < 4) {
            errors.add(localized(
                    "common.toml: key_length must be at least 4.",
                    "common.toml: key_length deve ser no mínimo 4."
            ));
        }
        if (common.keyCharset == null || common.keyCharset.length() < 2) {
            errors.add(localized(
                    "common.toml: key_charset must have at least 2 characters.",
                    "common.toml: key_charset deve ter pelo menos 2 caracteres."
            ));
        } else {
            long uniqueChars = common.keyCharset.chars().distinct().count();
            if (uniqueChars < 2) {
                errors.add(localized(
                        "common.toml: key_charset must contain at least 2 distinct characters.",
                        "common.toml: key_charset deve conter pelo menos 2 caracteres distintos."
                ));
            }
            double entropyBits = Math.log(Math.pow(uniqueChars, common.keyLength)) / Math.log(2);
            if (entropyBits < 32) {
                errors.add(localized(
                        "common.toml: key_charset + key_length combination provides less than 32 bits of entropy (" + String.format("%.1f", entropyBits) + " bits). Increase charset size or key_length.",
                        "common.toml: combinação key_charset + key_length fornece menos de 32 bits de entropia (" + String.format("%.1f", entropyBits) + " bits). Aumente o charset ou key_length."
                ));
            }
        }
        if (!common.defaultActivationMode.equals("extend") && !common.defaultActivationMode.equals("replace") &&
            !common.defaultActivationMode.equals("stack") && !common.defaultActivationMode.equals("deny")) {
            errors.add(localized(
                    "common.toml: invalid default_activation_mode: " + common.defaultActivationMode,
                    "common.toml: default_activation_mode inválido: " + common.defaultActivationMode
            ));
        }
        if (!tiers.defaults.activationMode.equals("extend") && !tiers.defaults.activationMode.equals("replace") &&
            !tiers.defaults.activationMode.equals("stack") && !tiers.defaults.activationMode.equals("deny")) {
            errors.add(localized(
                    "tiers.toml: invalid defaults.activation_mode: " + tiers.defaults.activationMode,
                    "tiers.toml: defaults.activation_mode inválido: " + tiers.defaults.activationMode
            ));
        }
        for (Map.Entry<String, RandomPoolDefinition> poolEntry : pools.list.entrySet()) {
            String poolId = poolEntry.getKey();
            RandomPoolDefinition pool = poolEntry.getValue();
            boolean hasValues = pool != null && pool.values != null && !pool.values.isEmpty();
            boolean hasWeighted = pool != null && pool.weighted != null && !pool.weighted.isEmpty();
            if (!hasValues && !hasWeighted) {
                errors.add(localized(
                        "pools.toml: pool " + poolId + " must define at least one value.",
                        "pools.toml: o pool " + poolId + " deve definir pelo menos um valor."
                ));
            }
            if (pool != null) {
                for (String value : pool.values) {
                    if (value == null || value.trim().isEmpty()) {
                        errors.add(localized(
                                "pools.toml: pool " + poolId + " has an empty value entry.",
                                "pools.toml: o pool " + poolId + " possui uma entrada de valor vazia."
                        ));
                    }
                }
                for (RandomPoolEntry item : pool.weighted) {
                    if (item == null || item.value == null || item.value.trim().isEmpty()) {
                        errors.add(localized(
                                "pools.toml: pool " + poolId + " has an invalid weighted entry.",
                                "pools.toml: o pool " + poolId + " possui uma entrada ponderada inválida."
                        ));
                        continue;
                    }
                    if (item.weight <= 0.0d) {
                        errors.add(localized(
                                "pools.toml: pool " + poolId + " has a non-positive weight for value " + item.value + ".",
                                "pools.toml: o pool " + poolId + " possui peso não positivo para o valor " + item.value + "."
                        ));
                    }
                }
            }
        }
        for (VipTierDefinition tier : tiers.list.values()) {
            if (tier.id == null || tier.id.trim().isEmpty()) {
                errors.add(localized(
                        "tiers.toml: tier ID cannot be empty.",
                        "tiers.toml: ID do tier não pode ser vazio."
                ));
            }
            if (tier.priority < 0) {
                errors.add(localized(
                        "tiers.toml: tier " + tier.id + " priority cannot be negative.",
                        "tiers.toml: priority do tier " + tier.id + " não pode ser negativa."
                ));
            }
            if (!tier.activationMode.equals("extend") && !tier.activationMode.equals("replace") &&
                !tier.activationMode.equals("stack") && !tier.activationMode.equals("deny")) {
                errors.add(localized(
                        "tiers.toml: invalid activation_mode for tier " + tier.id + ": " + tier.activationMode,
                        "tiers.toml: activation_mode inválido para o tier " + tier.id + ": " + tier.activationMode
                ));
            }
            for (VipActivationItemDefinition item : tier.activationItems) {
                if (item == null) {
                    errors.add(localized(
                            "tiers.toml: tier " + tier.id + " has an invalid activation item entry.",
                            "tiers.toml: o tier " + tier.id + " possui uma entrada de item de ativação inválida."
                    ));
                    continue;
                }
                if ((item.itemId == null || item.itemId.trim().isEmpty()) && (item.stackSnbt == null || item.stackSnbt.trim().isEmpty())) {
                    errors.add(localized(
                            "tiers.toml: tier " + tier.id + " has an activation item with no item or stack_snbt.",
                            "tiers.toml: o tier " + tier.id + " possui um item de ativação sem item ou stack_snbt."
                    ));
                }
                if (item.itemId != null && item.itemId.trim().isEmpty()) {
                    errors.add(localized(
                            "tiers.toml: tier " + tier.id + " has an empty activation item id.",
                            "tiers.toml: o tier " + tier.id + " possui um id de item de ativação vazio."
                    ));
                }
                if (item.amount < 1) {
                    errors.add(localized(
                            "tiers.toml: tier " + tier.id + " has an activation item amount below 1.",
                            "tiers.toml: o tier " + tier.id + " possui quantidade de item de ativação abaixo de 1."
                    ));
                }
                for (Map.Entry<String, Integer> enchant : item.enchants.entrySet()) {
                    if (enchant.getKey() == null || enchant.getKey().trim().isEmpty() || enchant.getValue() == null || enchant.getValue() < 1) {
                        errors.add(localized(
                                "tiers.toml: tier " + tier.id + " has an invalid enchant entry.",
                                "tiers.toml: o tier " + tier.id + " possui uma entrada de encantamento inválida."
                        ));
                    }
                }
                if (item.chance < 0.0d || item.chance > 100.0d) {
                    errors.add(localized(
                            "tiers.toml: tier " + tier.id + " has an activation item chance outside 0..100: " + item.chance,
                            "tiers.toml: o tier " + tier.id + " possui chance de item de ativação fora de 0..100: " + item.chance
                    ));
                }
            }
        }
        for (PackageDefinition pkg : packages.list.values()) {
            if (pkg.id == null || pkg.id.trim().isEmpty()) {
                errors.add(localized(
                        "packages.toml: package ID cannot be empty.",
                        "packages.toml: ID do pacote não pode ser vazio."
                ));
            }
        }
        for (RewardKeyDefinition rk : rewardKeys.list.values()) {
            if (rk.id == null || rk.id.trim().isEmpty()) {
                errors.add(localized(
                        "reward_keys.toml: reward key ID cannot be empty.",
                        "reward_keys.toml: ID do reward key não pode ser vazio."
                ));
            }
        }
        if (fulfillment.enabled) {
            if (!integrations.sqlEnabled) {
                errors.add(localized(
                        "webstore.toml: fulfillment.enabled requires SQL mode.",
                        "webstore.toml: fulfillment.enabled requer modo SQL."
                ));
            }
            if (fulfillment.serverId == null || fulfillment.serverId.isBlank()) {
                errors.add(localized(
                        "webstore.toml: fulfillment.server_id cannot be empty.",
                        "webstore.toml: fulfillment.server_id não pode ser vazio."
                ));
            }
            if (fulfillment.keyId == null || fulfillment.keyId.isBlank()) {
                errors.add(localized(
                        "webstore.toml: fulfillment.key_id cannot be empty.",
                        "webstore.toml: fulfillment.key_id não pode ser vazio."
                ));
            }
            if (fulfillment.keyPrefix == null || fulfillment.keyPrefix.isBlank()) {
                errors.add(localized(
                        "webstore.toml: fulfillment.key_prefix cannot be empty.",
                        "webstore.toml: fulfillment.key_prefix não pode ser vazio."
                ));
            }
            if (fulfillment.pollIntervalSeconds < 5) {
                errors.add(localized(
                        "webstore.toml: fulfillment.poll_interval_seconds must be at least 5.",
                        "webstore.toml: fulfillment.poll_interval_seconds deve ser no mínimo 5."
                ));
            }
            if (fulfillment.claimLimit < 1 || fulfillment.claimLimit > 100) {
                errors.add(localized(
                        "webstore.toml: fulfillment.claim_limit must be between 1 and 100.",
                        "webstore.toml: fulfillment.claim_limit deve ficar entre 1 e 100."
                ));
            }
            for (FulfillmentProductConfig product : fulfillment.products.values()) {
                String type = product.normalizedType();
                if (!"vip".equals(type) && !"reward".equals(type)) {
                    errors.add(localized(
                            "webstore.toml: product " + product.sku + " must be type reward or vip.",
                            "webstore.toml: produto " + product.sku + " deve ser do tipo reward ou vip."
                    ));
                }
            }
        }
        return errors;
    }

    public static String localized(String enUs, String ptBr) {
        return localized(common.language, enUs, ptBr);
    }

    public static String localized(String language, String enUs, String ptBr) {
        return isPtBr(language) ? ptBr : enUs;
    }

    public static boolean isPtBr() {
        return isPtBr(common.language);
    }

    public static boolean isPtBr(String language) {
        return "pt-br".equals(normalizeLanguage(language));
    }

    public static void applyMessageDefaults(String language) {
        String normalized = normalizeLanguage(language);
        if (isPtBr(normalized)) {
            applyPortugueseMessageDefaults();
        } else {
            applyEnglishMessageDefaults();
        }
    }

    private static void applyEnglishMessageDefaults() {
        messages.prefix = "&7[&eEasyVip&7] ";
        messages.noPermission = "&cYou do not have permission to use this command.";
        messages.playerOnly = "&cThis command can only be used by players.";
        messages.adminOnly = "&cThis command can only be used by administrators.";
        messages.invalidPlayer = "&cPlayer not found.";
        messages.invalidTier = "&cInvalid VIP tier.";
        messages.invalidDuration = "&cInvalid duration.";
        messages.invalidKey = "&cThe entered key is invalid.";
        messages.keyExpired = "&cThis key has expired.";
        messages.keyNoUsesLeft = "&cThis key has no uses left.";
        messages.keyAlreadyUsed = "&cThis key has already been used.";
        messages.keyBoundToOtherPlayer = "&cThis key is bound to another player.";
        messages.keyConfirmRequired = "&eThis key will activate VIP {tier_display} for {duration}. Type /easyvip confirm to confirm.";
        messages.keyRewardConfirmRequired = "&eThis key will deliver the benefits package {reward_display}. Type /easyvip confirm to confirm.";
        messages.keyConfirmed = "&aKey confirmed successfully!";
        messages.rewardGiven = "&aBenefits package {reward_display} delivered successfully!";
        messages.vipActivated = "&aVIP {tier_display} activated for {duration}!";
        messages.vipExtended = "&aVIP {tier_display} extended by {duration}!";
        messages.vipSet = "&aVIP {tier_display} set for {player} for {duration}!";
        messages.vipRemoved = "&aVIP {tier_display} removed from {player}!";
        messages.vipExpired = "&cYour VIP {tier_display} expired.";
        messages.vipNotFound = "&cYou do not have this active VIP.";
        messages.vipTimeHeader = "&e--- Your VIPs ---";
        messages.vipTimeLine = "&7- {tier_display}: &f{duration_left} left";
        messages.activeVipChanged = "&aYour active VIP has been changed to {tier_display}.";
        messages.activeVipNotOwned = "&cYou do not own this VIP tier.";
        messages.packageGiven = "&aYou received package {package}!";
        messages.packageNotFound = "&cPackage not found.";
        messages.variantPending = "&eYou have a pending variant choice for package {package}. Use /easyvip variant choose {package} <variant> to pick one.";
        messages.variantSelected = "&aVariant {variant} selected successfully!";
        messages.variantInvalid = "&cInvalid variant. Allowed choices: {allowed_variants}";
        messages.reloadSuccess = "&aSettings reloaded successfully!";
        messages.reloadError = "&cError reloading settings: {error}";
        messages.configInvalid = "&cInvalid configuration found.";
        messages.vipActivatedBroadcast = "&6[&eEasyVip&6] &aPlayer &e{player} &aactivated VIP &b{tier_display}&a. Congratulations!";
        messages.vipLuckyItemBroadcast = "&6[&eEasyVip&6] &e%player% &6won a legendary item while activating VIP &b%tier_display%&6!";
        messages.durationPermanent = "permanent";
    }

    private static void applyPortugueseMessageDefaults() {
        messages.prefix = "&7[&eEasyVip&7] ";
        messages.noPermission = "&cVocê não tem permissão para usar este comando.";
        messages.playerOnly = "&cEste comando só pode ser usado por jogadores.";
        messages.adminOnly = "&cEste comando só pode ser usado por administradores.";
        messages.invalidPlayer = "&cJogador não encontrado.";
        messages.invalidTier = "&cTier VIP inválido.";
        messages.invalidDuration = "&cDuração inválida.";
        messages.invalidKey = "&cA chave inserida é inválida.";
        messages.keyExpired = "&cEsta chave expirou.";
        messages.keyNoUsesLeft = "&cEsta chave não possui mais usos.";
        messages.keyAlreadyUsed = "&cEsta chave já foi usada.";
        messages.keyBoundToOtherPlayer = "&cEsta chave está vinculada a outro jogador.";
        messages.keyConfirmRequired = "&eEsta chave ativará o VIP {tier_display} por {duration}. Digite /easyvip confirm para confirmar.";
        messages.keyRewardConfirmRequired = "&eEsta chave entregará o pacote de benefícios {reward_display}. Digite /easyvip confirm para confirmar.";
        messages.keyConfirmed = "&aChave confirmada com sucesso!";
        messages.rewardGiven = "&aPacote de benefícios {reward_display} entregue com sucesso!";
        messages.vipActivated = "&aVocê ativou o VIP {tier_display} por {duration}.";
        messages.vipExtended = "&aVIP {tier_display} estendido por mais {duration}!";
        messages.vipSet = "&aVIP {tier_display} definido para {player} por {duration}!";
        messages.vipRemoved = "&aVIP {tier_display} removido de {player}!";
        messages.vipExpired = "&cSeu VIP {tier_display} expirou.";
        messages.vipNotFound = "&cVocê não possui este VIP ativo.";
        messages.vipTimeHeader = "&e--- Seus VIPs ---";
        messages.vipTimeLine = "&7- {tier_display}: &f{duration_left} restante";
        messages.activeVipChanged = "&aSeu VIP ativo foi alterado para {tier_display}.";
        messages.activeVipNotOwned = "&cVocê não possui este tier VIP.";
        messages.packageGiven = "&aVocê recebeu o pacote {package}!";
        messages.packageNotFound = "&cPacote não encontrado.";
        messages.variantPending = "&eVocê possui uma escolha de variante pendente para o pacote {package}. Use /easyvip variant choose {package} <variante> para escolher.";
        messages.variantSelected = "&aVariante {variant} selecionada com sucesso!";
        messages.variantInvalid = "&cVariante inválida. Escolhas permitidas: {allowed_variants}";
        messages.reloadSuccess = "&aConfigurações recarregadas com sucesso!";
        messages.reloadError = "&cErro ao recarregar configurações: {error}";
        messages.configInvalid = "&cConfiguração inválida encontrada.";
        messages.vipActivatedBroadcast = "&6[&eEasyVip&6] &aO player &e{player} &aativou o VIP &b{tier_display}&a. Parabéns!";
        messages.vipLuckyItemBroadcast = "&6[&eEasyVip&6] &e%player% &6ganhou um item lendário ao ativar o VIP &b%tier_display%&6!";
        messages.durationPermanent = "permanente";
    }

    private static String normalizeLanguage(String language) {
        if (language == null) {
            return "en-us";
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.isEmpty() ? "en-us" : normalized;
    }

    // ─── Helper Methods ─────────────────────────────────────
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getActionList(Map<String, Object> parent, String key) {
        Object obj = parent.get(key);
        if (obj instanceof List) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                if (item instanceof Map) {
                    actions.add((Map<String, Object>) item);
                }
            }
            return actions;
        }
        return new ArrayList<>();
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val != null) {
            try {
                return Integer.parseInt(val.toString());
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return def;
    }

    private static long getLong(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        if (val != null) {
            try {
                return Long.parseLong(val.toString());
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return def;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean def) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val != null) return Boolean.parseBoolean(val.toString());
        return def;
    }

    private static List<String> getStringList(Map<String, Object> map, String key, List<String> def) {
        Object val = map.get(key);
        if (val instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object o : (List<?>) val) {
                if (o != null) list.add(o.toString());
            }
            return list;
        }
        return new ArrayList<>(def);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return null;
    }

    private static List<VipActivationItemDefinition> parseActivationItems(Object obj) {
        List<VipActivationItemDefinition> items = new ArrayList<>();
        if (!(obj instanceof List)) {
            return items;
        }

        for (Object entry : (List<?>) obj) {
            Map<String, Object> itemData = asMap(entry);
            if (itemData == null) {
                continue;
            }

            VipActivationItemDefinition item = new VipActivationItemDefinition();
            item.itemId = getString(itemData, "item", getString(itemData, "item_id", ""));
            item.amount = Math.max(1, getInt(itemData, "amount", 1));
            item.stackSnbt = getString(itemData, "stack_snbt", getString(itemData, "stack", ""));
            item.chance = getDouble(itemData, "chance", 100.0d);
            Map<String, Object> enchantsData = asMap(itemData.get("enchants"));
            if (enchantsData != null) {
                for (Map.Entry<String, Object> enchantEntry : enchantsData.entrySet()) {
                    int level = 0;
                    Object levelObj = enchantEntry.getValue();
                    if (levelObj instanceof Number) {
                        level = ((Number) levelObj).intValue();
                    } else if (levelObj != null) {
                        try {
                            level = Integer.parseInt(levelObj.toString());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (level > 0) {
                        item.enchants.put(enchantEntry.getKey(), level);
                    }
                }
            }
            if ((item.itemId != null && !item.itemId.isEmpty()) || (item.stackSnbt != null && !item.stackSnbt.isEmpty())) {
                items.add(item);
            }
        }

        return items;
    }

    private static double getDouble(Map<String, Object> map, String key, double def) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        if (val != null) {
            try {
                return Double.parseDouble(val.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}

package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebStoreSyncServiceTest {

    private String originalWebstoreServerId;
    private String originalFulfillmentServerId;
    private boolean originalWebstoreEnabled;
    private String originalApiToken;
    private String originalApiUrl;
    private Map<String, FulfillmentProductConfig> originalProducts;

    @BeforeEach
    void setUp() {
        originalWebstoreServerId = EasyVipConfig.webstore.serverId;
        originalFulfillmentServerId = EasyVipConfig.fulfillment.serverId;
        originalWebstoreEnabled = EasyVipConfig.webstore.enabled;
        originalApiToken = EasyVipConfig.webstore.apiToken;
        originalApiUrl = EasyVipConfig.webstore.apiUrl;
        originalProducts = new LinkedHashMap<>(EasyVipConfig.fulfillment.products);

        EasyVipConfig.webstore.enabled = true;
        EasyVipConfig.webstore.apiToken = "token";
        EasyVipConfig.webstore.apiUrl = "https://rails.example.invalid";
        EasyVipConfig.webstore.serverId = "allthemons";
        EasyVipConfig.fulfillment.serverId = "fallback-server";
    }

    @AfterEach
    void tearDown() {
        EasyVipConfig.webstore.serverId = originalWebstoreServerId;
        EasyVipConfig.fulfillment.serverId = originalFulfillmentServerId;
        EasyVipConfig.webstore.enabled = originalWebstoreEnabled;
        EasyVipConfig.webstore.apiToken = originalApiToken;
        EasyVipConfig.webstore.apiUrl = originalApiUrl;
        EasyVipConfig.fulfillment.products.clear();
        EasyVipConfig.fulfillment.products.putAll(originalProducts);
    }

    @Test
    void buildSyncPayloadIncludesMultiServerFields() throws Exception {
        String payload = invokeBuildSyncPayload(UUID.fromString("e309ad92-e421-420a-8bf3-3df86db3e660"), "PedropsRei", "203.0.113.10");
        assertTrue(payload.contains("\"minecraft_uuid\""));
        assertTrue(payload.contains("\"username\""));
        assertTrue(payload.contains("\"canonical_username\": \"pedropsrei\""));
        assertTrue(payload.contains("\"server_id\": \"allthemons\""));
        assertTrue(payload.contains("\"identity_status\": \"observed\""));
        assertTrue(payload.contains("\"ip_address\": \"203.0.113.10\""));
    }

    @Test
    void buildSyncPayloadFallsBackToFulfillmentServerId() throws Exception {
        EasyVipConfig.webstore.serverId = "";
        String payload = invokeBuildSyncPayload(UUID.fromString("e309ad92-e421-420a-8bf3-3df86db3e660"), "PedropsRei", null);
        assertTrue(payload.contains("\"server_id\": \"fallback-server\""));
        assertFalse(payload.contains("ip_address"));
    }

    private static String invokeBuildSyncPayload(UUID uuid, String username, String ipAddress) throws Exception {
        Method method = WebStoreSyncService.class.getDeclaredMethod("buildSyncPayload", UUID.class, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, uuid, username, ipAddress);
    }
}

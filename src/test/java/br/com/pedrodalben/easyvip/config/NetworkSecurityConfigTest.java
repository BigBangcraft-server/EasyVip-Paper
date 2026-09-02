package br.com.pedrodalben.easyvip.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSecurityConfigTest {
    private final boolean redisEnabled = EasyVipConfig.network.redisEnabled;
    private final String redisUri = EasyVipConfig.network.redisUri;
    private final String environment = EasyVipConfig.network.environment;
    private final boolean webstoreEnabled = EasyVipConfig.webstore.enabled;
    private final String webstoreUrl = EasyVipConfig.webstore.apiUrl;

    @AfterEach
    void restore() {
        EasyVipConfig.network.redisEnabled = redisEnabled;
        EasyVipConfig.network.redisUri = redisUri;
        EasyVipConfig.network.environment = environment;
        EasyVipConfig.webstore.enabled = webstoreEnabled;
        EasyVipConfig.webstore.apiUrl = webstoreUrl;
    }

    @Test
    void productionRemoteRedisRequiresTls() {
        EasyVipConfig.network.redisEnabled = true;
        EasyVipConfig.network.redisUri = "redis://redis.example:6379";
        EasyVipConfig.network.environment = "production";

        List<String> errors = EasyVipConfig.validate();

        assertTrue(errors.stream().anyMatch(error -> error.contains("production Redis must use rediss")));
    }

    @Test
    void remoteWebStoreRequiresHttps() {
        EasyVipConfig.webstore.enabled = true;
        EasyVipConfig.webstore.apiUrl = "http://store.example";

        List<String> errors = EasyVipConfig.validate();

        assertTrue(errors.stream().anyMatch(error -> error.contains("api_url must use https")));
    }

    @Test
    void webStoreUrlRequiresHostAndHttpScheme() {
        EasyVipConfig.webstore.enabled = true;
        EasyVipConfig.webstore.apiUrl = "https://";

        List<String> errors = EasyVipConfig.validate();

        assertTrue(errors.stream().anyMatch(error -> error.contains("api_url")));
    }
}

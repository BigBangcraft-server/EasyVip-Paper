package br.com.pedrodalben.easyvip.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSecurityConfigTest {
    private final boolean redisEnabled = EasyVipConfig.network.redisEnabled;
    private final String redisUri = EasyVipConfig.network.redisUri;
    private final String environment = EasyVipConfig.network.environment;
    private final boolean webstoreEnabled = EasyVipConfig.webstore.enabled;
    private final String webstoreUrl = EasyVipConfig.webstore.apiUrl;
    private final boolean sqlEnabled = EasyVipConfig.integrations.sqlEnabled;
    private final String sqlUrl = EasyVipConfig.integrations.sqlUrl;

    @AfterEach
    void restore() {
        EasyVipConfig.network.redisEnabled = redisEnabled;
        EasyVipConfig.network.redisUri = redisUri;
        EasyVipConfig.network.environment = environment;
        EasyVipConfig.webstore.enabled = webstoreEnabled;
        EasyVipConfig.webstore.apiUrl = webstoreUrl;
        EasyVipConfig.integrations.sqlEnabled = sqlEnabled;
        EasyVipConfig.integrations.sqlUrl = sqlUrl;
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

    @Test
    void productionRemoteSqlRequiresIdentityVerification() {
        EasyVipConfig.integrations.sqlEnabled = true;
        EasyVipConfig.integrations.sqlUrl = "jdbc:mysql://db.example:3306/easyvip";
        EasyVipConfig.network.environment = "production";

        List<String> errors = EasyVipConfig.validate();

        assertTrue(errors.stream().anyMatch(error -> error.contains("sslMode=VERIFY_IDENTITY")));
    }

    @Test
    void sqlTlsValidationCannotBeSatisfiedByAnotherParameterOrAnOverriddenValue() {
        EasyVipConfig.integrations.sqlEnabled = true;
        EasyVipConfig.integrations.sqlUrl =
                "jdbc:mysql://db.example:3306/easyvip?note=sslMode=VERIFY_IDENTITY&sslMode=DISABLED";
        EasyVipConfig.network.environment = "production";

        List<String> errors = EasyVipConfig.validate();

        assertTrue(errors.stream().anyMatch(error -> error.contains("sslMode=VERIFY_IDENTITY")));
    }

    @Test
    void sqlTlsValidationAcceptsTheEffectiveIdentityMode() {
        EasyVipConfig.integrations.sqlEnabled = true;
        EasyVipConfig.integrations.sqlUrl =
                "jdbc:mysql://db.example:3306/easyvip?sslMode=VERIFY_IDENTITY&connectTimeout=5";
        EasyVipConfig.network.environment = "production";

        List<String> errors = EasyVipConfig.validate();

        assertTrue(errors.stream().noneMatch(error -> error.contains("sslMode=VERIFY_IDENTITY")));
    }

    @Test
    void environmentCredentialsTakePrecedenceWithoutLoggingValues() {
        assertEquals("environment-secret", EasyVipConfig.resolveEnvironmentValue(
                "EASYVIP_SQL_PASSWORD", "inline-secret",
                Map.of("EASYVIP_SQL_PASSWORD", "environment-secret")));
        assertEquals("inline-secret", EasyVipConfig.resolveEnvironmentValue(
                "EASYVIP_SQL_PASSWORD", "inline-secret", Map.of()));
        assertEquals("", EasyVipConfig.resolveEnvironmentValue(
                "EASYVIP_SQL_PASSWORD", "inline-secret", Map.of("EASYVIP_SQL_PASSWORD", "")));
    }
}

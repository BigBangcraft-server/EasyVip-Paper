package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WebStoreFulfillmentStagingTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        WebStoreFulfillmentService.stop();
        PersistenceManager.shutdown();
    }

    @Test
    void stagingClaimCompleteAndRetryAreIdempotent() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("EASYVIP_WEBSTORE_STAGING_ENABLE")));

        String url = System.getenv("EASYVIP_WEBSTORE_STAGING_URL");
        String token = System.getenv("EASYVIP_WEBSTORE_STAGING_TOKEN");
        String secret = System.getenv("EASYVIP_WEBSTORE_STAGING_SECRET");
        String serverId = System.getenv("EASYVIP_WEBSTORE_STAGING_SERVER_ID");
        String keyId = System.getenv("EASYVIP_WEBSTORE_STAGING_KEY_ID");
        String fulfillmentId = System.getenv("EASYVIP_WEBSTORE_STAGING_FULFILLMENT_ID");
        String sku = System.getenv("EASYVIP_WEBSTORE_STAGING_SKU");
        String productType = System.getenv("EASYVIP_WEBSTORE_STAGING_PRODUCT_TYPE");
        String lineItemId = System.getenv().getOrDefault("EASYVIP_WEBSTORE_STAGING_LINE_ITEM_ID", "staging-line-item-1");

        Assumptions.assumeTrue(url != null && !url.isBlank());
        Assumptions.assumeTrue(token != null && !token.isBlank());
        Assumptions.assumeTrue(secret != null && !secret.isBlank());
        Assumptions.assumeTrue(serverId != null && !serverId.isBlank());
        Assumptions.assumeTrue(keyId != null && !keyId.isBlank());
        Assumptions.assumeTrue(fulfillmentId != null && !fulfillmentId.isBlank());
        Assumptions.assumeTrue(sku != null && !sku.isBlank());
        Assumptions.assumeTrue(productType != null && !productType.isBlank());

        EasyVipConfig.fulfillment.enabled = true;
        EasyVipConfig.fulfillment.serverId = serverId;
        EasyVipConfig.fulfillment.keyId = keyId;
        EasyVipConfig.fulfillment.keyPrefix = System.getenv().getOrDefault("EASYVIP_WEBSTORE_STAGING_KEY_PREFIX", "EVIP-");
        EasyVipConfig.fulfillment.token = token;
        EasyVipConfig.fulfillment.tokenEnv = "";
        EasyVipConfig.fulfillment.secretEnv = "";
        EasyVipConfig.fulfillment.keys.current.secret = secret;
        EasyVipConfig.fulfillment.keys.current.secretEnv = "";
        EasyVipConfig.fulfillment.keys.keys.put("current", EasyVipConfig.fulfillment.keys.current);
        EasyVipConfig.fulfillment.keys.keys.put(keyId, EasyVipConfig.fulfillment.keys.current);
        EasyVipConfig.webstore.apiUrl = url;
        EasyVipConfig.webstore.apiToken = token;
        EasyVipConfig.webstore.serverId = serverId;
        EasyVipConfig.integrations.sqlEnabled = true;
        EasyVipConfig.integrations.sqlUrl = "jdbc:h2:mem:staging-" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        EasyVipConfig.integrations.sqlUsername = "";
        EasyVipConfig.integrations.sqlPassword = "";
        EasyVipConfig.fulfillment.products.clear();

        if ("reward".equalsIgnoreCase(productType)) {
            String rewardKeyId = System.getenv("EASYVIP_WEBSTORE_STAGING_REWARD_KEY_ID");
            Assumptions.assumeTrue(rewardKeyId != null && !rewardKeyId.isBlank());
            EasyVipConfig.RewardKeyDefinition reward = new EasyVipConfig.RewardKeyDefinition();
            reward.id = rewardKeyId;
            reward.displayName = rewardKeyId;
            EasyVipConfig.rewardKeys.list.put(rewardKeyId, reward);
            FulfillmentProductConfig product = new FulfillmentProductConfig();
            product.sku = sku;
            product.type = "reward";
            product.rewardKeyId = rewardKeyId;
            product.maxUses = 1;
            product.bindToPlayer = true;
            EasyVipConfig.fulfillment.products.put(sku, product);
        } else if ("vip".equalsIgnoreCase(productType)) {
            String tierId = System.getenv("EASYVIP_WEBSTORE_STAGING_TIER_ID");
            String duration = System.getenv("EASYVIP_WEBSTORE_STAGING_DURATION");
            Assumptions.assumeTrue(tierId != null && !tierId.isBlank());
            Assumptions.assumeTrue(duration != null && !duration.isBlank());
            EasyVipConfig.VipTierDefinition tier = new EasyVipConfig.VipTierDefinition();
            tier.id = tierId;
            tier.displayName = tierId;
            tier.priority = 1;
            EasyVipConfig.tiers.list.put(tierId, tier);
            FulfillmentProductConfig product = new FulfillmentProductConfig();
            product.sku = sku;
            product.type = "vip";
            product.tierId = tierId;
            product.duration = duration;
            product.maxUses = 1;
            product.bindToPlayer = true;
            EasyVipConfig.fulfillment.products.put(sku, product);
        } else {
            Assumptions.assumeTrue(false, "Unsupported staging product type: " + productType);
        }

        PersistenceManager.initialize(tempDir);
        WebStoreFulfillmentService.start(tempDir);

        awaitCondition(() -> {
            var fulfillment = SqlDatabaseManager.getWebStoreFulfillment(fulfillmentId);
            return fulfillment != null && "completed".equalsIgnoreCase(fulfillment.getStatus());
        }, Duration.ofMinutes(2));

        int keyCount = PersistenceManager.getAllKeys().size();
        assertTrue(keyCount >= 1);

        WebStoreFulfillmentService.pollNowForTest();
        Thread.sleep(1000L);
        assertEquals(keyCount, PersistenceManager.getAllKeys().size());
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(1000L);
        }
        fail("Condition not met before timeout");
    }
}

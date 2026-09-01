package br.com.pedrodalben.easyvip.webstore;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FulfillmentConfig {

    public boolean enabled = false;
    public String serverId = "";
    public int pollIntervalSeconds = 15;
    public int claimLimit = 20;
    public int requestTimeoutSeconds = 10;
    public int timestampToleranceSeconds = 60;
    public String keyId = "";
    public String keyPrefix = "EVIP-";
    public String secretEnv = "EASYVIP_FULFILLMENT_SECRET";
    public String tokenEnv = "EASYVIP_FULFILLMENT_TOKEN";
    public String token = "";

    public final FulfillmentKeyConfig keys = new FulfillmentKeyConfig();
    public final Map<String, FulfillmentProductConfig> products = new LinkedHashMap<>();
}

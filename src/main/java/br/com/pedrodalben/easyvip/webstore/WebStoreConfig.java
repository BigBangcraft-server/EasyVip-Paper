package br.com.pedrodalben.easyvip.webstore;

public final class WebStoreConfig {

    public boolean enabled = false;
    public String apiUrl = "http://localhost:3000";
    public String apiToken = "";
    public String serverId = "";
    public boolean syncOnRegister = true;
    public boolean syncOnLogin = true;
    public boolean syncOnJoin = true;
    public boolean syncOnNickChange = true;
    public int retryMaxAttempts = 3;
    public int retryDelaySeconds = 5;

    public String challengesEndpoint() {
        return apiUrl + "/api/v1/minecraft/challenges";
    }

    public String playersSyncEndpoint() {
        return apiUrl + "/api/v1/minecraft/players/sync";
    }

    public String fulfillmentClaimEndpoint() {
        return apiUrl + "/api/v1/minecraft/fulfillments/claim";
    }

    public String fulfillmentCompleteEndpoint(String fulfillmentId) {
        return apiUrl + "/api/v1/minecraft/fulfillments/" + fulfillmentId + "/complete";
    }

    public String fulfillmentFailEndpoint(String fulfillmentId) {
        return apiUrl + "/api/v1/minecraft/fulfillments/" + fulfillmentId + "/fail";
    }
}

package br.com.pedrodalben.easyvip.webstore;

public final class FulfillmentProductConfig {

    public String sku;
    public String type;
    public String kind;
    public String tierId;
    public String duration;
    public String rewardKeyId;
    public int maxUses = 1;
    public String expiresAfter;
    public boolean bindToPlayer = true;

    public String normalizedType() {
        if (type != null && !type.isBlank()) {
            return type.trim().toLowerCase(java.util.Locale.ROOT);
        }
        if (kind != null && !kind.isBlank()) {
            return kind.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return "";
    }

    public long parseExpiresAfterMillis() {
        if (expiresAfter == null || expiresAfter.isBlank()) {
            return -1;
        }
        try {
            return br.com.pedrodalben.easyvip.util.DurationParser.parseDurationMillis(expiresAfter);
        } catch (Exception e) {
            return -1;
        }
    }
}

package br.com.pedrodalben.easyvip.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PendingVariantSelection {
    private UUID playerUuid;
    private String packageId;
    private List<String> variants = new ArrayList<>();
    private long timestamp;

    public PendingVariantSelection() {
    }

    public PendingVariantSelection(UUID playerUuid, String packageId, List<String> variants) {
        this.playerUuid = playerUuid;
        this.packageId = packageId;
        this.variants = variants;
        this.timestamp = System.currentTimeMillis();
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getPackageId() {
        return packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    public List<String> getVariants() {
        return variants;
    }

    public void setVariants(List<String> variants) {
        this.variants = variants;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isExpired(long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return false;
        }
        return System.currentTimeMillis() - timestamp > (timeoutSeconds * 1000L);
    }
}

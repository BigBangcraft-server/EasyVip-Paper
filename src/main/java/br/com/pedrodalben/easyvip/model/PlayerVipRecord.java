package br.com.pedrodalben.easyvip.model;

public class PlayerVipRecord {
    private String tierId;
    private long startTime;
    private long expiryTime; // -1 for permanent
    private boolean active;
    private boolean pendingActivateActions;

    public PlayerVipRecord() {
    }

    public PlayerVipRecord(String tierId, long startTime, long expiryTime, boolean active, boolean pendingActivateActions) {
        this.tierId = tierId;
        this.startTime = startTime;
        this.expiryTime = expiryTime;
        this.active = active;
        this.pendingActivateActions = pendingActivateActions;
    }

    public String getTierId() {
        return tierId;
    }

    public void setTierId(String tierId) {
        this.tierId = tierId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isPendingActivateActions() {
        return pendingActivateActions;
    }

    public void setPendingActivateActions(boolean pendingActivateActions) {
        this.pendingActivateActions = pendingActivateActions;
    }

    public boolean isExpired() {
        if (expiryTime == -1) {
            return false;
        }
        return System.currentTimeMillis() > expiryTime;
    }
}

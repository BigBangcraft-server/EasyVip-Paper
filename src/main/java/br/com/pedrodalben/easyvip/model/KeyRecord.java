package br.com.pedrodalben.easyvip.model;

import java.util.*;

public class KeyRecord {
    private String code;
    private String type; // "vip" or "reward"
    private String tierId;
    private String duration; // e.g., "30d", "permanent"
    private String rewardKeyId;
    private int maxUses = 1;
    private int usedCount = 0;
    private UUID boundPlayerUuid;
    private long createdTime;
    private long expiryTime = -1; // -1 for no expiration
    private Set<UUID> usedBy = new HashSet<>();
    private Map<UUID, Long> lastUsedAtBy = new HashMap<>();
    private List<Map<String, Object>> actions = new ArrayList<>();
    private Set<String> consumedInstances = new HashSet<>();

    public KeyRecord() {
    }

    public KeyRecord copy() {
        KeyRecord copy = new KeyRecord();
        copy.code = this.code;
        copy.type = this.type;
        copy.tierId = this.tierId;
        copy.duration = this.duration;
        copy.rewardKeyId = this.rewardKeyId;
        copy.maxUses = this.maxUses;
        copy.usedCount = this.usedCount;
        copy.boundPlayerUuid = this.boundPlayerUuid;
        copy.createdTime = this.createdTime;
        copy.expiryTime = this.expiryTime;
        copy.usedBy = new HashSet<>(this.usedBy != null ? this.usedBy : Collections.emptySet());
        copy.lastUsedAtBy = new HashMap<>(this.lastUsedAtBy != null ? this.lastUsedAtBy : Collections.emptyMap());
        copy.actions = new ArrayList<>(this.actions != null ? this.actions : Collections.emptyList());
        copy.consumedInstances = new HashSet<>(this.consumedInstances != null ? this.consumedInstances : Collections.emptySet());
        return copy;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTierId() {
        return tierId;
    }

    public void setTierId(String tierId) {
        this.tierId = tierId;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getRewardKeyId() {
        return rewardKeyId;
    }

    public void setRewardKeyId(String rewardKeyId) {
        this.rewardKeyId = rewardKeyId;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(int maxUses) {
        this.maxUses = maxUses;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(int usedCount) {
        this.usedCount = usedCount;
    }

    public UUID getBoundPlayerUuid() {
        return boundPlayerUuid;
    }

    public void setBoundPlayerUuid(UUID boundPlayerUuid) {
        this.boundPlayerUuid = boundPlayerUuid;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
    }

    public Set<UUID> getUsedBy() {
        return usedBy;
    }

    public void setUsedBy(Set<UUID> usedBy) {
        this.usedBy = usedBy;
    }

    public Map<UUID, Long> getLastUsedAtBy() {
        return lastUsedAtBy;
    }

    public void setLastUsedAtBy(Map<UUID, Long> lastUsedAtBy) {
        this.lastUsedAtBy = lastUsedAtBy;
    }

    public List<Map<String, Object>> getActions() {
        return actions;
    }

    public void setActions(List<Map<String, Object>> actions) {
        this.actions = actions;
    }

    public boolean isExpired() {
        if (expiryTime == -1) {
            return false;
        }
        return System.currentTimeMillis() > expiryTime;
    }

    public boolean isFullyUsed() {
        return usedCount >= maxUses;
    }

    public Set<String> getConsumedInstances() {
        return consumedInstances;
    }

    public void setConsumedInstances(Set<String> consumedInstances) {
        this.consumedInstances = consumedInstances;
    }

    public boolean isInstanceConsumed(String instanceId) {
        return instanceId != null && consumedInstances != null && consumedInstances.contains(instanceId);
    }

    public void markInstanceConsumed(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }
        if (consumedInstances == null) {
            consumedInstances = new HashSet<>();
        }
        consumedInstances.add(instanceId);
    }
}

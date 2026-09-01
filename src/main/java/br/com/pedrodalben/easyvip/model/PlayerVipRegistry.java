package br.com.pedrodalben.easyvip.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerVipRegistry {
    private UUID playerUuid;
    private String playerName;
    private Map<String, PlayerVipRecord> vips = new HashMap<>();
    private String lastObservedActiveVip;

    public String getLastObservedActiveVip() {
        return lastObservedActiveVip;
    }

    public void setLastObservedActiveVip(String lastObservedActiveVip) {
        this.lastObservedActiveVip = lastObservedActiveVip;
    }

    public PlayerVipRegistry() {
    }

    public PlayerVipRegistry(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public Map<String, PlayerVipRecord> getVips() {
        return vips;
    }

    public void setVips(Map<String, PlayerVipRecord> vips) {
        this.vips = vips;
    }
}

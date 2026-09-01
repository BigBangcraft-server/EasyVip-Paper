package br.com.pedrodalben.easyvip.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class VipActivateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final String playerName;
    private final String tierId;
    private final long durationMillis;
    private final String source;

    public VipActivateEvent(UUID playerUuid, String playerName, String tierId, long durationMillis, String source) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.tierId = tierId;
        this.durationMillis = durationMillis;
        this.source = source;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getTierId() {
        return tierId;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public String getSource() {
        return source;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

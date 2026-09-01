package br.com.pedrodalben.easyvip.action;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class ActionContext {

    private final Player player;
    private final UUID playerUuid;
    private final String playerName;
    private final String source;

    private ActionContext(Player player, UUID playerUuid, String playerName, String source) {
        this.player = player;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.source = source != null ? source : "unknown";
    }

    public static ActionContext online(Player player, String source) {
        if (player == null) {
            throw new IllegalArgumentException("player cannot be null for online ActionContext");
        }
        return new ActionContext(player, player.getUniqueId(), player.getName(), source);
    }

    public static ActionContext offline(UUID playerUuid, String playerName, String source) {
        if (playerUuid == null) {
            throw new IllegalArgumentException("playerUuid cannot be null for offline ActionContext");
        }
        String resolvedName = (playerName != null && !playerName.isBlank()) ? playerName : playerUuid.toString();
        Player online = null;
        try {
            if (Bukkit.getServer() != null) {
                online = Bukkit.getPlayer(playerUuid);
            }
        } catch (Throwable ignored) {
        }
        return new ActionContext(online, playerUuid, resolvedName, source);
    }

    public Player getOnlinePlayer() {
        if (player != null && player.isOnline()) {
            return player;
        }
        if (playerUuid != null) {
            try {
                if (Bukkit.getServer() != null) {
                    return Bukkit.getPlayer(playerUuid);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getSource() {
        return source;
    }

    public boolean isOnline() {
        Player p = getOnlinePlayer();
        return p != null && p.isOnline();
    }
}

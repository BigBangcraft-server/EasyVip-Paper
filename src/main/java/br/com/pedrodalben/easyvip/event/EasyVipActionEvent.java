package br.com.pedrodalben.easyvip.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;

public class EasyVipActionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String hook;
    private final Map<String, String> context;

    public EasyVipActionEvent(Player player, String hook, Map<String, String> context) {
        this.player = player;
        this.hook = hook;
        this.context = context != null ? Collections.unmodifiableMap(context) : Collections.emptyMap();
    }

    public Player getPlayer() {
        return player;
    }

    public String getHook() {
        return hook;
    }

    public Map<String, String> getContext() {
        return context;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

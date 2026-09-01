package br.com.pedrodalben.easyvip.platform;

import org.bukkit.entity.Player;
import java.math.BigDecimal;

public interface EconomyBridge {

    boolean hasBalance(Player player, BigDecimal amount);

    boolean withdraw(Player player, BigDecimal amount);

    boolean deposit(Player player, BigDecimal amount);
}

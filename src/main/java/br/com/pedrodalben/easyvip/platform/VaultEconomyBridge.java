package br.com.pedrodalben.easyvip.platform;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;

public final class VaultEconomyBridge implements EconomyBridge {

    private Economy economy;
    private boolean checked = false;

    private Economy getEconomy() {
        if (!checked) {
            checked = true;
            try {
                if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
                    RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
                    if (rsp != null) {
                        economy = rsp.getProvider();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return economy;
    }

    @Override
    public boolean hasBalance(Player player, BigDecimal amount) {
        Economy econ = getEconomy();
        if (econ == null || player == null || amount == null) {
            return false;
        }
        return econ.has(player, amount.doubleValue());
    }

    @Override
    public boolean withdraw(Player player, BigDecimal amount) {
        Economy econ = getEconomy();
        if (econ == null || player == null || amount == null) {
            return false;
        }
        return econ.withdrawPlayer(player, amount.doubleValue()).transactionSuccess();
    }

    @Override
    public boolean deposit(Player player, BigDecimal amount) {
        Economy econ = getEconomy();
        if (econ == null || player == null || amount == null) {
            return false;
        }
        return econ.depositPlayer(player, amount.doubleValue()).transactionSuccess();
    }
}

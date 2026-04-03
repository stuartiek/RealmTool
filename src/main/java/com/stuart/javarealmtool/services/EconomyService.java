package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.UUID;

public class EconomyService {
    private final JavaRealmTool plugin;

    public EconomyService(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    public long getCoins(UUID uuid) {
        String key = "coins." + uuid;
        long amount = plugin.getEconomyConfig().getLong(key, Long.MIN_VALUE);
        if (amount != Long.MIN_VALUE) return amount;

        String legacyKey = "drowsy_coins." + uuid;
        amount = plugin.getEconomyConfig().getLong(legacyKey, Long.MIN_VALUE);
        if (amount != Long.MIN_VALUE) {
            plugin.getEconomyConfig().set(key, amount);
            plugin.getEconomyConfig().set(legacyKey, null);
            plugin.saveEconomyFile();
            return amount;
        }

        return 0;
    }

    public void setCoins(UUID uuid, long amount) {
        plugin.getEconomyConfig().set("coins." + uuid, amount);
        plugin.getEconomyConfig().set("drowsy_coins." + uuid, amount);
        plugin.saveEconomyFile();
    }

    public void addCoins(UUID uuid, long delta) {
        setCoins(uuid, getCoins(uuid) + delta);
    }

    public boolean isDrowsyCoin(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        return meta.getDisplayName().equals(ChatColor.GOLD + "Drowsy Coins");
    }

    public ItemStack makeDrowsyCoinStack(int amount) {
        ItemStack coin = new ItemStack(Material.GOLD_NUGGET, amount);
        ItemMeta meta = coin.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Drowsy Coins");
            coin.setItemMeta(meta);
        }
        return coin;
    }

    public long countDrowsyCoins(Player p) {
        long total = 0;
        for (ItemStack item : p.getInventory().getContents()) {
            if (isDrowsyCoin(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public long removeDrowsyCoins(Player p, long amount) {
        long remaining = amount;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack item = inv.getItem(i);
            if (isDrowsyCoin(item)) {
                int stack = item.getAmount();
                if (stack <= remaining) {
                    remaining -= stack;
                    inv.setItem(i, null);
                } else {
                    item.setAmount((int) (stack - remaining));
                    inv.setItem(i, item);
                    remaining = 0;
                }
            }
        }
        return amount - remaining;
    }

    public void giveDrowsyCoins(Player p, long amount) {
        while (amount > 0) {
            int give = (int) Math.min(64, amount);
            ItemStack stack = makeDrowsyCoinStack(give);
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(stack);
            for (ItemStack item : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), item);
            }
            amount -= give;
        }
    }
}

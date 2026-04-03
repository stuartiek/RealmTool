package com.stuart.javarealmtool.commands;

import com.stuart.javarealmtool.JavaRealmTool;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class BalanceCommand implements CommandExecutor, TabCompleter {

    private final JavaRealmTool plugin;

    public BalanceCommand(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(Arrays.asList("withdraw", "deposit"));
            if (sender.hasPermission("dmt.admin")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    completions.add(p.getName());
                }
            }
            return completions.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && sender.hasPermission("dmt.admin")) {
            // Check if first arg is a player name, not withdraw/deposit
            if (!args[0].equalsIgnoreCase("withdraw") && !args[0].equalsIgnoreCase("deposit")) {
                return Arrays.asList("add", "remove", "reset").stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
            return true;
        }
        Player p = (Player) sender;

        if (args.length == 0) {
            long coins = plugin.getCoins(p.getUniqueId());
            int xp = p.getLevel();
            p.sendMessage(ChatColor.GOLD + "Your balance: " + ChatColor.GREEN + coins + " Drowsy coins " + ChatColor.AQUA + "| XP level: " + xp);
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("withdraw") || sub.equals("deposit")) {
            if (args.length != 2) {
                p.sendMessage(ChatColor.RED + "Usage: /balance " + sub + " <amount>");
                return true;
            }

            long amount;
            try {
                amount = Long.parseLong(args[1]);
            } catch (NumberFormatException ex) {
                p.sendMessage(ChatColor.RED + "Invalid amount.");
                return true;
            }
            if (amount <= 0) {
                p.sendMessage(ChatColor.RED + "Amount must be positive.");
                return true;
            }

            if (sub.equals("withdraw")) {
                long coins = plugin.getCoins(p.getUniqueId());
                if (coins < amount) {
                    p.sendMessage(ChatColor.RED + "Insufficient balance. You have " + coins + " Drowsy coins.");
                    return true;
                }
                plugin.addCoins(p.getUniqueId(), -amount);
                plugin.giveDrowsyCoins(p, amount);
                p.sendMessage(ChatColor.GREEN + "Withdrew " + amount + " Drowsy coins. Your new balance is " + (coins - amount) + " Drowsy coins.");
                return true;
            } else { // deposit
                long held = plugin.countDrowsyCoins(p);
                if (held < amount) {
                    p.sendMessage(ChatColor.RED + "You only have " + held + " Drowsy coins in your inventory.");
                    return true;
                }
                plugin.removeDrowsyCoins(p, amount);
                plugin.addCoins(p.getUniqueId(), amount);
                p.sendMessage(ChatColor.GREEN + "Deposited " + amount + " Drowsy coins. Your new balance is " + (plugin.getCoins(p.getUniqueId())) + " Drowsy coins.");
                return true;
            }
        }

        if (!p.hasPermission("dmt.admin")) {
            p.sendMessage(ChatColor.RED + "You do not have permission to manage other players' balance.");
            return true;
        }

        String targetName = args[0];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target == null || target.getUniqueId() == null) {
            p.sendMessage(ChatColor.RED + "Player not found: " + targetName);
            return true;
        }

        if (args.length == 1) {
            long coins = plugin.getCoins(target.getUniqueId());
            p.sendMessage(ChatColor.GOLD + "Balance for " + ChatColor.AQUA + target.getName() + ChatColor.GOLD + ": " + ChatColor.GREEN + coins + " Drowsy coins");
            return true;
        }

        String action = args[1].toLowerCase();
        if (action.equals("reset")) {
            plugin.setCoins(target.getUniqueId(), 0);
            p.sendMessage(ChatColor.GREEN + "Reset " + target.getName() + "'s balance.");
            return true;
        }

        if (args.length < 3) {
            p.sendMessage(ChatColor.RED + "Usage: /balance <player> <add|remove> <amount> or /balance <player> reset");
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage(ChatColor.RED + "Invalid amount.");
            return true;
        }
        if (amount < 0) {
            p.sendMessage(ChatColor.RED + "Amount must be positive.");
            return true;
        }

        if (action.equals("add")) {
            plugin.addCoins(target.getUniqueId(), amount);
            p.sendMessage(ChatColor.GREEN + "Added " + amount + " coins to " + target.getName() + "'s balance.");
        } else if (action.equals("remove")) {
            plugin.addCoins(target.getUniqueId(), -amount);
            p.sendMessage(ChatColor.GREEN + "Removed " + amount + " coins from " + target.getName() + "'s balance.");
        } else {
            p.sendMessage(ChatColor.RED + "Unknown action. Use add/remove/reset.");
        }
        return true;
    }
}
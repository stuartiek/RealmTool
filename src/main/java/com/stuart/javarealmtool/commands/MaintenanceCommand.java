package com.stuart.javarealmtool.commands;

import com.stuart.javarealmtool.JavaRealmTool;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

public class MaintenanceCommand implements CommandExecutor, TabCompleter {

    private static final String DEFAULT_MESSAGE = "Server is under maintenance...";

    private final JavaRealmTool plugin;

    public MaintenanceCommand(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dmt.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use maintenance mode.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            boolean enabled = plugin.getDataConfig().getBoolean("maintenance.enabled", false);
            String message = plugin.getDataConfig().getString("maintenance.message", DEFAULT_MESSAGE);
            sender.sendMessage(ChatColor.GOLD + "Maintenance mode: " + (enabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            sender.sendMessage(ChatColor.GOLD + "Message: " + ChatColor.YELLOW + message);
            return true;
        }

        String action = args[0].toLowerCase();
        if (!action.equals("on") && !action.equals("off")) {
            sender.sendMessage(ChatColor.RED + "Usage: /maintenance <on|off|status> [message]");
            return true;
        }

        boolean enabled = action.equals("on");
        String message = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim() : DEFAULT_MESSAGE;
        if (!enabled) {
            message = plugin.getDataConfig().getString("maintenance.message", DEFAULT_MESSAGE);
        } else if (message.isEmpty()) {
            message = DEFAULT_MESSAGE;
        }

        plugin.getDataConfig().set("maintenance.enabled", enabled);
        plugin.getDataConfig().set("maintenance.message", message);
        plugin.getDataConfig().set("maintenance.startTime", "");
        plugin.getDataConfig().set("maintenance.endTime", "");
        plugin.saveDataFile();
        plugin.logAction(sender.getName(), enabled ? "enabled" : "disabled", "maintenance mode");

        if (enabled) {
            Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "[Maintenance] " + ChatColor.RESET + ChatColor.RED + message);
            List<String> whitelist = plugin.getDataConfig().getStringList("maintenance.whitelist");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!whitelist.contains(player.getName())) {
                    player.kickPlayer(ChatColor.RED + message);
                }
            }
            sender.sendMessage(ChatColor.GREEN + "Maintenance mode enabled.");
        } else {
            Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "[Maintenance] " + ChatColor.RESET + ChatColor.GREEN + "Maintenance mode has been disabled.");
            sender.sendMessage(ChatColor.GREEN + "Maintenance mode disabled.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("dmt.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(Arrays.asList("on", "off", "status"));
            return completions.stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
package com.stuart.javarealmtool.commands;

import com.stuart.javarealmtool.JavaRealmTool;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

public class TicketCommand implements CommandExecutor, TabCompleter {

    private final JavaRealmTool plugin;

    public TicketCommand(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
            return true;
        }
        Player p = (Player) sender;

        if (args.length == 0) {
            p.sendMessage(ChatColor.GOLD + "===== Ticket System =====");
            p.sendMessage(ChatColor.YELLOW + "/ticket new [category] <message>" + ChatColor.GRAY + " - Create a ticket");
            p.sendMessage(ChatColor.YELLOW + "/ticket list" + ChatColor.GRAY + " - View your tickets");
            p.sendMessage(ChatColor.YELLOW + "/ticket view <id>" + ChatColor.GRAY + " - View ticket details");
            p.sendMessage(ChatColor.YELLOW + "/ticket close <id>" + ChatColor.GRAY + " - Close your ticket");
            p.sendMessage(ChatColor.GRAY + "Categories: bug, griefing, chat, item_loss, pvp, other");
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("new")) {
            if (args.length < 2) {
                p.sendMessage(ChatColor.RED + "Usage: /ticket new [category] <message>");
                return true;
            }
            // Cooldown check (60 seconds)
            long now = System.currentTimeMillis();
            Long lastTicket = plugin.getTicketCooldowns().get(p.getUniqueId());
            if (lastTicket != null && (now - lastTicket) < 60000) {
                long remaining = (60000 - (now - lastTicket)) / 1000;
                p.sendMessage(ChatColor.RED + "Please wait " + remaining + "s before creating another ticket.");
                return true;
            }
            // Check if second arg is a category
            Set<String> validCategories = new HashSet<>(Arrays.asList("bug", "griefing", "chat", "item_loss", "pvp", "other"));
            String category = "other";
            int messageStart = 1;
            if (args.length > 2 && validCategories.contains(args[1].toLowerCase())) {
                category = args[1].toLowerCase();
                messageStart = 2;
            }
            if (messageStart >= args.length) {
                p.sendMessage(ChatColor.RED + "Please provide a message for your ticket.");
                return true;
            }
            int id = plugin.getTicketConfig().getInt("tickets.next_id", 1);
            String path = "tickets." + id;
            plugin.getTicketConfig().set(path + ".player", p.getName());
            plugin.getTicketConfig().set(path + ".uuid", p.getUniqueId().toString());
            plugin.getTicketConfig().set(path + ".message", String.join(" ", Arrays.copyOfRange(args, messageStart, args.length)));
            plugin.getTicketConfig().set(path + ".status", "open");
            plugin.getTicketConfig().set(path + ".priority", "medium");
            plugin.getTicketConfig().set(path + ".category", category);
            plugin.getTicketConfig().set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
            // Save location
            plugin.getTicketConfig().set(path + ".world", p.getWorld().getName());
            plugin.getTicketConfig().set(path + ".x", p.getLocation().getBlockX());
            plugin.getTicketConfig().set(path + ".y", p.getLocation().getBlockY());
            plugin.getTicketConfig().set(path + ".z", p.getLocation().getBlockZ());
            plugin.getTicketConfig().set("tickets.next_id", id + 1);
            plugin.saveTicketFile();
            plugin.getTicketCooldowns().put(p.getUniqueId(), now);
            p.sendMessage(ChatColor.GREEN + "Ticket #" + id + " created (" + category + "). Staff will review it soon.");
            // Notify online staff
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("realmtool.admin") || staff.hasPermission("dmt.admin")) {
                    staff.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + p.getName() + " created ticket #" + id + ": " + ChatColor.GRAY + plugin.getTicketConfig().getString(path + ".message"));
                }
            }
            plugin.sendWebPush("New Ticket #" + id, p.getName() + " needs help: " + plugin.getTicketConfig().getString(path + ".message"));
            return true;
        }

        if (sub.equals("list")) {
            boolean found = false;
            if (plugin.getTicketConfig().contains("tickets")) {
                for (String key : plugin.getTicketConfig().getConfigurationSection("tickets").getKeys(false)) {
                    if (key.equals("next_id")) continue;
                    String ticketPlayer = plugin.getTicketConfig().getString("tickets." + key + ".player", "");
                    if (ticketPlayer.equalsIgnoreCase(p.getName())) {
                        String status = plugin.getTicketConfig().getString("tickets." + key + ".status", "open");
                        String msg = plugin.getTicketConfig().getString("tickets." + key + ".message", "");
                        ChatColor statusColor = status.equals("open") ? ChatColor.GREEN : status.equals("resolved") ? ChatColor.AQUA : ChatColor.GRAY;
                        p.sendMessage(ChatColor.GOLD + "#" + key + " " + statusColor + "[" + status.toUpperCase() + "] " + ChatColor.WHITE + (msg.length() > 40 ? msg.substring(0, 40) + "..." : msg));
                        found = true;
                    }
                }
            }
            if (!found) p.sendMessage(ChatColor.YELLOW + "You have no tickets.");
            return true;
        }

        if (sub.equals("view")) {
            if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /ticket view <id>"); return true; }
            String ticketId = args[1];
            String base = "tickets." + ticketId;
            if (!plugin.getTicketConfig().contains(base)) { p.sendMessage(ChatColor.RED + "Ticket not found."); return true; }
            String ticketPlayer = plugin.getTicketConfig().getString(base + ".player", "");
            if (!ticketPlayer.equalsIgnoreCase(p.getName()) && !p.hasPermission("realmtool.admin") && !p.hasPermission("dmt.admin")) {
                p.sendMessage(ChatColor.RED + "You can only view your own tickets.");
                return true;
            }
            p.sendMessage(ChatColor.GOLD + "===== Ticket #" + ticketId + " =====");
            p.sendMessage(ChatColor.YELLOW + "Player: " + ChatColor.WHITE + ticketPlayer);
            p.sendMessage(ChatColor.YELLOW + "Status: " + ChatColor.WHITE + plugin.getTicketConfig().getString(base + ".status", "open"));
            p.sendMessage(ChatColor.YELLOW + "Priority: " + ChatColor.WHITE + plugin.getTicketConfig().getString(base + ".priority", "medium"));
            p.sendMessage(ChatColor.YELLOW + "Category: " + ChatColor.WHITE + plugin.getTicketConfig().getString(base + ".category", "other"));
            p.sendMessage(ChatColor.YELLOW + "Message: " + ChatColor.WHITE + plugin.getTicketConfig().getString(base + ".message", ""));
            p.sendMessage(ChatColor.YELLOW + "Created: " + ChatColor.WHITE + plugin.getTicketConfig().getString(base + ".timestamp", ""));
            String resolution = plugin.getTicketConfig().getString(base + ".resolution");
            if (resolution != null) p.sendMessage(ChatColor.YELLOW + "Resolution: " + ChatColor.WHITE + resolution);
            List<String> responses = plugin.getTicketConfig().getStringList(base + ".responses");
            if (!responses.isEmpty()) {
                p.sendMessage(ChatColor.GOLD + "--- Responses ---");
                for (String resp : responses) {
                    String[] parts = resp.split(" \\| ", 3);
                    if (parts.length == 3) {
                        p.sendMessage(ChatColor.AQUA + parts[1] + ChatColor.GRAY + " (" + parts[0] + "): " + ChatColor.WHITE + parts[2]);
                    } else {
                        p.sendMessage(ChatColor.GRAY + resp);
                    }
                }
            }
            return true;
        }

        if (sub.equals("close")) {
            if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /ticket close <id>"); return true; }
            String ticketId = args[1];
            String base = "tickets." + ticketId;
            if (!plugin.getTicketConfig().contains(base)) { p.sendMessage(ChatColor.RED + "Ticket not found."); return true; }
            String ticketPlayer = plugin.getTicketConfig().getString(base + ".player", "");
            if (!ticketPlayer.equalsIgnoreCase(p.getName()) && !p.hasPermission("realmtool.admin") && !p.hasPermission("dmt.admin")) {
                p.sendMessage(ChatColor.RED + "You can only close your own tickets.");
                return true;
            }
            String currentStatus = plugin.getTicketConfig().getString(base + ".status", "open");
            if (currentStatus.equals("closed")) {
                p.sendMessage(ChatColor.RED + "This ticket is already closed.");
                return true;
            }
            plugin.getTicketConfig().set(base + ".status", "closed");
            plugin.saveTicketFile();
            p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " closed.");
            return true;
        }

        p.sendMessage(ChatColor.RED + "Unknown subcommand. Use /ticket for help.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> completions = Arrays.asList("new", "list", "view", "close");
            List<String> matches = new ArrayList<>();
            for (String s : completions) {
                if (s.startsWith(args[0].toLowerCase())) matches.add(s);
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("new")) {
            List<String> completions = Arrays.asList("bug", "griefing", "chat", "item_loss", "pvp", "other");
            List<String> matches = new ArrayList<>();
            for (String s : completions) {
                if (s.startsWith(args[1].toLowerCase())) matches.add(s);
            }
            return matches;
        }
        return Collections.emptyList();
    }
}
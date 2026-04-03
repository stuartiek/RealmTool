package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

public class TicketService {
    private final JavaRealmTool plugin;
    private final Map<UUID, Long> ticketCooldowns = new HashMap<>();

    public TicketService(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    public Map<UUID, Long> getTicketCooldowns() {
        return ticketCooldowns;
    }

    public void createTicket(Player p, String text) {
        long now = System.currentTimeMillis();
        Long lastTicket = ticketCooldowns.get(p.getUniqueId());
        if (lastTicket != null && (now - lastTicket) < 60000) {
            long remaining = (60000 - (now - lastTicket)) / 1000;
            p.sendMessage(ChatColor.RED + "Please wait " + remaining + "s before creating another ticket.");
            return;
        }
        Set<String> validCategories = Set.of("bug", "griefing", "chat", "item_loss", "pvp", "other");
        String category = "other";
        String message = text;
        String[] parts = text.split(" ");
        if (parts.length > 1 && validCategories.contains(parts[0].toLowerCase())) {
            category = parts[0].toLowerCase();
            message = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        }
        if (message == null || message.trim().isEmpty()) {
            p.sendMessage(ChatColor.RED + "Please provide a message for your ticket.");
            return;
        }
        int id = plugin.getTicketConfig().getInt("tickets.next_id", 1);
        String path = "tickets." + id;
        plugin.getTicketConfig().set(path + ".player", p.getName());
        plugin.getTicketConfig().set(path + ".uuid", p.getUniqueId().toString());
        plugin.getTicketConfig().set(path + ".message", message);
        plugin.getTicketConfig().set(path + ".status", "open");
        plugin.getTicketConfig().set(path + ".priority", "medium");
        plugin.getTicketConfig().set(path + ".category", category);
        plugin.getTicketConfig().set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        plugin.getTicketConfig().set(path + ".world", p.getWorld().getName());
        plugin.getTicketConfig().set(path + ".x", p.getLocation().getBlockX());
        plugin.getTicketConfig().set(path + ".y", p.getLocation().getBlockY());
        plugin.getTicketConfig().set(path + ".z", p.getLocation().getBlockZ());
        plugin.getTicketConfig().set("tickets.next_id", id + 1);
        plugin.saveTicketFile();
        ticketCooldowns.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.GREEN + "Ticket #" + id + " created (" + category + "). Staff will review it soon.");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("realmtool.admin")) {
                staff.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + p.getName() + " created ticket #" + id + ": " + ChatColor.GRAY + message);
            }
        }
        plugin.sendWebPush("New Ticket #" + id, p.getName() + " needs help: " + message);
        p.sendMessage(ChatColor.GREEN + "Your ticket has been submitted! A staff member will review it shortly.");
    }

    public void createAppeal(Player p, String text) {
        long now = System.currentTimeMillis();
        Long last = ticketCooldowns.get(p.getUniqueId());
        if (last != null && (now - last) < 60000) {
            long remaining = (60000 - (now - last)) / 1000;
            p.sendMessage(ChatColor.RED + "Please wait " + remaining + "s before creating another appeal.");
            return;
        }
        Set<String> validCategories = Set.of("bug", "griefing", "chat", "item_loss", "pvp", "other");
        String category = "other";
        String message = text;
        String[] parts = text.split(" ");
        if (parts.length > 1 && validCategories.contains(parts[0].toLowerCase())) {
            category = parts[0].toLowerCase();
            message = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        }
        if (message == null || message.trim().isEmpty()) {
            p.sendMessage(ChatColor.RED + "Please provide a message for your appeal.");
            return;
        }
        int id = plugin.getTicketConfig().getInt("appeals.next_id", 1);
        String path = "appeals." + id;
        plugin.getTicketConfig().set(path + ".player", p.getName());
        plugin.getTicketConfig().set(path + ".uuid", p.getUniqueId().toString());
        plugin.getTicketConfig().set(path + ".message", message);
        plugin.getTicketConfig().set(path + ".status", "open");
        plugin.getTicketConfig().set(path + ".priority", "medium");
        plugin.getTicketConfig().set(path + ".category", category);
        plugin.getTicketConfig().set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        plugin.getTicketConfig().set("appeals.next_id", id + 1);
        plugin.saveTicketFile();
        ticketCooldowns.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.GREEN + "Appeal #" + id + " created (" + category + "). Staff will review it soon.");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("realmtool.admin")) {
                staff.sendMessage(ChatColor.GOLD + "[Appeals] " + ChatColor.YELLOW + p.getName() + " created appeal #" + id + ": " + ChatColor.GRAY + message);
            }
        }
        plugin.sendWebPush("New Appeal #" + id, p.getName() + " submitted an appeal: " + message);
        p.sendMessage(ChatColor.GREEN + "Your appeal has been submitted! A staff member will review it shortly.");
    }

    public Map<String, Object> getTicketData(int id) {
        Map<String, Object> m = new HashMap<>();
        String base = id < 0 ? "appeals." + (-id) : "tickets." + id;
        if (!plugin.getTicketConfig().contains(base)) return m;
        m.put("id", Integer.toString(id));
        m.put("player", plugin.getTicketConfig().getString(base + ".player", ""));
        m.put("message", plugin.getTicketConfig().getString(base + ".message", ""));
        m.put("status", plugin.getTicketConfig().getString(base + ".status", "open"));
        m.put("priority", plugin.getTicketConfig().getString(base + ".priority", "medium"));
        m.put("category", plugin.getTicketConfig().getString(base + ".category", "other"));
        m.put("assignee", plugin.getTicketConfig().getString(base + ".assignee", ""));
        m.put("timestamp", plugin.getTicketConfig().getString(base + ".timestamp", ""));
        m.put("resolution", plugin.getTicketConfig().getString(base + ".resolution", ""));

        if (plugin.getTicketConfig().contains(base + ".world")) {
            Map<String, Object> loc = new HashMap<>();
            loc.put("world", plugin.getTicketConfig().getString(base + ".world", ""));
            loc.put("x", plugin.getTicketConfig().getInt(base + ".x", 0));
            loc.put("y", plugin.getTicketConfig().getInt(base + ".y", 0));
            loc.put("z", plugin.getTicketConfig().getInt(base + ".z", 0));
            m.put("location", loc);
        }

        List<Map<String, String>> parsedResponses = new ArrayList<>();
        for (String raw : plugin.getTicketConfig().getStringList(base + ".responses")) {
            String[] parts = raw.split(" \\| ", 3);
            Map<String, String> resp = new HashMap<>();
            if (parts.length == 3) {
                resp.put("timestamp", parts[0].trim());
                resp.put("admin", parts[1].trim());
                resp.put("message", parts[2].trim());
            } else {
                resp.put("timestamp", "");
                resp.put("admin", "Unknown");
                resp.put("message", raw);
            }
            parsedResponses.add(resp);
        }
        m.put("responses", parsedResponses);
        return m;
    }

    public void addTicketResponse(int id, String admin, String message) {
        String base = id < 0 ? "appeals." + (-id) : "tickets." + id;
        String path = base + ".responses";
        List<String> responses = plugin.getTicketConfig().getStringList(path);
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + admin + " | " + message;
        responses.add(entry);
        plugin.getTicketConfig().set(path, responses);
        plugin.getTicketConfig().set(base + ".has_new_response", true);
        plugin.saveTicketFile();
        String playerName = plugin.getTicketConfig().getString(base + ".player", "");
        Player target = Bukkit.getPlayer(playerName);
        if (target != null && target.isOnline()) {
            target.playSound(target.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1F, 2F);
            target.sendMessage(ChatColor.GOLD + "[" + (id < 0 ? "Appeals" : "Tickets") + "] " + ChatColor.GREEN + admin + " responded to your " + (id < 0 ? "appeal" : "ticket") + " #" + Math.abs(id) + ": " + ChatColor.WHITE + message);
        }
    }

    public void updateTicketField(int id, String field, String value) {
        plugin.getTicketConfig().set((id < 0 ? "appeals." + (-id) : "tickets." + id) + "." + field, value);
        plugin.saveTicketFile();
    }

    public void resolveTicket(int id, String reason) {
        String base = id < 0 ? "appeals." + (-id) : "tickets." + id;
        plugin.getTicketConfig().set(base + ".status", "resolved");
        plugin.getTicketConfig().set(base + ".resolution", reason);
        plugin.getTicketConfig().set(base + ".has_new_response", true);
        plugin.saveTicketFile();
        String playerName = plugin.getTicketConfig().getString(base + ".player", "");
        Player target = Bukkit.getPlayer(playerName);
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatColor.GOLD + "[" + (id < 0 ? "Appeals" : "Tickets") + "] " + ChatColor.GREEN + "Your " + (id < 0 ? "appeal" : "ticket") + " #" + Math.abs(id) + " has been resolved: " + ChatColor.WHITE + reason);
        }
    }
}

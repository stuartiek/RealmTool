package com.stuart.javarealmtool.web;

import com.stuart.javarealmtool.JavaRealmTool;
import com.stuart.javarealmtool.WebServer;
import io.javalin.Javalin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.Future;
import java.util.UUID;

public class WebRankController {
    private final JavaRealmTool plugin;
    private final WebServer webServer;

    public WebRankController(JavaRealmTool plugin, WebServer webServer) {
        this.plugin = plugin;
        this.webServer = webServer;
    }

    public void registerRoutes(Javalin app) {
        app.get("/api/ranks", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.view.groups")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> ranks = new ArrayList<>();
                var section = plugin.getRankConfig().getConfigurationSection("ranks");
                if (section != null) {
                    for (String name : section.getKeys(false)) {
                        Map<String, Object> r = new HashMap<>();
                        String path = "ranks." + name;
                        r.put("name", name);
                        String c = plugin.getRankConfig().getString(path + ".color");
                        if (c == null || c.isEmpty() || c.equals("#ffffff") || c.equals("#aaaaaa")) {
                            String inf = plugin.inferHexColorFromPrefix(plugin.getRankConfig().getString(path + ".prefix", ""));
                            if (!inf.equals("#ffffff")) c = inf;
                        }
                        r.put("color", c);
                        r.put("prefix", plugin.getRankConfig().getString(path + ".prefix", ""));
                        r.put("level", plugin.getRankConfig().getInt(path + ".level", 1));
                        r.put("description", plugin.getRankConfig().getString(path + ".description", ""));
                        ranks.add(r);
                    }
                }
                return Map.of("ranks", ranks);
            });
            ctx.json(future.get());
        });

        app.post("/api/ranks/create", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String name = (String) body.get("name");
            if (name == null || name.isBlank()) { ctx.status(400).json(Map.of("error", "Name required")); return; }
            String finalName = name.replaceAll("[^a-zA-Z0-9_-]", "");

            Bukkit.getScheduler().runTask(plugin, () -> {
                String path = "ranks." + finalName;
                if (!plugin.getRankConfig().contains(path)) {
                    String hexColor = (String) body.getOrDefault("color", "#ffffff");
                    plugin.getRankConfig().set(path + ".color", hexColor);
                    plugin.getRankConfig().set(path + ".level", body.getOrDefault("level", 1));
                    plugin.getRankConfig().set(path + ".description", body.getOrDefault("description", ""));

                    String spigotColor = "";
                    if (hexColor.startsWith("#") && hexColor.length() == 7) {
                        spigotColor = "&x";
                        for (char c : hexColor.substring(1).toCharArray()) {
                            spigotColor += "&" + c;
                        }
                    } else {
                        spigotColor = "&7";
                    }
                    plugin.getRankConfig().set(path + ".prefix", spigotColor + "[" + finalName + "] &r");
                    plugin.getRankConfig().set(path + ".permissions", new ArrayList<String>());
                    plugin.getRankConfig().set(path + ".members", new ArrayList<String>());
                    plugin.saveRankFile();
                    plugin.logAction("WebAdmin", "created rank", finalName);
                }
            });
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ranks/update", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String name = (String) body.get("name");
            if (name == null) { ctx.status(400).json(Map.of("error", "Name required")); return; }

            Bukkit.getScheduler().runTask(plugin, () -> {
                String path = "ranks." + name;
                if (plugin.getRankConfig().contains(path)) {
                    if (body.containsKey("description")) plugin.getRankConfig().set(path + ".description", body.get("description"));
                    if (body.containsKey("level")) plugin.getRankConfig().set(path + ".level", body.get("level"));
                    if (body.containsKey("color")) {
                        String hexColor = (String) body.get("color");
                        plugin.getRankConfig().set(path + ".color", hexColor);

                        String spigotColor = "";
                        if (hexColor.startsWith("#") && hexColor.length() == 7) {
                            spigotColor = "&x";
                            for (char c : hexColor.substring(1).toCharArray()) {
                                spigotColor += "&" + c;
                            }
                        } else {
                            spigotColor = "&7";
                        }
                        plugin.getRankConfig().set(path + ".prefix", spigotColor + "[" + name + "] &r");
                    }
                    plugin.saveRankFile();
                    plugin.refreshAllPermissions();
                    plugin.logAction("WebAdmin", "updated rank", name);
                }
            });
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ranks/delete", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String name = (String) body.get("name");

            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getRankConfig().set("ranks." + name, null);
                if (plugin.getRankConfig().contains("player_rank")) {
                    for (String uuidKey : plugin.getRankConfig().getConfigurationSection("player_rank").getKeys(false)) {
                        String assigned = plugin.getRankConfig().getString("player_rank." + uuidKey);
                        if (assigned != null && assigned.equals(name)) {
                            plugin.getRankConfig().set("player_rank." + uuidKey, null);
                        }
                    }
                }
                plugin.saveRankFile();
                plugin.refreshAllPermissions();
                plugin.logAction("WebAdmin", "deleted rank", name);
            });
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ranks/promote", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String player = (String) body.get("player");
            String rank = (String) body.get("rank");

            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                plugin.setPlayerRank(uuid, rank);
                plugin.getDataConfig().set("users." + uuid + ".promotedBy", "WebAdmin");
                plugin.getDataConfig().set("users." + uuid + ".promotionDate", System.currentTimeMillis());
                plugin.saveDataFile();
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) plugin.applyPermissionGroup(p);
                plugin.logAction("WebAdmin", "promoted " + player + " to", rank);
            });
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ranks/demote", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String player = (String) body.get("player");

            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                plugin.setPlayerRank(uuid, null);
                plugin.saveDataFile();
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) plugin.applyPermissionGroup(p);
                plugin.logAction("WebAdmin", "demoted", player);
            });
            ctx.json(Map.of("status", true));
        });
    }
}

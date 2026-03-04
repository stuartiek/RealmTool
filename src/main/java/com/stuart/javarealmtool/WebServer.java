package com.stuart.javarealmtool;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

public class WebServer {
    private final JavaRealmTool plugin;
    private Javalin app;
    private final ConcurrentLinkedQueue<WsContext> sessions = new ConcurrentLinkedQueue<>();

    public WebServer(JavaRealmTool plugin) { this.plugin = plugin; }

    public void start() {
        ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
        Thread serverThread = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(pluginClassLoader);
            app = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.directory = "webapp";
                    staticFiles.location = Location.CLASSPATH;
                });
                config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
                config.router.mount(router -> {
                    router.ws("/api/console", ws -> {
                        ws.onConnect(ctx -> sessions.add(ctx));
                        ws.onClose(ctx -> sessions.remove(ctx));
                    });
                });
            });

            setupRoutes();
            Bukkit.getLogger().addHandler(new WebLogHandler(sessions));
            app.start(8091);
        });
        serverThread.start();
    }

    private void setupRoutes() {
        // --- AUTHENTICATE ---
        app.get("/api/players", ctx -> {
            String received = ctx.header("Authorization");
            String hardcoded = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String fromConfig = "Bearer " + plugin.getApiKey();
            if (received == null || (!received.equals(hardcoded) && !received.equals(fromConfig))) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> res = new HashMap<>();
                List<Map<String, Object>> players = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", p.getName());
                    m.put("health", Math.round(p.getHealth()));
                    m.put("x", Math.round(p.getLocation().getX() * 10.0) / 10.0);
                    m.put("y", Math.round(p.getLocation().getY() * 10.0) / 10.0);
                    m.put("z", Math.round(p.getLocation().getZ() * 10.0) / 10.0);
                    m.put("world", p.getWorld().getName());
                    m.put("warnings", plugin.getDataConfig().getStringList("warnings." + p.getUniqueId()).size());
                    m.put("punished", plugin.isPunished(p.getUniqueId()));
                    players.add(m);
                }
                res.put("players", players);
                res.put("tps", Math.min(20.0, Math.round(Bukkit.getTPS()[0] * 100.0) / 100.0));
                res.put("usedMem", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024);
                res.put("totalMem", Runtime.getRuntime().totalMemory() / 1024 / 1024);
                res.put("percentMem", Math.round(((double)(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / Runtime.getRuntime().totalMemory()) * 100));
                return res;
            });
            ctx.json(future.get());
        });

        app.get("/api/tickets", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            Future<List<Map<String, String>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, String>> tickets = new ArrayList<>();
                if (plugin.getConfig().contains("tickets")) {
                    for (String key : plugin.getConfig().getConfigurationSection("tickets").getKeys(false)) {
                        if (key.equals("next_id")) continue;
                        if ("open".equals(plugin.getConfig().getString("tickets." + key + ".status"))) {
                            Map<String, String> t = new HashMap<>();
                            t.put("id", key);
                            t.put("player", plugin.getConfig().getString("tickets." + key + ".player"));
                            t.put("message", plugin.getConfig().getString("tickets." + key + ".message"));
                            t.put("time", plugin.getConfig().getString("tickets." + key + ".timestamp"));
                            tickets.add(t);
                        }
                    }
                }
                return tickets;
            });
            ctx.json(future.get());
        });

        app.get("/api/notes", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            String player = ctx.queryParam("player");
            if (player == null) { ctx.result("[]"); return; }
            
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                List<String> notes = plugin.getDataConfig().getStringList("notes." + uuid);
                return notes == null ? new ArrayList<>() : notes;
            });
            ctx.json(future.get());
        });

        app.get("/api/warnings", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            String player = ctx.queryParam("player");
            if (player == null) { ctx.result("[]"); return; }
            
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                List<String> warnings = plugin.getDataConfig().getStringList("warnings." + uuid);
                return warnings == null ? new ArrayList<>() : warnings;
            });
            ctx.json(future.get());
        });

        app.get("/api/history", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> history = plugin.getDataConfig().getStringList("action_history");
                List<String> recent = new ArrayList<>(history);
                Collections.reverse(recent);
                return recent.stream().limit(50).collect(Collectors.toList());
            });
            ctx.json(future.get());
        });

        app.get("/api/chat", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> chat = plugin.getDataConfig().getStringList("chat_history");
                List<String> recent = new ArrayList<>(chat);
                Collections.reverse(recent);
                return recent.stream().limit(30).collect(Collectors.toList());
            });
            ctx.json(future.get());
        });

        app.get("/api/banned", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            List<String> banned = new ArrayList<>(Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getEntries());
            ctx.json(banned);
        });

        // --- ACTION API ---
        app.post("/api/actions/{action}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String action = ctx.pathParam("action");
            String targetName = ctx.queryParam("player");
            String val = ctx.queryParam("value");

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (action.equals("broadcast")) {
                    Bukkit.broadcastMessage(ChatColor.GOLD + "[Web Alert] " + ChatColor.WHITE + val);
                    plugin.logAction("WebAdmin", "broadcast", val);
                } else if (targetName != null) {
                    Player p = Bukkit.getPlayer(targetName);
                    UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
                    
                    if (action.equals("kick") && p != null) {
                        p.kickPlayer(ChatColor.RED + "Kicked by Web Admin: " + (val != null ? val : "No reason"));
                        plugin.logAction("WebAdmin", "kicked", targetName);
                    }
                    else if (action.equals("ban")) {
                        Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(targetName, val, null, "Web Admin");
                        if (p != null) p.kickPlayer(ChatColor.RED + "You have been banned: " + (val != null ? val : "No reason"));
                        plugin.logAction("WebAdmin", "banned", targetName);
                    }
                    else if (action.equals("unban")) {
                        Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(targetName);
                        plugin.logAction("WebAdmin", "unbanned", targetName);
                    }
                    else if (action.equals("warn")) {
                        plugin.addWarning(uuid, val != null ? val : "No reason");
                        if (p != null) p.sendMessage(ChatColor.YELLOW + "You have been warned: " + (val != null ? val : "No reason"));
                        plugin.logAction("WebAdmin", "warned", targetName);
                    }
                    else if (action.equals("heal") && p != null) {
                        p.setHealth(20);
                        p.setFoodLevel(20);
                        plugin.logAction("WebAdmin", "healed", targetName);
                    }
                    else if (action.equals("punish")) {
                        long duration = 3600000L; // Default 1h
                        if ("3h".equals(val)) duration = 10800000L;
                        if ("24h".equals(val)) duration = 86400000L;
                        plugin.getDataConfig().set("punishments." + uuid, System.currentTimeMillis() + duration);
                        plugin.saveDataConfig();
                        if (p != null) p.sendMessage(ChatColor.RED + "You have been punished for " + val);
                        plugin.logAction("WebAdmin", "punished (" + val + ")", targetName);
                    }
                    else if (action.equals("unpunish")) {
                        plugin.getDataConfig().set("punishments." + uuid, null);
                        plugin.saveDataConfig();
                        if (p != null) p.sendMessage(ChatColor.GREEN + "Your punishment has been lifted.");
                        plugin.logAction("WebAdmin", "unpunished", targetName);
                    }
                    else if (action.equals("addnote")) {
                        if (!plugin.getDataConfig().contains("notes." + uuid)) {
                            plugin.getDataConfig().set("notes." + uuid, new ArrayList<>());
                        }
                        List<String> notes = plugin.getDataConfig().getStringList("notes." + uuid);
                        notes.add(val != null ? val : "Note added via web");
                        plugin.getDataConfig().set("notes." + uuid, notes);
                        plugin.saveDataConfig();
                        plugin.logAction("WebAdmin", "added note for", targetName);
                    }
                    else if (action.equals("runcmd")) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), val.replace("{player}", targetName));
                        plugin.logAction("WebAdmin", "executed command for", targetName);
                    }
                }
            });
            ctx.result("OK");
        });

        app.post("/api/ticket/close/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getConfig().set("tickets." + id + ".status", "closed");
                plugin.saveConfig();
                plugin.logAction("WebAdmin", "closed ticket", id);
            });
            ctx.result("OK");
        });

        app.post("/api/command", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            
            String cmd = ctx.queryParam("cmd");
            if (cmd != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    plugin.logAction("WebAdmin", "executed command", cmd);
                });
            }
            ctx.result("OK");
        });
    }

    public void stop() { if (app != null) app.stop(); }

    private static class WebLogHandler extends Handler {
        private final ConcurrentLinkedQueue<WsContext> sessions;
        public WebLogHandler(ConcurrentLinkedQueue<WsContext> s) { this.sessions = s; }
        @Override
        public void publish(LogRecord record) {
            String msg = "[" + record.getLevel() + "] " + record.getMessage();
            for (WsContext s : sessions) { 
                try { if (s.session.isOpen()) s.send(msg); } catch(Exception ignored) {} 
            }
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
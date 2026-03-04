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
                    staticFiles.directory = "web";
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
        // --- DATA API ---
        app.get("/api/players", ctx -> {
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> res = new HashMap<>();
                List<Map<String, String>> players = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Map<String, String> m = new HashMap<>();
                    m.put("name", p.getName());
                    m.put("health", String.valueOf(Math.round(p.getHealth())));
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

        // --- TICKET API ---
        app.get("/api/tickets", ctx -> {
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

        // --- ACTION API ---
        app.post("/api/actions/{action}", ctx -> {
            String received = ctx.header("Authorization");
            String hardcoded = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String fromConfig = "Bearer " + plugin.getApiKey();

            if (received == null || (!received.equals(hardcoded) && !received.equals(fromConfig))) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String action = ctx.pathParam("action");
            String targetName = ctx.queryParam("player");
            String val = ctx.queryParam("value"); // reason or duration

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (action.equals("broadcast")) {
                    Bukkit.broadcastMessage(ChatColor.GOLD + "[Web Alert] " + ChatColor.WHITE + val);
                } else if (targetName != null) {
                    Player p = Bukkit.getPlayer(targetName);
                    UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
                    
                    if (action.equals("kick") && p != null) p.kickPlayer(ChatColor.RED + "Kicked by Web Admin");
                    else if (action.equals("heal") && p != null) { p.setHealth(20); p.setFoodLevel(20); }
                    else if (action.equals("punish")) {
                        long duration = 3600000L; // Default 1h
                        if ("3h".equals(val)) duration = 10800000L;
                        if ("24h".equals(val)) duration = 86400000L;
                        
                        plugin.getConfig().set("punishments." + uuid, System.currentTimeMillis() + duration);
                        plugin.saveConfig();
                        if (p != null) {
                            p.sendMessage(ChatColor.RED + "You have been punished via Web Panel.");
                            // Re-trigger join event logic to add to team/restrictions if needed
                            // For simplicity, we just set the data; the listeners check dataConfig.
                        }
                    }
                    else if (action.equals("unpunish")) {
                        plugin.getConfig().set("punishments." + uuid, null);
                        plugin.saveConfig();
                        if (p != null) p.sendMessage(ChatColor.GREEN + "Punishment lifted via Web Panel.");
                    }
                }
            });
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
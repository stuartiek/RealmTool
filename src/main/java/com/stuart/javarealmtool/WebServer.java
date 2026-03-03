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

    public WebServer(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    public void start() {
        ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
        
        Thread serverThread = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(pluginClassLoader);
            
            app = Javalin.create(config -> {
                config.showJavalinBanner = false;
                
                // Static Files (The Website)
                config.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.directory = "web";
                    staticFiles.location = Location.CLASSPATH;
                });

                // CORS Configuration
                config.bundledPlugins.enableCors(cors -> {
                    cors.addRule(it -> it.anyHost());
                });

                // WebSocket Router for Live Console
                config.router.mount(router -> {
                    router.ws("/api/console", ws -> {
                        ws.onConnect(ctx -> sessions.add(ctx));
                        ws.onClose(ctx -> sessions.remove(ctx));
                    });
                });
            });

            setupRoutes();

            // Attach Logger to capture server logs
            Bukkit.getLogger().addHandler(new WebLogHandler(sessions));

            try {
                app.start(8091);
                plugin.getLogger().info("Web Dashboard live at: http://localhost:8091/");
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to start WebServer: " + e.getMessage());
            }
        });
        
        serverThread.setName("RMT-WebServer-Thread");
        serverThread.start();
    }

    private void setupRoutes() {
        // --- GET PLAYERS & SYSTEM STATS ---
        app.get("/api/players", ctx -> {
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> response = new HashMap<>();
                
                // Players List
                List<Map<String, Object>> players = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", p.getName());
                    m.put("health", Math.round(p.getHealth()));
                    m.put("food", p.getFoodLevel());
                    players.add(m);
                }
                response.put("players", players);

                // TPS & RAM Metrics
                double tps = Bukkit.getTPS()[0]; 
                long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
                long usedMem = totalMem - (Runtime.getRuntime().freeMemory() / 1024 / 1024);

                response.put("tps", Math.min(20.0, Math.round(tps * 100.0) / 100.0));
                response.put("usedMem", usedMem);
                response.put("totalMem", totalMem);
                response.put("percentMem", Math.round(((double)usedMem / totalMem) * 100));

                return response;
            });
            ctx.json(future.get());
        });

        // --- REMOTE ACTIONS ---
        app.post("/api/actions/{action}", ctx -> {
            plugin.getLogger().info("DEBUG: Expected key: 'Bearer " + plugin.getApiKey() + "'");
            plugin.getLogger().info("DEBUG: Received key: '" + ctx.header("Authorization") + "'");

            String key = ctx.header("Authorization");
            if (key == null || !key.equals("Bearer " + plugin.getApiKey())) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String action = ctx.pathParam("action");
            String target = ctx.queryParam("player");
            String reason = ctx.queryParam("reason") != null ? ctx.queryParam("reason") : "Remote Admin";

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = (target != null) ? Bukkit.getPlayer(target) : null;
                if (action.equals("broadcast")) Bukkit.broadcastMessage(ChatColor.GOLD + "[Alert] " + ChatColor.WHITE + reason);
                else if (p != null) {
                    switch (action) {
                        case "kick": p.kickPlayer(ChatColor.RED + "Kicked: " + reason); break;
                        case "warn": p.sendMessage(ChatColor.RED + "WARNING: " + reason); break;
                        case "heal": p.setHealth(20); p.setFoodLevel(20); break;
                    }
                }
            });
            ctx.result("OK");
        });
    }

    public void stop() { if (app != null) app.stop(); }

    // Final Fixed Inner class for Javalin 6 + Paper 1.21.1
    private static class WebLogHandler extends Handler {
        private final ConcurrentLinkedQueue<WsContext> sessions;

        public WebLogHandler(ConcurrentLinkedQueue<WsContext> sessions) {
            this.sessions = sessions;
        }

        @Override
        public void publish(LogRecord record) {
            // Only process if there are active web users
            if (sessions.isEmpty()) return;

            String msg = "[" + record.getLevel() + "] " + record.getMessage();
            
            // Iterate through sessions safely
            for (WsContext session : sessions) {
                try {
                    // In Javalin 6, 'session' (WsContext) has a direct 'session' field 
                    // which is the Jetty Session object. We check if it's open.
                    if (session.session.isOpen()) {
                        session.send(msg); 
                    }
                } catch (Exception e) {
                    // If a session is broken, the next 'onClose' event will remove it
                }
            }
        }

        @Override public void flush() {}
        @Override public void close() throws SecurityException {}
    }
}
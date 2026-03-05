package com.stuart.javarealmtool;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import java.text.SimpleDateFormat;
import java.util.Date;

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
                    m.put("playtime", plugin.getPlaytimeHours(p.getUniqueId()));
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

            String status = ctx.queryParam("status");
            String priority = ctx.queryParam("priority");
            
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> tickets = new ArrayList<>();
                if (plugin.getDataConfig().contains("tickets")) {
                    for (String key : plugin.getDataConfig().getConfigurationSection("tickets").getKeys(false)) {
                        if (key.equals("next_id")) continue;
                        String ticketStatus = plugin.getDataConfig().getString("tickets." + key + ".status", "open");
                        String ticketPriority = plugin.getDataConfig().getString("tickets." + key + ".priority", "medium");
                        
                        if ((status == null || status.equals(ticketStatus)) && 
                            (priority == null || priority.equals(ticketPriority))) {
                            Map<String, Object> t = new HashMap<>();
                            t.put("id", key);
                            t.put("player", plugin.getDataConfig().getString("tickets." + key + ".player"));
                            t.put("message", plugin.getDataConfig().getString("tickets." + key + ".message"));
                            t.put("status", ticketStatus);
                            t.put("priority", ticketPriority);
                            t.put("category", plugin.getDataConfig().getString("tickets." + key + ".category", "other"));
                            t.put("assignee", plugin.getDataConfig().getString("tickets." + key + ".assignee", ""));
                            t.put("time", plugin.getDataConfig().getString("tickets." + key + ".timestamp"));
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
            
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                List<String> notesList = plugin.getDataConfig().getStringList("notes." + uuid);
                List<Map<String, Object>> result = new ArrayList<>();
                if (notesList != null) {
                    for (int i = 0; i < notesList.size(); i++) {
                        Map<String, Object> note = new HashMap<>();
                        note.put("index", i);
                        note.put("text", notesList.get(i));
                        result.add(note);
                    }
                }
                return result;
            });
            ctx.json(future.get());
        });

        app.patch("/api/note/{player}/{index}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            
            String player = ctx.pathParam("player");
            int index = Integer.parseInt(ctx.pathParam("index"));
            String newText = ctx.queryParam("text");
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                List<String> notes = plugin.getDataConfig().getStringList("notes." + uuid);
                if (notes != null && index >= 0 && index < notes.size()) {
                    notes.set(index, newText);
                    plugin.getDataConfig().set("notes." + uuid, notes);
                    plugin.saveDataFile();
                    plugin.logAction("WebAdmin", "edited note for", player);
                }
            });
            ctx.result("OK");
        });

        app.delete("/api/note/{player}/{index}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            
            String player = ctx.pathParam("player");
            int index = Integer.parseInt(ctx.pathParam("index"));
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                List<String> notes = plugin.getDataConfig().getStringList("notes." + uuid);
                if (notes != null && index >= 0 && index < notes.size()) {
                    notes.remove(index);
                    plugin.getDataConfig().set("notes." + uuid, notes);
                    plugin.saveDataFile();
                    plugin.logAction("WebAdmin", "deleted note for", player);
                }
            });
            ctx.result("OK");
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
            
            Future<List<Map<String, String>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> chat = plugin.getDataConfig().getStringList("chat_history");
                List<Map<String, String>> messages = new ArrayList<>();
                List<String> recent = new ArrayList<>(chat);
                Collections.reverse(recent);
                recent.stream().limit(30).forEach(msg -> {
                    Map<String, String> msgMap = new HashMap<>();
                    int spaceIdx = msg.indexOf(' ');
                    if (spaceIdx > 0) {
                        String timestamp = msg.substring(0, spaceIdx).trim();
                        String rest = msg.substring(spaceIdx + 1).trim();
                        int colonIdx = rest.indexOf(':');
                        if (colonIdx > 0) {
                            String player = rest.substring(0, colonIdx).trim();
                            String message = rest.substring(colonIdx + 1).trim();
                            msgMap.put("timestamp", timestamp);
                            msgMap.put("player", player);
                            msgMap.put("message", message);
                            messages.add(msgMap);
                        }
                    }
                });
                return messages;
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
                String targetNameParam = ctx.queryParam("player");
            String reasonParam = ctx.queryParam("reason");
            
            // Try to get params from JSON body if not in query params
                if (targetNameParam == null || reasonParam == null) {
                try {
                    String body = ctx.body();
                    if (body != null && !body.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                            if (targetNameParam == null) targetNameParam = (String) bodyMap.get("player");
                        if (reasonParam == null) reasonParam = (String) bodyMap.get("reason");
                    }
                } catch (Exception e) {
                    // Continue with null params
                }
            }
            
            if (reasonParam == null) reasonParam = ctx.queryParam("value");
            if (reasonParam == null) reasonParam = ctx.queryParam("message");
            
                final String targetName = targetNameParam;
            final String val = reasonParam;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (action.equals("broadcast")) {
                    Bukkit.broadcastMessage(ChatColor.GOLD + "[Web Alert] " + ChatColor.WHITE + val);
                    plugin.logAction("WebAdmin", "broadcast", val);
                } else if (targetName != null) {
                    Player p = Bukkit.getPlayer(targetName);
                    UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
                    
                    if (action.equals("kick") && p != null) {
                        String kickReason = val != null ? val : "No reason";
                        p.kickPlayer(ChatColor.RED + "Kicked by Web Admin: " + kickReason);
                        plugin.addChatLog("System", "[KICK] " + targetName + ": " + kickReason);
                        plugin.logAction("WebAdmin", "kicked", targetName);
                    }
                    else if (action.equals("ban")) {
                        String banReason = val != null ? val : "No reason";
                        Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(targetName, banReason, null, "Web Admin");
                        if (p != null) p.kickPlayer(ChatColor.RED + "You have been banned: " + banReason);
                        plugin.addChatLog("System", "[BAN] " + targetName + ": " + banReason);
                        plugin.logAction("WebAdmin", "banned", targetName);
                    }
                    else if (action.equals("unban")) {
                        Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(targetName);
                        plugin.logAction("WebAdmin", "unbanned", targetName);
                    }
                    else if (action.equals("warn")) {
                        String warnReason = val != null ? val : "No reason";
                        plugin.addWarning(uuid, warnReason);
                        if (p != null) p.sendMessage(ChatColor.YELLOW + "You have been warned: " + warnReason);
                        plugin.addChatLog("System", "[WARNING] " + targetName + ": " + warnReason);
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
                        plugin.saveDataFile();
                        if (p != null) p.sendMessage(ChatColor.RED + "You have been punished for " + val);
                        plugin.logAction("WebAdmin", "punished (" + val + ")", targetName);
                    }
                    else if (action.equals("unpunish")) {
                        plugin.getDataConfig().set("punishments." + uuid, null);
                        plugin.saveDataFile();
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
                        plugin.saveDataFile();
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
                plugin.getDataConfig().set("tickets." + id + ".status", "closed");
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", "closed ticket", id);
            });
            ctx.result("OK");
        });

        app.get("/api/ticket/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String id = ctx.pathParam("id");
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () ->
                plugin.getTicketData(Integer.parseInt(id))
            );
            ctx.json(future.get());
        });

        app.post("/api/ticket/{id}/response", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String id = ctx.pathParam("id");
            String admin = ctx.queryParam("admin");
            String message = ctx.queryParam("message");
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.addTicketResponse(Integer.parseInt(id), admin, message);
                plugin.logAction("WebAdmin", "added response to ticket", id);
            });
            ctx.result("OK");
        });

        app.patch("/api/ticket/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String id = ctx.pathParam("id");
            String priority = ctx.queryParam("priority");
            String category = ctx.queryParam("category");
            String status = ctx.queryParam("status");
            String assignee = ctx.queryParam("assignee");

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (priority != null) plugin.updateTicketField(Integer.parseInt(id), "priority", priority);
                if (category != null) plugin.updateTicketField(Integer.parseInt(id), "category", category);
                if (status != null) plugin.updateTicketField(Integer.parseInt(id), "status", status);
                if (assignee != null) plugin.updateTicketField(Integer.parseInt(id), "assignee", assignee);
                plugin.logAction("WebAdmin", "updated ticket", id);
            });
            ctx.result("OK");
        });

        app.post("/api/ticket/{id}/resolve", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String id = ctx.pathParam("id");
            String reason = ctx.queryParam("reason");

            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.resolveTicket(Integer.parseInt(id), reason);
                plugin.logAction("WebAdmin", "resolved ticket", id);
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

        // --- WHITELIST ---
        app.get("/api/whitelist", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            ctx.json(new ArrayList<>(Bukkit.getWhitelistedPlayers().stream().map(p -> p.getName()).toList()));
        });

        app.post("/api/whitelist/{action}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String action = ctx.pathParam("action");
            String player = ctx.queryParam("player");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if ("add".equals(action)) {
                    Bukkit.getOfflinePlayer(player).setWhitelisted(true);
                    plugin.logAction("WebAdmin", "whitelisted", player);
                } else if ("remove".equals(action)) {
                    Bukkit.getOfflinePlayer(player).setWhitelisted(false);
                    plugin.logAction("WebAdmin", "unwhitelisted", player);
                }
            });
            ctx.result("OK");
        });

        // --- MUTE ---
        app.get("/api/muted", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> plugin.getDataConfig().getStringList("muted"));
            ctx.json(future.get());
        });

        app.post("/api/mute", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            
                String playerParam = ctx.queryParam("player");
                String reasonParam = ctx.queryParam("reason");
            
            // Try to get params from JSON body if not in query params
                if (playerParam == null || reasonParam == null) {
                try {
                    String body = ctx.body();
                    if (body != null && !body.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                            if (playerParam == null) playerParam = (String) bodyMap.get("player");
                            if (reasonParam == null) reasonParam = (String) bodyMap.get("reason");
                    }
                } catch (Exception e) {
                    // Continue with null params
                }
            }
            
                final String targetPlayer = playerParam;
                final String finalReason = reasonParam;
            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(targetPlayer).getUniqueId();
                plugin.mutePlayer(uuid, finalReason != null ? finalReason : "No reason");
                plugin.logAction("WebAdmin", "muted", targetPlayer);
            });
            ctx.result("OK");
        });

        app.post("/api/unmute", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            
                String playerParam = ctx.queryParam("player");
            
            // Try to get player from JSON body if not in query params
                if (playerParam == null) {
                try {
                    String body = ctx.body();
                    if (body != null && !body.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                            playerParam = (String) bodyMap.get("player");
                    }
                } catch (Exception e) {
                    // Continue with null params
                }
            }
            
            final String targetPlayer = playerParam;
            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(targetPlayer).getUniqueId();
                plugin.unmutePlayer(uuid);
                plugin.logAction("WebAdmin", "unmuted", targetPlayer);
            });
            ctx.result("OK");
        });

        // --- IPs & SESSIONS ---
        app.get("/api/ips", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String player = ctx.queryParam("player");
            if (player == null) { ctx.result("[]"); return; }
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                return plugin.getDataConfig().getStringList("ips." + uuid);
            });
            ctx.json(future.get());
        });

        app.get("/api/sessions", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String player = ctx.queryParam("player");
            if (player == null) { ctx.result("[]"); return; }
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                return plugin.getDataConfig().getStringList("sessions." + uuid);
            });
            ctx.json(future.get());
        });

        // --- TEMPLATES ---
        app.get("/api/templates", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> new HashMap<>(plugin.getDataConfig().getConfigurationSection("templates").getValues(false)));
            try { ctx.json(future.get()); } catch (Exception e) { ctx.json(new HashMap<>()); }
        });

        app.post("/api/template/save", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String name = ctx.queryParam("name");
            String content = ctx.queryParam("content");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.saveTemplate(name, content);
                plugin.logAction("WebAdmin", "saved template", name);
            });
            ctx.result("OK");
        });

        // --- TELEPORT ---
        app.post("/api/teleport", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String player1 = ctx.queryParam("player1");
            String player2 = ctx.queryParam("player2");
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p1 = Bukkit.getPlayer(player1);
                Player p2 = Bukkit.getPlayer(player2);
                if (p1 != null && p2 != null) {
                    p1.teleport(p2);
                    plugin.logAction("WebAdmin", "teleported " + player1 + " to", player2);
                }
            });
            ctx.result("OK");
        });

        // --- INVENTORY GIVE ---
        app.post("/api/give", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String player = ctx.queryParam("player");
            String item = ctx.queryParam("item");
            String amount = ctx.queryParam("amount");
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(player);
                if (p != null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + player + " " + item + " " + (amount != null ? amount : "1"));
                    plugin.logAction("WebAdmin", "gave " + item + " to", player);
                }
            });
            ctx.result("OK");
        });

        // --- RESTART ---
        app.post("/api/restart", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String delayStr = ctx.queryParam("delay");
            long delay = delayStr != null ? Long.parseLong(delayStr) : 5;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.scheduleRestart(delay);
                final long d = delay;
                Bukkit.broadcastMessage(ChatColor.RED + "Server restarting in " + d + " minutes!");
                plugin.logAction("WebAdmin", "scheduled restart", delay + " mins");
            });
            ctx.result("OK");
        });

        // --- BACKUP ---
        app.post("/api/backup", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                plugin.logAction("WebAdmin", "triggered backup", "");
            });
            ctx.result("OK");
        });

        // --- WORLDS ---
        app.get("/api/worlds", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            List<Map<String, Object>> worlds = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", w.getName());
                m.put("players", w.getPlayers().size());
                m.put("environment", w.getEnvironment().toString());
                m.put("difficulty", w.getDifficulty().toString());
                worlds.add(m);
            }
            ctx.json(worlds);
        });

        // --- PLUGINS ---
        app.get("/api/plugins", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            List<String> plugins = new ArrayList<>();
            for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
                plugins.add(p.getName() + " v" + p.getDescription().getVersion());
            }
            ctx.json(plugins);
        });

        // --- GAMERULES ---
        app.get("/api/gamerules", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            World world = Bukkit.getWorlds().get(0);
            Map<String, Object> rules = new HashMap<>();
            rules.put("pvp", world.getPVP());
            rules.put("difficulty", world.getDifficulty().toString());
            ctx.json(rules);
        });

        app.post("/api/gamerule", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String rule = ctx.queryParam("rule");
            String value = ctx.queryParam("value");
            Bukkit.getScheduler().runTask(plugin, () -> {
                World world = Bukkit.getWorlds().get(0);
                if ("pvp".equals(rule)) world.setPVP(Boolean.parseBoolean(value));
                plugin.logAction("WebAdmin", "set gamerule " + rule, value);
            });
            ctx.result("OK");
        });

        // --- BULK ACTIONS ---
        app.post("/api/bulk", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String action = ctx.queryParam("action");
            String players = ctx.queryParam("players");
            String reason = ctx.queryParam("reason");
            if (players != null && action != null) {
                String[] playerList = players.split(",");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String p : playerList) {
                        UUID uuid = Bukkit.getOfflinePlayer(p.trim()).getUniqueId();
                        if ("ban".equals(action)) {
                            Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(p.trim(), reason, null, "WebAdmin");
                            Player pl = Bukkit.getPlayer(p.trim());
                            if (pl != null) pl.kickPlayer(ChatColor.RED + "Banned: " + (reason != null ? reason : ""));
                        } else if ("kick".equals(action)) {
                            Player pl = Bukkit.getPlayer(p.trim());
                            if (pl != null) pl.kickPlayer(ChatColor.RED + (reason != null ? reason : "Kicked"));
                        }
                        plugin.logAction("WebAdmin", "bulk " + action, p.trim());
                    }
                });
            }
            ctx.result("OK");
        });

        // --- REPORTS ---
        app.post("/api/report", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String reporter = ctx.queryParam("reporter");
            String reported = ctx.queryParam("reported");
            String reason = ctx.queryParam("reason");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.getDataConfig().contains("reports")) plugin.getDataConfig().set("reports", new ArrayList<>());
                List<String> reports = plugin.getDataConfig().getStringList("reports");
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                reports.add(ts + " | " + reporter + " reported " + reported + " for: " + reason);
                plugin.getDataConfig().set("reports", reports);
                plugin.saveDataFile();
            });
            ctx.result("OK");
        });

        app.get("/api/reports", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> reports = plugin.getDataConfig().getStringList("reports");
                List<String> recent = new ArrayList<>(reports);
                Collections.reverse(recent);
                return recent.stream().limit(50).collect(Collectors.toList());
            });
            ctx.json(future.get());
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
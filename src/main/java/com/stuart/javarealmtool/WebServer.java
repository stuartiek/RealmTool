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
                        plugin.fireDiscordEvent("bans", "Player Banned", "**" + targetName + "** was banned.\nReason: " + banReason, 0xe74c3c, targetName);
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
                        plugin.fireDiscordEvent("warns", "Player Warned", "**" + targetName + "** was warned.\nReason: " + warnReason, 0xf1c40f, targetName);
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
            Future<List<Map<String, String>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> raw = plugin.getDataConfig().getStringList("muted");
                List<Map<String, String>> result = new ArrayList<>();
                for (String entry : raw) {
                    Map<String, String> m = new HashMap<>();
                    String[] parts = entry.split("\\|", 3);
                    if (parts.length >= 3) {
                        m.put("uuid", parts[0]);
                        m.put("name", parts[1]);
                        m.put("reason", parts[2]);
                    } else if (parts.length == 1) {
                        // Legacy UUID-only entry
                        String name = Bukkit.getOfflinePlayer(UUID.fromString(parts[0])).getName();
                        m.put("uuid", parts[0]);
                        m.put("name", name != null ? name : parts[0]);
                        m.put("reason", "No reason");
                    }
                    result.add(m);
                }
                return result;
            });
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
                plugin.mutePlayer(uuid, targetPlayer, finalReason != null ? finalReason : "No reason");
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
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (plugin.getDataConfig().contains("templates") && plugin.getDataConfig().getConfigurationSection("templates") != null) {
                    return new HashMap<>(plugin.getDataConfig().getConfigurationSection("templates").getValues(false));
                }
                return new HashMap<String, Object>();
            });
            ctx.json(future.get());
        });

        app.post("/api/template/save", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String name = null;
            String content = null;
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    name = (String) bodyMap.get("name");
                    content = (String) bodyMap.get("content");
                }
            } catch (Exception e) { /* ignore */ }
            if (name == null) name = ctx.queryParam("name");
            if (content == null) content = ctx.queryParam("content");
            if (name == null || content == null) { ctx.status(400).result("Missing name or content"); return; }
            final String fName = name;
            final String fContent = content;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.saveTemplate(fName, fContent);
                plugin.logAction("WebAdmin", "saved template", fName);
            });
            ctx.result("OK");
        });

        app.post("/api/template/delete", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String name = null;
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    name = (String) bodyMap.get("name");
                }
            } catch (Exception e) { /* ignore */ }
            if (name == null) name = ctx.queryParam("name");
            if (name == null) { ctx.status(400).result("Missing name"); return; }
            final String fName = name;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("templates." + fName, null);
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", "deleted template", fName);
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

        // --- DISCORD INTEGRATION ---
        app.get("/api/discord", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> res = new HashMap<>();
                res.put("webhook", plugin.getDataConfig().getString("discord.webhook", ""));
                res.put("webhook_ban", plugin.getDataConfig().getString("discord.webhook_ban", ""));
                res.put("webhook_warn", plugin.getDataConfig().getString("discord.webhook_warn", ""));
                res.put("webhook_report", plugin.getDataConfig().getString("discord.webhook_report", ""));
                res.put("bans", plugin.getDataConfig().getBoolean("discord.bans", true));
                res.put("warns", plugin.getDataConfig().getBoolean("discord.warns", true));
                res.put("reports", plugin.getDataConfig().getBoolean("discord.reports", true));
                res.put("joins", plugin.getDataConfig().getBoolean("discord.joins", true));
                res.put("leaves", plugin.getDataConfig().getBoolean("discord.leaves", true));
                res.put("deaths", plugin.getDataConfig().getBoolean("discord.deaths", false));
                res.put("block_logging", plugin.getDataConfig().getBoolean("discord.block_logging", false));
                res.put("container_logging", plugin.getDataConfig().getBoolean("discord.container_logging", false));
                res.put("command_logging", plugin.getDataConfig().getBoolean("discord.command_logging", false));
                res.put("milestone_alerts", plugin.getDataConfig().getBoolean("discord.milestone_alerts", false));
                res.put("performance_alerts", plugin.getDataConfig().getBoolean("discord.performance_alerts", false));
                res.put("health_check", plugin.getDataConfig().getBoolean("discord.health_check", false));
                res.put("daily_summary", plugin.getDataConfig().getBoolean("discord.daily_summary", false));
                res.put("webhooks_sent", plugin.getDataConfig().getInt("discord.webhooks_sent", 0));
                res.put("webhooks_failed", plugin.getDataConfig().getInt("discord.webhooks_failed", 0));
                return res;
            });
            ctx.json(future.get());
        });

        app.post("/api/discord", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }

            // Parse from JSON body
            String webhook = null;
            java.util.Map<String, Object> bodyMap = null;
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    bodyMap = mapper.readValue(body, java.util.Map.class);
                    webhook = (String) bodyMap.get("webhook");
                }
            } catch (Exception e) { /* ignore */ }

            // Fallback to query params
            if (webhook == null) webhook = ctx.queryParam("webhook");

            final String fWebhook = webhook;
            final java.util.Map<String, Object> fBody = bodyMap;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (fWebhook != null) plugin.getDataConfig().set("discord.webhook", fWebhook);

                // Helper to get boolean from body map or query param
                String[] boolKeys = {"bans", "warns", "reports", "joins", "leaves", "deaths",
                    "block_logging", "container_logging", "command_logging",
                    "milestone_alerts", "performance_alerts", "health_check", "daily_summary"};
                String[] webhookKeys = {"webhook_ban", "webhook_warn", "webhook_report"};

                for (String key : webhookKeys) {
                    String val = null;
                    if (fBody != null && fBody.containsKey(key)) val = (String) fBody.get(key);
                    if (val == null) val = ctx.queryParam(key);
                    if (val != null) plugin.getDataConfig().set("discord." + key, val);
                }

                for (String key : boolKeys) {
                    Object val = null;
                    if (fBody != null && fBody.containsKey(key)) val = fBody.get(key);
                    if (val != null) {
                        boolean b = val instanceof Boolean ? (Boolean) val : "true".equalsIgnoreCase(val.toString());
                        plugin.getDataConfig().set("discord." + key, b);
                    } else {
                        String qp = ctx.queryParam(key);
                        if (qp != null) plugin.getDataConfig().set("discord." + key, "true".equalsIgnoreCase(qp));
                    }
                }

                plugin.saveDataFile();
                plugin.logAction("WebAdmin", "updated", "discord settings");
            });
            ctx.result("OK");
        });

        app.post("/api/discord/test", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }

            String webhook = null;
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    webhook = (String) bodyMap.get("webhook");
                }
            } catch (Exception e) { /* ignore */ }
            if (webhook == null) webhook = ctx.queryParam("webhook");
            if (webhook == null || webhook.isEmpty()) { ctx.json(Map.of("success", false, "error", "No webhook URL")); return; }

            boolean success = plugin.sendDiscordWebhook(webhook, "Drowsy Management Tool", "✅ **Webhook test successful!**\nYour Discord integration is working.", 0x4ec9b0);
            ctx.json(Map.of("success", success, "error", success ? "" : "Failed to connect to webhook"));
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
                plugin.fireDiscordEvent("reports", "New Report", "**" + reporter + "** reported **" + reported + "**\nReason: " + reason, 0xe67e22, reported);
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

        // --- KITS ---
        app.get("/api/kits", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }

            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> kits = new ArrayList<>();
                if (plugin.getDataConfig().contains("kits")) {
                    for (String kitName : plugin.getDataConfig().getConfigurationSection("kits").getKeys(false)) {
                        String path = "kits." + kitName;
                        Map<String, Object> kit = new HashMap<>();
                        kit.put("name", kitName);
                        kit.put("icon", plugin.getDataConfig().getString(path + ".icon", "CHEST"));
                        kit.put("cost", plugin.getDataConfig().getInt(path + ".cost", 0));
                        kit.put("cooldown", plugin.getDataConfig().getInt(path + ".cooldown", 0));
                        kit.put("permission", plugin.getDataConfig().getString(path + ".permission", ""));
                        kit.put("description", plugin.getDataConfig().getString(path + ".description", ""));
                        kit.put("items", plugin.getDataConfig().getStringList(path + ".items"));
                        kits.add(kit);
                    }
                }
                return kits;
            });
            ctx.json(Map.of("kits", future.get()));
        });

        app.post("/api/kits", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> body = mapper.readValue(ctx.body(), java.util.Map.class);

            String name = (String) body.get("name");
            if (name == null || name.trim().isEmpty()) { ctx.status(400).result("Kit name required"); return; }
            name = name.trim();

            final String kitName = name;
            final String icon = body.get("icon") != null ? ((String) body.get("icon")).toUpperCase() : "CHEST";
            final int cost = body.get("cost") != null ? ((Number) body.get("cost")).intValue() : 0;
            final int cooldown = body.get("cooldown") != null ? ((Number) body.get("cooldown")).intValue() : 0;
            final String permission = body.get("permission") != null ? (String) body.get("permission") : "";
            final String description = body.get("description") != null ? (String) body.get("description") : "";
            final List<String> items = body.get("items") != null ? (List<String>) body.get("items") : new ArrayList<>();

            Bukkit.getScheduler().runTask(plugin, () -> {
                String path = "kits." + kitName;
                plugin.getDataConfig().set(path + ".icon", icon);
                plugin.getDataConfig().set(path + ".cost", cost);
                plugin.getDataConfig().set(path + ".cooldown", cooldown);
                plugin.getDataConfig().set(path + ".permission", permission);
                plugin.getDataConfig().set(path + ".description", description);
                plugin.getDataConfig().set(path + ".items", items);
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", "created/updated kit", kitName);
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/kits/{name}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }

            String kitName = ctx.pathParam("name");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("kits." + kitName, null);
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", "deleted kit", kitName);
            });
            ctx.json(Map.of("success", true));
        });

        // --- MAINTENANCE MODE ---
        app.get("/api/maintenance", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> res = new HashMap<>();
                res.put("enabled", plugin.getDataConfig().getBoolean("maintenance.enabled", false));
                res.put("message", plugin.getDataConfig().getString("maintenance.message", "Server is under maintenance..."));
                res.put("endTime", plugin.getDataConfig().getString("maintenance.endTime", ""));
                res.put("whitelist", plugin.getDataConfig().getStringList("maintenance.whitelist"));
                return res;
            });
            ctx.json(future.get());
        });

        app.post("/api/maintenance/set", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }

            String status = null;
            String message = null;
            String endTime = null;

            // Parse JSON body first
            try {
                String body = ctx.body();
                Bukkit.getLogger().info("[Maintenance] Received body: " + body);
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    status = (String) bodyMap.get("status");
                    message = (String) bodyMap.get("message");
                    endTime = (String) bodyMap.get("endTime");
                }
            } catch (Exception e) {
                Bukkit.getLogger().warning("[Maintenance] Failed to parse body: " + e.getMessage());
            }

            // Fallback to query params
            if (status == null) status = ctx.queryParam("status");
            if (message == null) message = ctx.queryParam("message");
            if (endTime == null) endTime = ctx.queryParam("endTime");

            Bukkit.getLogger().info("[Maintenance] status=" + status + " message=" + message + " endTime=" + endTime);

            if (status == null) {
                ctx.status(400).result("Missing status parameter");
                return;
            }

            final boolean enabled = "on".equalsIgnoreCase(status);
            final String fMessage = (message != null && !message.isEmpty()) ? message : "Server is under maintenance...";
            final String fEndTime = endTime;

            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("maintenance.enabled", enabled);
                plugin.getDataConfig().set("maintenance.message", fMessage);
                if (fEndTime != null) plugin.getDataConfig().set("maintenance.endTime", fEndTime);
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", enabled ? "enabled" : "disabled", "maintenance mode");
                Bukkit.getLogger().info("[Maintenance] Mode set to " + (enabled ? "ON" : "OFF"));

                if (enabled) {
                    Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "[Maintenance] " + ChatColor.RESET + ChatColor.RED + fMessage);
                    List<String> whitelist = plugin.getDataConfig().getStringList("maintenance.whitelist");
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (!whitelist.contains(p.getName())) {
                            p.kickPlayer(ChatColor.RED + fMessage);
                        }
                    }
                } else {
                    Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "[Maintenance] " + ChatColor.RESET + ChatColor.GREEN + "Maintenance mode has been disabled.");
                }
            });
            ctx.result("OK");
        });

        app.post("/api/maintenance/whitelist/add", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }

            String player = null;
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    player = (String) bodyMap.get("player");
                }
            } catch (Exception e) { /* ignore */ }
            if (player == null) player = ctx.queryParam("player");

            final String fPlayer = player;
            if (fPlayer == null || fPlayer.isEmpty()) { ctx.status(400).result("Missing player"); return; }

            Bukkit.getScheduler().runTask(plugin, () -> {
                List<String> whitelist = new ArrayList<>(plugin.getDataConfig().getStringList("maintenance.whitelist"));
                if (!whitelist.contains(fPlayer)) {
                    whitelist.add(fPlayer);
                    plugin.getDataConfig().set("maintenance.whitelist", whitelist);
                    plugin.saveDataFile();
                    plugin.logAction("WebAdmin", "added to maintenance whitelist", fPlayer);
                }
            });
            ctx.result("OK");
        });

        app.delete("/api/maintenance/whitelist/{player}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }

            String player = ctx.pathParam("player");
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<String> whitelist = new ArrayList<>(plugin.getDataConfig().getStringList("maintenance.whitelist"));
                whitelist.remove(player);
                plugin.getDataConfig().set("maintenance.whitelist", whitelist);
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", "removed from maintenance whitelist", player);
            });
            ctx.result("OK");
        });

        // ========== CRATES API ==========
        app.get("/api/crates", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Map<String, Object> result = new HashMap<>();
            if (plugin.getDataConfig().contains("crates")) {
                for (String crateId : plugin.getDataConfig().getConfigurationSection("crates").getKeys(false)) {
                    Map<String, Object> crate = new HashMap<>();
                    String p = "crates." + crateId;
                    crate.put("icon", plugin.getDataConfig().getString(p + ".icon", "CHEST"));
                    crate.put("description", plugin.getDataConfig().getString(p + ".description", ""));
                    crate.put("key_cost", plugin.getDataConfig().getInt(p + ".key_cost", 0));
                    crate.put("rewards", plugin.getDataConfig().getStringList(p + ".rewards"));
                    result.put(crateId, crate);
                }
            }
            ctx.json(result);
        });

        app.post("/api/crates", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            String name = (String) body.get("name");
            if (name == null || name.isEmpty()) { ctx.status(400).json(Map.of("error", "Missing name")); return; }
            String p = "crates." + name;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set(p + ".icon", body.getOrDefault("icon", "CHEST"));
                plugin.getDataConfig().set(p + ".description", body.getOrDefault("description", ""));
                plugin.getDataConfig().set(p + ".key_cost", body.getOrDefault("key_cost", 0));
                plugin.getDataConfig().set(p + ".rewards", body.getOrDefault("rewards", new ArrayList<>()));
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/crates/{name}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String name = ctx.pathParam("name");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("crates." + name, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== BOUNTIES API ==========
        app.get("/api/bounties", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            List<Map<String, Object>> list = new ArrayList<>();
            if (plugin.getDataConfig().contains("bounties")) {
                for (String id : plugin.getDataConfig().getConfigurationSection("bounties").getKeys(false)) {
                    Map<String, Object> b = new HashMap<>();
                    String bp = "bounties." + id;
                    b.put("id", id);
                    b.put("targetName", plugin.getDataConfig().getString(bp + ".targetName", ""));
                    b.put("setterName", plugin.getDataConfig().getString(bp + ".setterName", ""));
                    b.put("amount", plugin.getDataConfig().getInt(bp + ".amount", 0));
                    list.add(b);
                }
            }
            ctx.json(list);
        });

        app.delete("/api/bounties/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("bounties." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== SHOPS API ==========
        app.get("/api/shops", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            List<Map<String, Object>> list = new ArrayList<>();
            if (plugin.getDataConfig().contains("shops")) {
                for (String id : plugin.getDataConfig().getConfigurationSection("shops").getKeys(false)) {
                    Map<String, Object> s = new HashMap<>();
                    String sp = "shops." + id;
                    s.put("id", id);
                    s.put("ownerName", plugin.getDataConfig().getString(sp + ".ownerName", ""));
                    s.put("item", plugin.getDataConfig().getString(sp + ".item", ""));
                    s.put("amount", plugin.getDataConfig().getInt(sp + ".amount", 1));
                    s.put("price", plugin.getDataConfig().getInt(sp + ".price", 0));
                    list.add(s);
                }
            }
            ctx.json(list);
        });

        app.delete("/api/shops/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("shops." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== QUESTS API ==========
        app.get("/api/quests", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Map<String, Object> result = new HashMap<>();
            if (plugin.getDataConfig().contains("quests")) {
                for (String qid : plugin.getDataConfig().getConfigurationSection("quests").getKeys(false)) {
                    Map<String, Object> q = new HashMap<>();
                    String qp = "quests." + qid;
                    q.put("name", plugin.getDataConfig().getString(qp + ".name", qid));
                    q.put("description", plugin.getDataConfig().getString(qp + ".description", ""));
                    q.put("type", plugin.getDataConfig().getString(qp + ".type", "break_blocks"));
                    q.put("goal", plugin.getDataConfig().getInt(qp + ".goal", 1));
                    q.put("reward", plugin.getDataConfig().getInt(qp + ".reward", 0));
                    q.put("reward_kit", plugin.getDataConfig().getString(qp + ".reward_kit", ""));
                    q.put("active", plugin.getDataConfig().getBoolean(qp + ".active", true));
                    result.put(qid, q);
                }
            }
            ctx.json(result);
        });

        app.post("/api/quests", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            String id = (String) body.get("id");
            if (id == null || id.isEmpty()) id = String.valueOf(System.currentTimeMillis());
            String qp = "quests." + id;
            final String fId = id;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set(qp + ".name", body.getOrDefault("name", fId));
                plugin.getDataConfig().set(qp + ".description", body.getOrDefault("description", ""));
                plugin.getDataConfig().set(qp + ".type", body.getOrDefault("type", "break_blocks"));
                plugin.getDataConfig().set(qp + ".goal", body.getOrDefault("goal", 1));
                plugin.getDataConfig().set(qp + ".reward", body.getOrDefault("reward", 0));
                plugin.getDataConfig().set(qp + ".reward_kit", body.getOrDefault("reward_kit", ""));
                plugin.getDataConfig().set(qp + ".active", body.getOrDefault("active", true));
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/quests/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("quests." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== STAFF APPLICATIONS API ==========
        app.get("/api/applications", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            List<Map<String, Object>> list = new ArrayList<>();
            if (plugin.getDataConfig().contains("applications")) {
                for (String id : plugin.getDataConfig().getConfigurationSection("applications").getKeys(false)) {
                    Map<String, Object> a = new HashMap<>();
                    String ap = "applications." + id;
                    a.put("id", id);
                    a.put("player", plugin.getDataConfig().getString(ap + ".player", ""));
                    a.put("message", plugin.getDataConfig().getString(ap + ".message", ""));
                    a.put("date", plugin.getDataConfig().getString(ap + ".date", ""));
                    a.put("status", plugin.getDataConfig().getString(ap + ".status", "pending"));
                    list.add(a);
                }
            }
            ctx.json(list);
        });

        app.post("/api/applications/{id}/status", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String id = ctx.pathParam("id");
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            String status = (String) body.get("status");
            if (status == null) { ctx.status(400).json(Map.of("error", "Missing status")); return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("applications." + id + ".status", status);
                plugin.saveDataFile();
                String playerName = plugin.getDataConfig().getString("applications." + id + ".player", "");
                Player target = Bukkit.getPlayer(playerName);
                if (target != null) {
                    target.sendMessage(ChatColor.GOLD + "Your staff application has been " + (status.equals("approved") ? ChatColor.GREEN + "APPROVED" : ChatColor.RED + "DENIED") + ChatColor.GOLD + "!");
                }
                plugin.logAction("WebAdmin", "application_" + status, playerName);
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/applications/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("applications." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== POLLS API ==========
        app.get("/api/polls", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Map<String, Object> result = new HashMap<>();
            if (plugin.getDataConfig().contains("polls")) {
                for (String pid : plugin.getDataConfig().getConfigurationSection("polls").getKeys(false)) {
                    Map<String, Object> poll = new HashMap<>();
                    String pp = "polls." + pid;
                    poll.put("question", plugin.getDataConfig().getString(pp + ".question", ""));
                    poll.put("options", plugin.getDataConfig().getStringList(pp + ".options"));
                    poll.put("active", plugin.getDataConfig().getBoolean(pp + ".active", false));
                    Map<String, Integer> votes = new HashMap<>();
                    if (plugin.getDataConfig().contains(pp + ".votes")) {
                        for (String vk : plugin.getDataConfig().getConfigurationSection(pp + ".votes").getKeys(false)) {
                            votes.put(vk, plugin.getDataConfig().getInt(pp + ".votes." + vk, 0));
                        }
                    }
                    poll.put("votes", votes);
                    result.put(pid, poll);
                }
            }
            ctx.json(result);
        });

        app.post("/api/polls", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            String id = (String) body.get("id");
            if (id == null || id.isEmpty()) id = String.valueOf(System.currentTimeMillis());
            String pp = "polls." + id;
            final String fId = id;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set(pp + ".question", body.getOrDefault("question", ""));
                plugin.getDataConfig().set(pp + ".options", body.getOrDefault("options", new ArrayList<>()));
                plugin.getDataConfig().set(pp + ".active", body.getOrDefault("active", true));
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/polls/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("polls." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== AUTO-MODERATION API ==========
        app.get("/api/automod", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Map<String, Object> res = new HashMap<>();
            res.put("filter_words", plugin.getDataConfig().getStringList("automod.filter_words"));
            res.put("spam_cooldown", plugin.getDataConfig().getInt("automod.spam_cooldown", 2));
            res.put("caps_threshold", plugin.getDataConfig().getInt("automod.caps_threshold", 70));
            res.put("violation_mute_threshold", plugin.getDataConfig().getInt("automod.violation_mute_threshold", 3));
            ctx.json(res);
        });

        app.post("/api/automod", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (body.containsKey("filter_words")) plugin.getDataConfig().set("automod.filter_words", body.get("filter_words"));
                if (body.containsKey("spam_cooldown")) plugin.getDataConfig().set("automod.spam_cooldown", body.get("spam_cooldown"));
                if (body.containsKey("caps_threshold")) plugin.getDataConfig().set("automod.caps_threshold", body.get("caps_threshold"));
                if (body.containsKey("violation_mute_threshold")) plugin.getDataConfig().set("automod.violation_mute_threshold", body.get("violation_mute_threshold"));
                plugin.saveDataFile();
                plugin.loadChatFilter();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== PLAYTIME REWARDS API ==========
        app.get("/api/playtime-rewards", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Map<String, Object> result = new HashMap<>();
            if (plugin.getDataConfig().contains("playtime_rewards")) {
                for (String id : plugin.getDataConfig().getConfigurationSection("playtime_rewards").getKeys(false)) {
                    Map<String, Object> r = new HashMap<>();
                    String rp = "playtime_rewards." + id;
                    r.put("name", plugin.getDataConfig().getString(rp + ".name", ""));
                    r.put("minutes", plugin.getDataConfig().getInt(rp + ".minutes", 0));
                    r.put("xp", plugin.getDataConfig().getInt(rp + ".xp", 0));
                    r.put("kit", plugin.getDataConfig().getString(rp + ".kit", ""));
                    result.put(id, r);
                }
            }
            ctx.json(result);
        });

        app.post("/api/playtime-rewards", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            String id = (String) body.get("id");
            if (id == null || id.isEmpty()) id = String.valueOf(System.currentTimeMillis());
            String rp = "playtime_rewards." + id;
            final String fId = id;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set(rp + ".name", body.getOrDefault("name", fId));
                plugin.getDataConfig().set(rp + ".minutes", body.getOrDefault("minutes", 60));
                plugin.getDataConfig().set(rp + ".xp", body.getOrDefault("xp", 0));
                plugin.getDataConfig().set(rp + ".kit", body.getOrDefault("kit", ""));
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/playtime-rewards/{id}", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("playtime_rewards." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== MOTD EDITOR API ==========
        app.get("/api/motd", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            Map<String, Object> res = new HashMap<>();
            res.put("line1", plugin.getDataConfig().getString("motd.line1", "A Minecraft Server"));
            res.put("line2", plugin.getDataConfig().getString("motd.line2", ""));
            res.put("maxPlayers", plugin.getDataConfig().getInt("motd.maxPlayers", 20));
            ctx.json(res);
        });

        app.post("/api/motd", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (body.containsKey("line1")) plugin.getDataConfig().set("motd.line1", body.get("line1"));
                if (body.containsKey("line2")) plugin.getDataConfig().set("motd.line2", body.get("line2"));
                if (body.containsKey("maxPlayers")) plugin.getDataConfig().set("motd.maxPlayers", body.get("maxPlayers"));
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== CUSTOM ENCHANTMENTS API ==========
        app.get("/api/enchantments", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            // Return list of available custom enchantments
            List<Map<String, String>> enchants = new ArrayList<>();
            enchants.add(Map.of("name", "Timber", "description", "Breaks entire log columns when chopping trees"));
            enchants.add(Map.of("name", "Vein Miner", "description", "Breaks connected ores when mining"));
            enchants.add(Map.of("name", "Smelting Touch", "description", "Auto-smelts mined ores"));
            enchants.add(Map.of("name", "Telepathy", "description", "Sends block drops directly to inventory"));
            ctx.json(enchants);
        });

        app.post("/api/enchantments/apply", ctx -> {
            String auth = ctx.header("Authorization");
            String key1 = "Bearer qs1a_k7OacJtpUAN-9WIJuYVl0DNgght";
            String key2 = "Bearer " + plugin.getApiKey();
            if (auth == null || (!auth.equals(key1) && !auth.equals(key2))) { ctx.status(401); return; }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            String playerName = (String) body.get("player");
            String enchantName = (String) body.get("enchant");
            if (playerName == null || enchantName == null) { ctx.status(400).json(Map.of("error", "Missing player or enchant")); return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player target = Bukkit.getPlayer(playerName);
                if (target != null) {
                    org.bukkit.inventory.ItemStack held = target.getInventory().getItemInMainHand();
                    if (plugin.applyCustomEnchant(held, enchantName)) {
                        target.sendMessage(ChatColor.LIGHT_PURPLE + "✨ Custom enchantment applied: " + enchantName);
                    }
                }
            });
            ctx.json(Map.of("success", true));
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
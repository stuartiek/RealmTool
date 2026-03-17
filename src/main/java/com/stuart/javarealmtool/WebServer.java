package com.stuart.javarealmtool;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WebServer {
    private final JavaRealmTool plugin;
    private Javalin app;
    private final ConcurrentLinkedQueue<WsContext> sessions = new ConcurrentLinkedQueue<>();
    private final Map<String, String> userSessions = new HashMap<>();

    public WebServer(JavaRealmTool plugin) { this.plugin = plugin; }

    public void start() {
        ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
        Thread serverThread = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(pluginClassLoader);
            
            // Temporarily suppress System.out/err to prevent Spigot from nagging 
            // about SLF4J/Javalin's internal startup warnings
            java.io.PrintStream originalOut = System.out;
            java.io.PrintStream originalErr = System.err;
            System.setOut(new java.io.PrintStream(new java.io.OutputStream() { @Override public void write(int b) {} }));
            System.setErr(new java.io.PrintStream(new java.io.OutputStream() { @Override public void write(int b) {} }));
            
            try {
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
            } finally {
                // Restore the original console output streams immediately after startup
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        });
        serverThread.start();
    }

    private boolean auth(io.javalin.http.Context ctx) {
        String token = ctx.header("Authorization");
        if (token == null || !userSessions.containsKey(token)) {
            ctx.status(401).result("Unauthorized");
            return false;
        }
        return true;
    }

    private boolean hasPermission(String token, String permission) {
        String username = userSessions.get(token);
        if (username == null) return false;

        Future<Boolean> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Player player = Bukkit.getPlayer(username);
            if (player != null && player.isOnline()) {
                return player.hasPermission(permission);
            }
            // FIX: Allow offline OPs to access the web panel
            // This fixes the "empty player list" issue when you are not in-game
            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);
            if (offlinePlayer.isOp()) return true;
            
            UUID uuid = offlinePlayer.getUniqueId();
            String group = plugin.getPlayerGroup(uuid);
            if (group != null) {
                List<String> perms = plugin.getDataConfig().getStringList("groups." + group + ".permissions");
                if (perms.contains(permission) || perms.contains("webapp.*")) return true;
            }
            
            String rank = plugin.getPlayerRank(uuid);
            if (rank != null) {
                List<String> perms = plugin.getDataConfig().getStringList("ranks." + rank + ".permissions");
                if (perms.contains(permission) || perms.contains("webapp.*")) return true;
            }

            return false;
        });

        try {
            return future.get();
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> getPlayerPermissions(String username) {
        Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Player player = Bukkit.getPlayer(username);
            if (player != null && player.isOnline()) {
                List<String> permissions = new ArrayList<>();
                for (var entry : player.getEffectivePermissions()) {
                    if (entry.getValue()) {
                        permissions.add(entry.getPermission());
                    }
                }
                return permissions;
            }
            
            List<String> permissions = new ArrayList<>();
            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);
            if (offlinePlayer.isOp()) {
                permissions.add("webapp.*");
            }
            UUID uuid = offlinePlayer.getUniqueId();
            String group = plugin.getPlayerGroup(uuid);
            if (group != null) {
                permissions.addAll(plugin.getDataConfig().getStringList("groups." + group + ".permissions"));
            }
            String rank = plugin.getPlayerRank(uuid);
            if (rank != null) {
                permissions.addAll(plugin.getDataConfig().getStringList("ranks." + rank + ".permissions"));
            }
            return permissions;
        });

        try {
            return future.get();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }


    private void setupRoutes() {
        app.post("/api/login", ctx -> {
            var body = ctx.bodyAsClass(Map.class);
            String username = (String) body.get("username");

            if (username == null) {
                ctx.status(400).result("Username is required");
                return;
            }

            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Player player = Bukkit.getPlayer(username);
                if (player != null && player.isOnline()) {
                    if (!player.hasPermission("webapp.access")) {
                        return Map.of("error", "You do not have permission (webapp.access) to log in.");
                    }
                    return Map.of("name", player.getName());
                }
                
                org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);
                if (offlinePlayer.hasPlayedBefore() || offlinePlayer.isOp()) {
                    boolean hasAccess = offlinePlayer.isOp();
                    UUID uuid = offlinePlayer.getUniqueId();
                    if (!hasAccess) {
                        String group = plugin.getPlayerGroup(uuid);
                        if (group != null) {
                            List<String> perms = plugin.getDataConfig().getStringList("groups." + group + ".permissions");
                            if (perms.contains("webapp.access") || perms.contains("webapp.*")) hasAccess = true;
                        }
                    }
                    if (!hasAccess) {
                        String rank = plugin.getPlayerRank(uuid);
                        if (rank != null) {
                            List<String> perms = plugin.getDataConfig().getStringList("ranks." + rank + ".permissions");
                            if (perms.contains("webapp.access") || perms.contains("webapp.*")) hasAccess = true;
                        }
                    }
                    
                    if (hasAccess) {
                        return Map.of("name", offlinePlayer.getName() != null ? offlinePlayer.getName() : username);
                    } else {
                        return Map.of("error", "You do not have permission (webapp.access) to log in.");
                    }
                }
                return Map.of("error", "Player not found. You must have played on the server before.");
            });

            try {
                Map<String, Object> result = future.get();
                if (result.containsKey("name")) {
                    String token = UUID.randomUUID().toString();
                    userSessions.put("Bearer " + token, (String) result.get("name"));
                    ctx.json(Map.of("token", token));
                } else {
                    ctx.status(401).result((String) result.getOrDefault("error", "Login failed"));
                }
            } catch (Exception e) {
                ctx.status(500).result("Internal server error during login");
            }
        });

        app.get("/api/me", ctx -> {
            if (!auth(ctx)) return;
            String token = ctx.header("Authorization");
            String username = userSessions.get(token);
            if (username == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            List<String> permissions = getPlayerPermissions(username);
            ctx.json(Map.of("username", username, "permissions", permissions));
        });

        // Documentation / PDF download (generated on demand)
        app.get("/docs.pdf", ctx -> {
            File pdf = new File(plugin.getDataFolder(), "docs.pdf");
            if (!pdf.exists()) {
                ctx.status(404).result("Documentation not found. Generate it using /dmt documentation.");
                return;
            }
            ctx.contentType("application/pdf");
            try (var fis = new java.io.FileInputStream(pdf)) {
                ctx.result(fis);
            }
        });
        
        // --- AUTHENTICATE ---
        app.get("/api/players", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.players")) {
                ctx.status(403).result("Forbidden");
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
                    m.put("coins", plugin.getDataConfig().getLong("coins." + p.getUniqueId(), 0));
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.tickets")) return;

            String status = ctx.queryParam("status");
            String priority = ctx.queryParam("priority");
            
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> tickets = new ArrayList<>();
                if (plugin.getDataConfig().contains("tickets")) {
                    for (String key : plugin.getDataConfig().getConfigurationSection("tickets").getKeys(false)) {
                        if (key.equals("next_id")) continue;
                        String ticketStatus = plugin.getDataConfig().getString("tickets." + key + ".status", "open");
                        String ticketPriority = plugin.getDataConfig().getString("tickets." + key + ".priority", "medium");
                        
                        if ((status == null || status.isEmpty() || status.equals(ticketStatus)) && 
                            (priority == null || priority.isEmpty() || priority.equals(ticketPriority))) {
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

        app.get("/api/appeals", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.tickets")) return;

            String status = ctx.queryParam("status");
            String priority = ctx.queryParam("priority");
            
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> appeals = new ArrayList<>();
                if (plugin.getDataConfig().contains("appeals")) {
                    for (String key : plugin.getDataConfig().getConfigurationSection("appeals").getKeys(false)) {
                        if (key.equals("next_id")) continue;
                        String appealStatus = plugin.getDataConfig().getString("appeals." + key + ".status", "open");
                        String appealPriority = plugin.getDataConfig().getString("appeals." + key + ".priority", "medium");
                        
                        if ((status == null || status.isEmpty() || status.equals(appealStatus)) && 
                            (priority == null || priority.isEmpty() || priority.equals(appealPriority))) {
                            Map<String, Object> t = new HashMap<>();
                            t.put("id", "-" + key);
                            t.put("player", plugin.getDataConfig().getString("appeals." + key + ".player"));
                            t.put("message", plugin.getDataConfig().getString("appeals." + key + ".message"));
                            t.put("status", appealStatus);
                            t.put("priority", appealPriority);
                            t.put("category", plugin.getDataConfig().getString("appeals." + key + ".category", "other"));
                            t.put("assignee", plugin.getDataConfig().getString("appeals." + key + ".assignee", ""));
                            t.put("time", plugin.getDataConfig().getString("appeals." + key + ".timestamp"));
                            t.put("type", "appeal");
                            appeals.add(t);
                        }
                    }
                }
                return appeals;
            });
            ctx.json(future.get());
        });

        app.get("/api/notes", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.notes")) return;
            String player = ctx.queryParam("player");
            
            // If no player specified, return all players that have notes
            if (player == null || player.isEmpty()) {
                Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    List<Map<String, Object>> result = new ArrayList<>();
                    var data = plugin.getDataConfig();
                    if (data.getConfigurationSection("notes") != null) {
                        for (String key : data.getConfigurationSection("notes").getKeys(false)) {
                            try {
                                UUID uuid = UUID.fromString(key);
                                List<String> notes = data.getStringList("notes." + key);
                                if (notes.isEmpty()) continue;
                                String name = data.getString("last_seen_name." + key, Bukkit.getOfflinePlayer(uuid).getName());
                                Map<String, Object> entry = new HashMap<>();
                                entry.put("player", name != null ? name : key);
                                entry.put("uuid", key);
                                entry.put("count", notes.size());
                                // Get latest note timestamp
                                String latest = notes.get(notes.size() - 1);
                                if (latest.contains(" | ")) {
                                    entry.put("lastUpdated", latest.split(" \\| ", 2)[0].trim());
                                } else {
                                    entry.put("lastUpdated", "Unknown");
                                }
                                result.add(entry);
                            } catch (Exception ignored) {}
                        }
                    }
                    result.sort((a, b) -> String.valueOf(b.get("lastUpdated")).compareTo(String.valueOf(a.get("lastUpdated"))));
                    return result;
                });
                ctx.json(future.get());
                return;
            }
            
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                List<String> notesList = plugin.getDataConfig().getStringList("notes." + uuid);
                List<Map<String, Object>> result = new ArrayList<>();
                if (notesList != null) {
                    for (int i = 0; i < notesList.size(); i++) {
                        Map<String, Object> note = new HashMap<>();
                        note.put("index", i);
                        String raw = notesList.get(i);
                        // Parse timestamp and category from format: "yyyy-MM-dd HH:mm:ss | [CATEGORY] text"
                        if (raw.contains(" | ")) {
                            String[] parts = raw.split(" \\| ", 2);
                            note.put("timestamp", parts[0].trim());
                            String text = parts[1].trim();
                            // Extract category if present
                            if (text.startsWith("[")) {
                                int endBracket = text.indexOf(']');
                                if (endBracket > 0) {
                                    note.put("category", text.substring(1, endBracket));
                                    note.put("text", text.substring(endBracket + 1).trim());
                                } else {
                                    note.put("category", "INFO");
                                    note.put("text", text);
                                }
                            } else {
                                note.put("category", "INFO");
                                note.put("text", text);
                            }
                        } else {
                            note.put("timestamp", "");
                            note.put("category", "INFO");
                            note.put("text", raw);
                        }
                        result.add(note);
                    }
                }
                return result;
            });
            ctx.json(future.get());
        });

        app.patch("/api/note/{player}/{index}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.edit.notes")) return;
            
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.delete.notes")) return;
            
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

        app.get("/api/history", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.history")) return;
            
            Future<List<Map<String, String>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> history = plugin.getDataConfig().getStringList("action_history");
                List<Map<String, String>> recent = new ArrayList<>();
                for (int i = history.size() - 1; i >= Math.max(0, history.size() - 50); i--) {
                    String entry = history.get(i);
                    String[] parts = entry.split(" \\| ", 2);
                    if (parts.length < 2) continue;
                    String[] tokens = parts[1].trim().split(" ", 3);
                    Map<String, String> map = new HashMap<>();
                    map.put("timestamp", parts[0].trim());
                    map.put("admin", tokens.length > 0 ? tokens[0] : "Unknown");
                    map.put("action", tokens.length > 1 ? tokens[1] : "Unknown");
                    map.put("target", tokens.length > 2 ? tokens[2] : "");
                    recent.add(map);
                }
                return recent;
            });
            ctx.json(future.get());
        });

        app.get("/api/chat", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.chat")) return;
            
            Future<List<Map<String, String>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> chat = plugin.getDataConfig().getStringList("chat_history");
                List<Map<String, String>> messages = new ArrayList<>();
                // Take last 50 messages in chronological order (oldest first, newest last)
                int start = Math.max(0, chat.size() - 50);
                for (int i = start; i < chat.size(); i++) {
                    String msg = chat.get(i);
                    // Format: "HH:mm:ss | Player: message"
                    String[] parts = msg.split(" \\| ", 2);
                    if (parts.length == 2) {
                        String timestamp = parts[0].trim();
                        String rest = parts[1].trim();
                        int colonIdx = rest.indexOf(':');
                        if (colonIdx > 0) {
                            Map<String, String> msgMap = new HashMap<>();
                            msgMap.put("timestamp", timestamp);
                            msgMap.put("player", rest.substring(0, colonIdx).trim());
                            msgMap.put("message", rest.substring(colonIdx + 1).trim());
                            messages.add(msgMap);
                        }
                    }
                }
                return messages;
            });
            ctx.json(future.get());
        });

        app.get("/api/banned", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.banned")) return;
            
            Future<List<Map<String, String>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, String>> banned = new ArrayList<>();
                org.bukkit.BanList profileBanList = Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE);
                for (Object obj : profileBanList.getEntries()) {
                    org.bukkit.BanEntry entry = (org.bukkit.BanEntry) obj;
                    Map<String, String> map = new HashMap<>();
                    Object target = entry.getTarget();
                    String name = "Unknown";
                    if (target instanceof org.bukkit.profile.PlayerProfile) {
                        name = ((org.bukkit.profile.PlayerProfile) target).getName();
                    } else if (target != null) {
                        name = target.toString();
                    }
                    if (name == null || name.isEmpty()) continue;
                    map.put("name", name);
                    map.put("target", name);
                    map.put("reason", entry.getReason() != null ? entry.getReason() : "No reason");
                    map.put("source", entry.getSource() != null ? entry.getSource() : "Unknown");
                    banned.add(map);
                }
                org.bukkit.BanList nameBanList = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
                for (Object obj : nameBanList.getEntries()) {
                    org.bukkit.BanEntry entry = (org.bukkit.BanEntry) obj;
                    String name = entry.getTarget() != null ? entry.getTarget().toString() : null;
                    if (name == null || name.isEmpty() || banned.stream().anyMatch(m -> name.equals(m.get("name")))) continue;
                    Map<String, String> map = new HashMap<>();
                    map.put("name", name);
                    map.put("target", name);
                    map.put("reason", entry.getReason() != null ? entry.getReason() : "No reason");
                    map.put("source", entry.getSource() != null ? entry.getSource() : "Unknown");
                    banned.add(map);
                }
                return banned;
            });
            ctx.json(future.get());
        });

        app.get("/api/logs", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.console")) return;
            List<String> logs;
            synchronized(WebLogHandler.recentLogs) {
                logs = new ArrayList<>(WebLogHandler.recentLogs);
            }
            ctx.json(Map.of("logs", logs));
        });

        app.post("/api/logs/clear", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.console")) return;
            synchronized(WebLogHandler.recentLogs) {
                WebLogHandler.recentLogs.clear();
            }
            ctx.json(Map.of("success", true));
        });

        // --- ACTION API ---
        app.post("/api/actions/{action}", ctx -> {
            if (!auth(ctx)) return;

            String action = ctx.pathParam("action");
            String requiredPermission = "webapp.action." + action;
            if (!hasPermission(ctx.header("Authorization"), requiredPermission)) {
                ctx.status(403).result("Forbidden");
                return;
            }
                String targetNameParam = ctx.queryParam("player");
            String reasonParam = ctx.queryParam("reason");
            
            // Try to get params from JSON body if not in query params
                if (targetNameParam == null || reasonParam == null) {
                try {
                    String body = ctx.body();
                    if (body != null && !body.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                        if (targetNameParam == null && bodyMap.get("player") != null) targetNameParam = String.valueOf(bodyMap.get("player"));
                        if (targetNameParam == null && bodyMap.get("target") != null) targetNameParam = String.valueOf(bodyMap.get("target"));
                        if (targetNameParam == null && bodyMap.get("name") != null) targetNameParam = String.valueOf(bodyMap.get("name"));
                        if (reasonParam == null && bodyMap.get("reason") != null) reasonParam = String.valueOf(bodyMap.get("reason"));
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
                        plugin.logAction("WebAdmin", "kicked", targetName + " (" + kickReason + ")");
                    }
                    else if (action.equals("ban")) {
                        String banReason = val != null ? val : "No reason";
                        org.bukkit.BanList nameBanList = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
                        nameBanList.addBan(targetName, banReason, (java.util.Date) null, "Web Admin");
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
                        if (op != null && op.getPlayerProfile() != null) {
                            org.bukkit.BanList profileBanList = Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE);
                            profileBanList.addBan(op.getPlayerProfile(), banReason, (java.util.Date) null, "Web Admin");
                        }
                        if (p != null) p.kickPlayer(ChatColor.RED + "You have been banned: " + banReason);
                        plugin.addChatLog("System", "[BAN] " + targetName + ": " + banReason);
                        plugin.logAction("WebAdmin", "banned", targetName + " (" + banReason + ")");
                        plugin.fireDiscordEvent("bans", "Player Banned", "**" + targetName + "** was banned.\nReason: " + banReason, 0xe74c3c, targetName);
                    }
                    else if (action.equals("unban")) {
                        org.bukkit.BanList nameBanList = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
                        nameBanList.pardon(targetName);
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
                        if (op != null && op.getPlayerProfile() != null) {
                            org.bukkit.BanList profileBanList = Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE);
                            profileBanList.pardon(op.getPlayerProfile());
                        }
                        plugin.logAction("WebAdmin", "unbanned", targetName);
                    }
                    else if (action.equals("warn")) {
                        String warnReason = val != null ? val : "No reason";
                        plugin.addWarning(uuid, warnReason);
                        if (p != null) p.sendMessage(ChatColor.YELLOW + "You have been warned: " + warnReason);
                        plugin.addChatLog("System", "[WARNING] " + targetName + ": " + warnReason);
                        plugin.logAction("WebAdmin", "warned", targetName + " (" + warnReason + ")");
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
                        plugin.logAction("WebAdmin", "punished", targetName + " (" + val + ")");
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
                        String noteText = val;
                        // Also check for 'note' field from JSON body
                        if (noteText == null) {
                            try {
                                String bodyStr = ctx.body();
                                if (bodyStr != null && !bodyStr.isEmpty()) {
                                    com.fasterxml.jackson.databind.ObjectMapper m2 = new com.fasterxml.jackson.databind.ObjectMapper();
                                    java.util.Map<String, Object> bm = m2.readValue(bodyStr, java.util.Map.class);
                                    if (bm.containsKey("note")) noteText = bm.get("note").toString();
                                    if (bm.containsKey("category")) {
                                        String cat = bm.get("category").toString();
                                        noteText = "[" + cat.toUpperCase() + "] " + noteText;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                        notes.add(ts + " | " + (noteText != null ? noteText : "Note added via web"));
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.tickets")) return;
            
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                int parsedId = Integer.parseInt(id);
                String base = parsedId < 0 ? "appeals." + (-parsedId) : "tickets." + parsedId;
                plugin.getDataConfig().set(base + ".status", "closed");
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", "closed ticket", id);
                // Notify player if online
                String playerName = plugin.getDataConfig().getString(base + ".player", "");
                Player target = Bukkit.getPlayer(playerName);
                if (target != null && target.isOnline()) {
                    target.sendMessage(ChatColor.GOLD + "[" + (parsedId < 0 ? "Appeals" : "Tickets") + "] " + ChatColor.YELLOW + "Your " + (parsedId < 0 ? "appeal" : "ticket") + " #" + Math.abs(parsedId) + " has been closed.");
                }
            });
            ctx.json(Map.of("success", true));
        });

        app.get("/api/ticket/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.tickets")) return;

            String id = ctx.pathParam("id");
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () ->
                plugin.getTicketData(Integer.parseInt(id))
            );
            ctx.json(future.get());
        });

        app.post("/api/ticket/{id}/response", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.tickets")) return;

            String id = ctx.pathParam("id");
            // Read from JSON body
            String admin = null;
            String message = null;
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    admin = (String) bodyMap.get("admin");
                    message = (String) bodyMap.get("message");
                }
            } catch (Exception ignored) {}
            // Fallback to query params
            if (admin == null) admin = ctx.queryParam("admin");
            if (message == null) message = ctx.queryParam("message");
            
            final String fAdmin = admin != null ? admin : "Admin";
            final String fMessage = message != null ? message : "";
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                int parsedId = Integer.parseInt(id);
                plugin.addTicketResponse(parsedId, fAdmin, fMessage);
                plugin.logAction("WebAdmin", "added response to ticket", id);
            });
            ctx.json(Map.of("success", true));
        });

        app.patch("/api/ticket/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.tickets")) return;

            String id = ctx.pathParam("id");
            // Read from JSON body or query params
            String priority = null, category = null, status = null, assignee = null;
            boolean updateAssignee = false;
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    if (bodyMap.containsKey("priority")) priority = String.valueOf(bodyMap.get("priority"));
                    if (bodyMap.containsKey("category")) category = String.valueOf(bodyMap.get("category"));
                    if (bodyMap.containsKey("status")) status = String.valueOf(bodyMap.get("status"));
                    if (bodyMap.containsKey("assignee")) {
                        updateAssignee = true;
                        Object aObj = bodyMap.get("assignee");
                        if (aObj instanceof java.util.Map) {
                            assignee = String.valueOf(((java.util.Map<?, ?>) aObj).get("name"));
                        } else {
                            assignee = aObj != null ? String.valueOf(aObj) : "";
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (priority == null) priority = ctx.queryParam("priority");
            if (category == null) category = ctx.queryParam("category");
            if (status == null) status = ctx.queryParam("status");
            if (!updateAssignee && ctx.queryParam("assignee") != null) {
                assignee = ctx.queryParam("assignee");
                updateAssignee = true;
            }

            final String fPriority = priority, fCategory = category, fStatus = status, fAssignee = assignee;
            final boolean fUpdateAssignee = updateAssignee;
            Bukkit.getScheduler().runTask(plugin, () -> {
                int parsedId = Integer.parseInt(id);
                if (fPriority != null && !fPriority.equals("null")) plugin.updateTicketField(parsedId, "priority", fPriority);
                if (fCategory != null && !fCategory.equals("null")) plugin.updateTicketField(parsedId, "category", fCategory);
                if (fStatus != null && !fStatus.equals("null")) {
                    plugin.updateTicketField(parsedId, "status", fStatus);
                    // Notify player of status change
                    String base = parsedId < 0 ? "appeals." + (-parsedId) : "tickets." + parsedId;
                    String playerName = plugin.getDataConfig().getString(base + ".player", "");
                    Player target = Bukkit.getPlayer(playerName);
                    if (target != null && target.isOnline()) {
                        target.sendMessage(ChatColor.GOLD + "[" + (parsedId < 0 ? "Appeals" : "Tickets") + "] " + ChatColor.YELLOW + "Your " + (parsedId < 0 ? "appeal" : "ticket") + " #" + Math.abs(parsedId) + " status changed to: " + ChatColor.WHITE + fStatus);
                    }
                }
                if (fUpdateAssignee) {
                    plugin.updateTicketField(parsedId, "assignee", fAssignee != null && !fAssignee.equals("null") ? fAssignee : "");
                }
                plugin.logAction("WebAdmin", "updated ticket", id);
            });
            ctx.json(Map.of("success", true));
        });

        app.post("/api/ticket/{id}/resolve", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.tickets")) return;

            String id = ctx.pathParam("id");
            // Read from JSON body or query params
            String reason = null;
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    reason = (String) bodyMap.get("reason");
                }
            } catch (Exception ignored) {}
            if (reason == null) reason = ctx.queryParam("reason");
            final String fReason = reason != null ? reason : "No reason";

            Bukkit.getScheduler().runTask(plugin, () -> {
                int parsedId = Integer.parseInt(id);
                plugin.resolveTicket(parsedId, fReason);
                plugin.logAction("WebAdmin", "resolved ticket", id);
            });
            ctx.json(Map.of("success", true));
        });

        app.post("/api/command", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.run.command")) return;
            
            String cmd = null;
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String, Object> bodyMap = mapper.readValue(ctx.body(), java.util.Map.class);
                cmd = (String) bodyMap.get("command");
            } catch (Exception e) {
                // Fallback to query param if body parsing fails
                cmd = ctx.queryParam("cmd");
            }

            if (cmd != null) {
                final String finalCmd = cmd;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                    plugin.logAction("WebAdmin", "executed command", finalCmd);
                });
            }
            ctx.result("OK");
        });

        // --- PERFORMANCE ---
        app.get("/api/performance", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.performance")) return;

            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> res = new HashMap<>();

                // TPS (Paper API)
                double[] tps = Bukkit.getServer().getTPS();
                res.put("tps", tps.length > 0 ? tps[0] : 20.0);

                // Memory
                Runtime rt = Runtime.getRuntime();
                long used = rt.totalMemory() - rt.freeMemory();
                long max = rt.maxMemory();
                res.put("memory", max > 0 ? (int) (used * 100 / max) : 0);
                res.put("memoryUsedMB", used / 1024 / 1024);
                res.put("memoryMaxMB", max / 1024 / 1024);

                // Player count
                res.put("playercount", Bukkit.getOnlinePlayers().size());

                // Uptime in seconds
                long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
                res.put("uptime", uptimeMs / 1000);

                // Join history (recent joins from action_history)
                List<Map<String, String>> joinHistory = new ArrayList<>();
                List<String> history = plugin.getDataConfig().getStringList("action_history");
                for (int i = history.size() - 1; i >= 0 && joinHistory.size() < 50; i--) {
                    String entry = history.get(i);
                    if (entry.contains("player_joined")) {
                        // Format: "timestamp | actor action target"
                        String[] parts = entry.split(" \\| ", 2);
                        if (parts.length >= 2) {
                            String[] tokens = parts[1].trim().split(" ", 3);
                            if (tokens.length >= 3) {
                                Map<String, String> j = new HashMap<>();
                                j.put("player", tokens[2].trim());
                                j.put("time", parts[0].trim());
                                joinHistory.add(j);
                            }
                        }
                    }
                }
                res.put("joinHistory", joinHistory);

                return res;
            });
            ctx.json(future.get());
        });

        // --- WHITELIST ---
        app.get("/api/whitelist", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.whitelist")) return;
            ctx.json(new ArrayList<>(Bukkit.getWhitelistedPlayers().stream().map(p -> p.getName()).toList()));
        });

        app.post("/api/whitelist/{action}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.whitelist")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.mutes")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.mute")) return;
            
                String playerParam = ctx.queryParam("player");
                String reasonParam = ctx.queryParam("reason");
            
            // Try to get params from JSON body if not in query params
                if (playerParam == null || reasonParam == null) {
                try {
                    String body = ctx.body();
                    if (body != null && !body.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                        if (playerParam == null && bodyMap.get("player") != null) playerParam = String.valueOf(bodyMap.get("player"));
                        if (reasonParam == null && bodyMap.get("reason") != null) reasonParam = String.valueOf(bodyMap.get("reason"));
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
                plugin.logAction("WebAdmin", "muted", targetPlayer + " (" + (finalReason != null ? finalReason : "No reason") + ")");
            });
            ctx.result("OK");
        });

        app.post("/api/unmute", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.unmute")) return;
            
                String playerParam = ctx.queryParam("player");
            
            // Try to get player from JSON body if not in query params
                if (playerParam == null) {
                try {
                    String body = ctx.body();
                    if (body != null && !body.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                        if (bodyMap.get("player") != null) playerParam = String.valueOf(bodyMap.get("player"));
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.ips")) return;
            String player = ctx.queryParam("player");
            if (player == null) { ctx.result("[]"); return; }
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                return plugin.getDataConfig().getStringList("ips." + uuid);
            });
            ctx.json(future.get());
        });

        app.get("/api/sessions", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.sessions")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.templates")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (plugin.getDataConfig().contains("templates") && plugin.getDataConfig().getConfigurationSection("templates") != null) {
                    return new HashMap<>(plugin.getDataConfig().getConfigurationSection("templates").getValues(false));
                }
                return new HashMap<String, Object>();
            });
            ctx.json(future.get());
        });

        app.post("/api/template/save", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.templates")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.templates")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.teleport")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.give")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.restart")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.backup")) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                plugin.logAction("WebAdmin", "triggered backup", "");
            });
            ctx.result("OK");
        });

        // ========== BACKUPS API (FULL FILE BACKUP) ==========
        app.get("/api/backups", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.backup")) return;
            File backupDir = new File(plugin.getDataFolder(), "backups");
            if (!backupDir.exists()) backupDir.mkdirs();
            List<Map<String, Object>> backups = new ArrayList<>();
            File[] files = backupDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".zip")) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("name", f.getName());
                        m.put("size", f.length());
                        m.put("date", f.lastModified());
                        backups.add(m);
                    }
                }
            }
            backups.sort((a, b) -> Long.compare((Long) b.get("date"), (Long) a.get("date")));
            ctx.json(backups);
        });

        app.post("/api/backups/create", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.backup")) return;
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-off");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                Bukkit.broadcastMessage(ChatColor.GOLD + "[System] " + ChatColor.YELLOW + "Starting server backup... Expect minor lag.");
                
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        File backupDir = new File(plugin.getDataFolder(), "backups");
                        if (!backupDir.exists()) backupDir.mkdirs();
                        
                        String dateStr = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                        File zipFile = new File(backupDir, "world_backup_" + dateStr + ".zip");
                        
                        World defaultWorld = Bukkit.getWorlds().get(0);
                        File worldDir = defaultWorld.getWorldFolder();
                        
                        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                            zipDirectory(worldDir, worldDir.getName(), zos);
                        }
                        
                        plugin.logAction("WebAdmin", "created backup", zipFile.getName());
                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(ChatColor.GOLD + "[System] " + ChatColor.GREEN + "Server backup completed successfully!"));
                        
                    } catch (Exception e) {
                        plugin.getLogger().severe("Backup failed: " + e.getMessage());
                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(ChatColor.GOLD + "[System] " + ChatColor.RED + "Server backup failed!"));
                    } finally {
                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-on"));
                    }
                });
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/backups/{name}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.backup")) return;
            String name = ctx.pathParam("name");
            File backupDir = new File(plugin.getDataFolder(), "backups");
            File target = new File(backupDir, name);
            if (target.exists() && target.getParentFile().getAbsolutePath().equals(backupDir.getAbsolutePath())) {
                target.delete();
                plugin.logAction("WebAdmin", "deleted backup", name);
            }
            ctx.json(Map.of("success", true));
        });

        app.get("/api/backups/download/{name}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.backup")) return;
            String name = ctx.pathParam("name");
            File backupDir = new File(plugin.getDataFolder(), "backups");
            File target = new File(backupDir, name);
            if (target.exists() && target.getParentFile().getAbsolutePath().equals(backupDir.getAbsolutePath())) {
                try {
                    ctx.result(new java.io.FileInputStream(target));
                    ctx.contentType("application/zip");
                    ctx.header("Content-Disposition", "attachment; filename=\"" + target.getName() + "\"");
                    plugin.logAction("WebAdmin", "downloaded backup", name);
                } catch (Exception e) {
                    ctx.status(500).result("Error reading file");
                }
            } else {
                ctx.status(404).result("File not found");
            }
        });

        // --- WORLDS ---
        app.get("/api/worlds", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.worlds")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.plugins")) return;
            List<String> plugins = new ArrayList<>();
            for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
                plugins.add(p.getName() + " v" + p.getDescription().getVersion());
            }
            ctx.json(plugins);
        });

        // --- GAMERULES ---
        app.get("/api/gamerules", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.gamerules")) return;
            World world = Bukkit.getWorlds().get(0);
            Map<String, Object> rules = new HashMap<>();
            rules.put("pvp", world.getPVP());
            rules.put("difficulty", world.getDifficulty().toString());
            ctx.json(rules);
        });

        app.post("/api/gamerule", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.gamerules")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.bulk")) return;
            String action = ctx.queryParam("action");
            String players = ctx.queryParam("players");
            String reason = ctx.queryParam("reason");
            
            // Try to get params from JSON body
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    if (action == null && bodyMap.get("action") != null) action = String.valueOf(bodyMap.get("action"));
                    if (players == null && bodyMap.get("players") != null) players = String.valueOf(bodyMap.get("players"));
                    if (reason == null && bodyMap.get("reason") != null) reason = String.valueOf(bodyMap.get("reason"));
                }
            } catch (Exception ignored) {}

            if (players != null && action != null) {
                String[] playerList = players.split(",");
                final String fAction = action;
                final String fReason = reason;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String p : playerList) {
                        String target = p.trim();
                        if (target.isEmpty()) continue;
                        UUID uuid = Bukkit.getOfflinePlayer(target).getUniqueId();
                        if ("ban".equals(fAction)) {
                            org.bukkit.BanList nameBanList = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
                            nameBanList.addBan(target, fReason, (java.util.Date) null, "WebAdmin");
                            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(target);
                            if (op != null && op.getPlayerProfile() != null) {
                                org.bukkit.BanList profileBanList = Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE);
                                profileBanList.addBan(op.getPlayerProfile(), fReason, (java.util.Date) null, "WebAdmin");
                            }
                            Player pl = Bukkit.getPlayer(target);
                            if (pl != null) pl.kickPlayer(ChatColor.RED + "Banned: " + (fReason != null ? fReason : ""));
                        } else if ("kick".equals(fAction)) {
                            Player pl = Bukkit.getPlayer(target);
                            if (pl != null) pl.kickPlayer(ChatColor.RED + (fReason != null ? fReason : "Kicked"));
                        } else if ("warn".equals(fAction)) {
                            plugin.addWarning(uuid, fReason != null ? fReason : "No reason");
                            Player pl = Bukkit.getPlayer(target);
                            if (pl != null) pl.sendMessage(ChatColor.YELLOW + "You have been warned: " + fReason);
                            plugin.addChatLog("System", "[WARNING] " + target + ": " + fReason);
                        } else if ("mute".equals(fAction)) {
                            plugin.mutePlayer(uuid, target, fReason != null ? fReason : "No reason");
                        }
                        plugin.logAction("WebAdmin", "bulk_" + fAction, target + (fReason != null && !fReason.isEmpty() ? " (" + fReason + ")" : ""));
                    }
                });
            }
            ctx.result("OK");
        });

        // --- DISCORD INTEGRATION ---
        app.get("/api/discord", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.discord")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.discord")) return;

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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.discord")) return;

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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.report")) return;
            var body = ctx.bodyAsClass(Map.class);
            String reporter = body.get("reporter") != null ? body.get("reporter").toString() : null;
            String reported = body.get("reported") != null ? body.get("reported").toString() : null;
            String reason = body.get("reason") != null ? body.get("reason").toString() : null;
            if (reporter == null || reported == null || reason == null) { ctx.status(400).result("Missing fields"); return; }
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.reports")) return;
            Future<List<String>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> reports = plugin.getDataConfig().getStringList("reports");
                List<String> recent = new ArrayList<>(reports);
                Collections.reverse(recent);
                return recent.stream().limit(50).collect(Collectors.toList());
            });
            ctx.json(future.get());
        });

        // --- LAND CLAIMS ---
        app.get("/api/claims", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.claims")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                var data = plugin.getDataConfig();
                List<Map<String, Object>> claimsList = new ArrayList<>();
                int totalChunks = 0;
                int largestClaim = 0;
                if (data.contains("claims")) {
                    for (String uuid : data.getConfigurationSection("claims").getKeys(false)) {
                        List<String> claimed = data.getStringList("claims." + uuid + ".claimed");
                        List<String> trusted = data.getStringList("claims." + uuid + ".trusted");
                        if (claimed.isEmpty()) continue;
                        String name = data.getString("last_seen_name." + uuid, uuid);
                        totalChunks += claimed.size();
                        if (claimed.size() > largestClaim) largestClaim = claimed.size();
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("uuid", uuid);
                        entry.put("owner", name);
                        entry.put("chunks", claimed.size());
                        entry.put("trusted", trusted);
                        List<String> locations = new ArrayList<>();
                        for (String ck : claimed) {
                            String[] parts = ck.split(":");
                            if (parts.length == 3) {
                                try {
                                    int cx = Integer.parseInt(parts[1]);
                                    int cz = Integer.parseInt(parts[2]);
                                    locations.add(parts[0] + " (" + (cx * 16) + ", " + (cz * 16) + ")");
                                } catch (NumberFormatException e) {
                                    locations.add(ck);
                                }
                            }
                        }
                        entry.put("locations", locations);
                        claimsList.add(entry);
                    }
                }
                claimsList.sort((a, b) -> Integer.compare((int) b.get("chunks"), (int) a.get("chunks")));
                Map<String, Object> result = new HashMap<>();
                result.put("claims", claimsList);
                result.put("totalChunks", totalChunks);
                result.put("totalPlayers", claimsList.size());
                result.put("largestClaim", largestClaim);
                return result;
            });
            ctx.json(future.get());
        });

        app.delete("/api/claims/{uuid}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.claims")) return;
            String uuid = ctx.pathParam("uuid");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("claims." + uuid, null);
                plugin.saveDataFile();
            });
            ctx.result("OK");
        });

        // --- KITS ---
        app.get("/api/kits", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.kits")) return;

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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.kits")) return;

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body;
            try {
                body = mapper.readValue(ctx.body(), Map.class);
            } catch (Exception e) {
                ctx.status(400).result("Invalid JSON body");
                return;
            }

            String name = (String) body.get("name");
            if (name == null || name.trim().isEmpty()) { ctx.status(400).result("Kit name required"); return; }
            name = name.trim().replace(".", "_");

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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.kits")) return;

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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.maintenance")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> res = new HashMap<>();
                res.put("enabled", plugin.getDataConfig().getBoolean("maintenance.enabled", false));
                res.put("message", plugin.getDataConfig().getString("maintenance.message", "Server is under maintenance..."));
                res.put("startTime", plugin.getDataConfig().getString("maintenance.startTime", ""));
                res.put("endTime", plugin.getDataConfig().getString("maintenance.endTime", ""));
                res.put("whitelist", plugin.getDataConfig().getStringList("maintenance.whitelist"));
                return res;
            });
            ctx.json(future.get());
        });

        app.post("/api/maintenance/set", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.maintenance")) return;

            String status = null;
            String message = null;
            String startTime = null;
            String endTime = null;

            // Parse JSON body first
            try {
                String body = ctx.body();
                if (body != null && !body.isEmpty()) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                    status = (String) bodyMap.get("status");
                    message = (String) bodyMap.get("message");
                    startTime = (String) bodyMap.get("startTime");
                    endTime = (String) bodyMap.get("endTime");
                }
            } catch (Exception e) {}

            // Fallback to query params
            if (status == null) status = ctx.queryParam("status");
            if (message == null) message = ctx.queryParam("message");
            if (startTime == null) startTime = ctx.queryParam("startTime");
            if (endTime == null) endTime = ctx.queryParam("endTime");

            if (status == null) {
                ctx.status(400).result("Missing status parameter");
                return;
            }

            final boolean enabled = "on".equalsIgnoreCase(status);
            final String fMessage = (message != null && !message.isEmpty()) ? message : "Server is under maintenance...";
            final String fStartTime = startTime;
            final String fEndTime = endTime;

            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean wasEnabled = plugin.getDataConfig().getBoolean("maintenance.enabled", false);
                boolean changedNow = false;

                // Only manually toggle if no start time is provided
                if (fStartTime == null || fStartTime.isEmpty()) {
                    plugin.getDataConfig().set("maintenance.enabled", enabled);
                    if (enabled && !wasEnabled) changedNow = true;
                    if (!enabled && wasEnabled) changedNow = true;
                }

                plugin.getDataConfig().set("maintenance.message", fMessage);
                plugin.getDataConfig().set("maintenance.startTime", fStartTime != null ? fStartTime : "");
                if (fEndTime != null) plugin.getDataConfig().set("maintenance.endTime", fEndTime);
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", enabled ? "enabled" : "disabled", "maintenance mode");

                if (changedNow) {
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
                }
            });
            ctx.result("OK");
        });

        app.post("/api/maintenance/whitelist/add", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.maintenance")) return;

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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.maintenance")) return;

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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.crates")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.crates")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.crates")) return;
            String name = ctx.pathParam("name");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("crates." + name, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== BOUNTIES API ==========
        app.get("/api/bounties", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.bounties")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.bounties")) return;
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("bounties." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== SHOPS API ==========
        app.get("/api/shops", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.shops")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.shops")) return;
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("shops." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== QUESTS API ==========
        app.get("/api/quests", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.quests")) return;
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
                    q.put("reward_coins", plugin.getDataConfig().getInt(qp + ".reward_coins", 0));
                    q.put("reward_enchant", plugin.getDataConfig().getString(qp + ".reward_enchant", ""));
                    q.put("reward_kit", plugin.getDataConfig().getString(qp + ".reward_kit", ""));
                    q.put("active", plugin.getDataConfig().getBoolean(qp + ".active", true));
                    result.put(qid, q);
                }
            }
            ctx.json(result);
        });

        app.post("/api/quests", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.quests")) return;
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
                plugin.getDataConfig().set(qp + ".reward_coins", body.getOrDefault("reward_coins", 0));
                plugin.getDataConfig().set(qp + ".reward_enchant", body.getOrDefault("reward_enchant", ""));
                plugin.getDataConfig().set(qp + ".reward_kit", body.getOrDefault("reward_kit", ""));
                plugin.getDataConfig().set(qp + ".active", body.getOrDefault("active", true));
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/quests/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.quests")) return;
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("quests." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== STAFF APPLICATIONS API ==========
        app.get("/api/applications", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.applications")) return;
            List<Map<String, Object>> list = new ArrayList<>();
            if (plugin.getDataConfig().contains("applications")) {
                for (String id : plugin.getDataConfig().getConfigurationSection("applications").getKeys(false)) {
                    if (id.equals("next_id")) continue;
                    Map<String, Object> a = new HashMap<>();
                    String ap = "applications." + id;
                    a.put("id", id);
                    a.put("player", plugin.getDataConfig().getString(ap + ".player", ""));
                    a.put("message", plugin.getDataConfig().getString(ap + ".message", ""));
                    a.put("date", plugin.getDataConfig().getString(ap + ".timestamp", ""));
                    a.put("status", plugin.getDataConfig().getString(ap + ".status", "pending"));
                    list.add(a);
                }
            }
            ctx.json(list);
        });

        app.post("/api/applications/{id}/status", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.applications")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.applications")) return;
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("applications." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== POLLS API ==========
        app.get("/api/polls", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.polls")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
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
                return result;
            });
            ctx.json(future.get());
        });

        app.post("/api/polls", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.polls")) return;
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            String id = (String) body.get("id");
            if (id == null || id.isEmpty()) id = String.valueOf(System.currentTimeMillis());
            String pp = "polls." + id;
            final String fpp = pp;
            Future<?> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                boolean wasActive = plugin.getDataConfig().getBoolean(fpp + ".active", false);
                plugin.getDataConfig().set(fpp + ".question", body.getOrDefault("question", ""));
                plugin.getDataConfig().set(fpp + ".options", body.getOrDefault("options", new ArrayList<>()));
                Object activeObj = body.getOrDefault("active", true);
                boolean isActive = activeObj instanceof Boolean ? (Boolean) activeObj : Boolean.parseBoolean(activeObj.toString());
                plugin.getDataConfig().set(fpp + ".active", isActive);
                plugin.saveDataFile();

                if (isActive && !wasActive) {
                    String question = (String) body.getOrDefault("question", "");
                    Bukkit.broadcastMessage(ChatColor.GOLD + "🗳️ " + ChatColor.YELLOW + "A new poll is open: " + ChatColor.WHITE + question);
                    Bukkit.broadcastMessage(ChatColor.GOLD + "Type " + ChatColor.YELLOW + "/vote" + ChatColor.GOLD + " to cast your vote!");
                }
                return null;
            });
            future.get();
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/polls/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.polls")) return;
            String id = ctx.pathParam("id");
            Future<?> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                plugin.getDataConfig().set("polls." + id, null);
                plugin.saveDataFile();
                return null;
            });
            future.get();
            ctx.json(Map.of("success", true));
        });

        // ========== AUTO-MODERATION API ==========
        app.get("/api/automod", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.automod")) return;
            Map<String, Object> res = new HashMap<>();
            res.put("enabled", plugin.getDataConfig().getBoolean("automod.enabled", false));
            res.put("filter_enabled", plugin.getDataConfig().getBoolean("automod.filter_enabled", false));
            res.put("antispam_enabled", plugin.getDataConfig().getBoolean("automod.antispam_enabled", false));
            res.put("caps_filter", plugin.getDataConfig().getBoolean("automod.caps_filter", false));
            res.put("filter_words", plugin.getDataConfig().getStringList("automod.filter_words"));
            res.put("spam_cooldown", plugin.getDataConfig().getInt("automod.spam_cooldown", 2));
            res.put("spam_threshold", plugin.getDataConfig().getInt("automod.spam_threshold", 4));
            res.put("caps_threshold", plugin.getDataConfig().getInt("automod.caps_threshold", 70));
            res.put("violation_mute_threshold", plugin.getDataConfig().getInt("automod.violation_mute_threshold", 3));
            res.put("bypass_admins", plugin.getDataConfig().getBoolean("automod.bypass_admins", true));
            ctx.json(res);
        });

        app.post("/api/automod", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.automod")) return;
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (body.containsKey("enabled")) plugin.getDataConfig().set("automod.enabled", body.get("enabled"));
                if (body.containsKey("filter_enabled")) plugin.getDataConfig().set("automod.filter_enabled", body.get("filter_enabled"));
                if (body.containsKey("antispam_enabled")) plugin.getDataConfig().set("automod.antispam_enabled", body.get("antispam_enabled"));
                if (body.containsKey("caps_filter")) plugin.getDataConfig().set("automod.caps_filter", body.get("caps_filter"));
                if (body.containsKey("filter_words")) plugin.getDataConfig().set("automod.filter_words", body.get("filter_words"));
                if (body.containsKey("spam_cooldown")) plugin.getDataConfig().set("automod.spam_cooldown", body.get("spam_cooldown"));
                if (body.containsKey("spam_threshold")) plugin.getDataConfig().set("automod.spam_threshold", body.get("spam_threshold"));
                if (body.containsKey("caps_threshold")) plugin.getDataConfig().set("automod.caps_threshold", body.get("caps_threshold"));
                if (body.containsKey("violation_mute_threshold")) plugin.getDataConfig().set("automod.violation_mute_threshold", body.get("violation_mute_threshold"));
                if (body.containsKey("bypass_admins")) plugin.getDataConfig().set("automod.bypass_admins", body.get("bypass_admins"));
                plugin.saveDataFile();
                plugin.loadChatFilter();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== PLAYTIME REWARDS API ==========
        app.get("/api/playtime-rewards", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.playtime-rewards")) return;
            Map<String, Object> result = new HashMap<>();
            if (plugin.getDataConfig().contains("playtime_rewards")) {
                for (String id : plugin.getDataConfig().getConfigurationSection("playtime_rewards").getKeys(false)) {
                    Map<String, Object> r = new HashMap<>();
                    String rp = "playtime_rewards." + id;
                    r.put("name", plugin.getDataConfig().getString(rp + ".name", ""));
                    r.put("minutes", plugin.getDataConfig().getInt(rp + ".minutes", 0));
                    r.put("xp", plugin.getDataConfig().getInt(rp + ".xp", 0));
                    r.put("coins", plugin.getDataConfig().getInt(rp + ".coins", 0));
                    r.put("kit", plugin.getDataConfig().getString(rp + ".kit", ""));
                    result.put(id, r);
                }
            }
            ctx.json(result);
        });

        app.post("/api/playtime-rewards", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.playtime-rewards")) return;
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
                plugin.getDataConfig().set(rp + ".coins", body.getOrDefault("coins", 0));
                plugin.getDataConfig().set(rp + ".kit", body.getOrDefault("kit", ""));
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/playtime-rewards/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.playtime-rewards")) return;
            String id = ctx.pathParam("id");
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("playtime_rewards." + id, null);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== MOTD EDITOR API ==========
        app.get("/api/motd", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.motd")) return;
            Map<String, Object> res = new HashMap<>();
            res.put("line1", plugin.getDataConfig().getString("motd.line1", "A Minecraft Server"));
            res.put("line2", plugin.getDataConfig().getString("motd.line2", ""));
            res.put("maxPlayers", plugin.getDataConfig().getInt("motd.maxPlayers", 20));
            ctx.json(res);
        });

        app.post("/api/motd", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.motd")) return;
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
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.enchantments")) return;
            // Return list of available custom enchantments
            List<Map<String, String>> enchants = new ArrayList<>();
            enchants.add(Map.of("name", "Timber", "description", "Breaks entire log columns when chopping trees"));
            enchants.add(Map.of("name", "Vein Miner", "description", "Breaks connected ores when mining"));
            enchants.add(Map.of("name", "Smelting Touch", "description", "Auto-smelts mined ores"));
            enchants.add(Map.of("name", "Telepathy", "description", "Sends block drops directly to inventory"));
            ctx.json(enchants);
        });

        app.post("/api/enchantments/apply", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.enchant")) return;
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

        // ========== DAILY LOGIN REWARDS ==========
        app.get("/api/daily-login", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.daily-login")) return;
            var data = plugin.getDataConfig();
            Map<String, Object> result = new HashMap<>();
            result.put("enabled", data.getBoolean("daily_login_enabled", false));
            result.put("baseXp", data.getInt("daily_login_base_xp", 10));
            result.put("streakBonus", data.getInt("daily_login_streak_bonus", 2));
            result.put("baseCoins", data.getInt("daily_login_base_coins", 0));
            result.put("streakCoins", data.getInt("daily_login_streak_coins", 0));
            List<Map<String, Object>> players = new ArrayList<>();
            if (data.contains("daily_login")) {
                for (String key : data.getConfigurationSection("daily_login").getKeys(false)) {
                    if (key.equals("enabled")) continue;
                    Map<String, Object> pData = new HashMap<>();
                    pData.put("uuid", key);
                    pData.put("streak", data.getInt("daily_login." + key + ".streak", 0));
                    pData.put("total", data.getInt("daily_login." + key + ".total", 0));
                    pData.put("last", data.getLong("daily_login." + key + ".last", 0));
                    // Try to find player name
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(java.util.UUID.fromString(key));
                    pData.put("name", op.getName() != null ? op.getName() : key);
                    players.add(pData);
                }
            }
            result.put("players", players);
            ctx.json(result);
        });

        app.post("/api/daily-login", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.daily-login")) return;
            var body = ctx.bodyAsClass(Map.class);
            var data = plugin.getDataConfig();
            if (body.containsKey("enabled")) data.set("daily_login_enabled", body.get("enabled"));
            if (body.containsKey("baseXp")) data.set("daily_login_base_xp", ((Number)body.get("baseXp")).intValue());
            if (body.containsKey("streakBonus")) data.set("daily_login_streak_bonus", ((Number)body.get("streakBonus")).intValue());
            if (body.containsKey("baseCoins")) data.set("daily_login_base_coins", ((Number)body.get("baseCoins")).intValue());
            if (body.containsKey("streakCoins")) data.set("daily_login_streak_coins", ((Number)body.get("streakCoins")).intValue());
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        // ========== AUCTION HOUSE ==========
        app.get("/api/auctions", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.auctions")) return;
            var data = plugin.getDataConfig();
            List<Map<String, Object>> auctions = new ArrayList<>();
            if (data.contains("auctions")) {
                for (String id : data.getConfigurationSection("auctions").getKeys(false)) {
                    String path = "auctions." + id;
                    Map<String, Object> a = new HashMap<>();
                    a.put("id", id);
                    a.put("item", data.getString(path + ".item", "DIRT"));
                    a.put("amount", data.getInt(path + ".amount", 1));
                    a.put("sellerName", data.getString(path + ".sellerName", "Unknown"));
                    a.put("currentBid", data.getInt(path + ".currentBid", 0));
                    a.put("highBidderName", data.getString(path + ".highBidderName", "None"));
                    a.put("endTime", data.getLong(path + ".endTime", 0));
                    a.put("bidIncrement", data.getInt(path + ".bidIncrement", 5));
                    auctions.add(a);
                }
            }
            ctx.json(auctions);
        });

        app.post("/api/auctions", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.auctions")) return;
            var body = ctx.bodyAsClass(Map.class);
            var data = plugin.getDataConfig();
            String id = "auction_" + System.currentTimeMillis();
            String path = "auctions." + id;
            data.set(path + ".item", body.getOrDefault("item", "DIRT"));
            data.set(path + ".amount", ((Number)body.getOrDefault("amount", 1)).intValue());
            data.set(path + ".sellerName", body.getOrDefault("sellerName", "Server"));
            data.set(path + ".seller", "server");
            data.set(path + ".currentBid", ((Number)body.getOrDefault("startBid", 0)).intValue());
            data.set(path + ".bidIncrement", ((Number)body.getOrDefault("bidIncrement", 5)).intValue());
            data.set(path + ".highBidderName", "None");
            int durationMinutes = ((Number)body.getOrDefault("duration", 60)).intValue();
            data.set(path + ".endTime", System.currentTimeMillis() + (long)durationMinutes * 60000);
            plugin.saveDataFile();
            ctx.json(Map.of("success", true, "id", id));
        });

        app.delete("/api/auctions/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.auctions")) return;
            plugin.getDataConfig().set("auctions." + ctx.pathParam("id"), null);
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        // ========== NICKNAMES ==========
        app.get("/api/nicknames", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.nicknames")) return;
            var data = plugin.getDataConfig();
            List<Map<String, Object>> nicks = new ArrayList<>();
            if (data.contains("nicknames")) {
                for (String uuid : data.getConfigurationSection("nicknames").getKeys(false)) {
                    Map<String, Object> n = new HashMap<>();
                    n.put("uuid", uuid);
                    n.put("nick", data.getString("nicknames." + uuid, ""));
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
                    n.put("name", op.getName() != null ? op.getName() : uuid);
                    nicks.add(n);
                }
            }
            ctx.json(nicks);
        });

        app.post("/api/nicknames", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.nick")) return;
            var body = ctx.bodyAsClass(Map.class);
            String playerName = (String) body.get("player");
            String nick = (String) body.get("nick");
            Player target = Bukkit.getPlayer(playerName);
            if (target != null) {
                if (nick == null || nick.isEmpty()) {
                    plugin.getDataConfig().set("nicknames." + target.getUniqueId(), null);
                    target.setDisplayName(target.getName());
                } else {
                    plugin.getDataConfig().set("nicknames." + target.getUniqueId(), nick);
                    target.setDisplayName(ChatColor.translateAlternateColorCodes('&', nick));
                }
                plugin.saveDataFile();
                ctx.json(Map.of("success", true));
            } else {
                ctx.status(404).json(Map.of("error", "Player not online"));
            }
        });

        // ========== CHAT TAGS ==========
        app.get("/api/chat-tags", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.chat-tags")) return;
            var data = plugin.getDataConfig();
            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> tags = new ArrayList<>();
            if (data.contains("chat_tags")) {
                for (String uuid : data.getConfigurationSection("chat_tags").getKeys(false)) {
                    Map<String, Object> t = new HashMap<>();
                    t.put("uuid", uuid);
                    t.put("tag", data.getString("chat_tags." + uuid, ""));
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
                    t.put("name", op.getName() != null ? op.getName() : uuid);
                    tags.add(t);
                }
            }
            result.put("tags", tags);
            List<String> available = data.getStringList("available_tags");
            result.put("available", available);
            ctx.json(result);
        });

        app.post("/api/chat-tags", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.chat-tags")) return;
            var body = ctx.bodyAsClass(Map.class);
            String action = (String) body.getOrDefault("action", "set");
            if (action.equals("add_available")) {
                String tag = (String) body.get("tag");
                List<String> available = plugin.getDataConfig().getStringList("available_tags");
                available.add(tag);
                plugin.getDataConfig().set("available_tags", available);
                plugin.saveDataFile();
            } else if (action.equals("remove_available")) {
                String tag = (String) body.get("tag");
                List<String> available = plugin.getDataConfig().getStringList("available_tags");
                available.remove(tag);
                plugin.getDataConfig().set("available_tags", available);
                plugin.saveDataFile();
            } else {
                String playerName = (String) body.get("player");
                String tag = (String) body.getOrDefault("tag", "");
                Player target = Bukkit.getPlayer(playerName);
                if (target != null) {
                    if (tag.isEmpty()) {
                        plugin.getDataConfig().set("chat_tags." + target.getUniqueId(), null);
                    } else {
                        plugin.getDataConfig().set("chat_tags." + target.getUniqueId(), tag);
                    }
                    plugin.saveDataFile();
                }
            }
            ctx.json(Map.of("success", true));
        });

        // ========== SERVER RULES ==========
        app.get("/api/rules", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.rules")) return;
            List<String> rules = plugin.getDataConfig().getStringList("server_rules");
            ctx.json(Map.of("rules", rules));
        });

        app.post("/api/rules", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.rules")) return;
            var body = ctx.bodyAsClass(Map.class);
            List<String> rules = (List<String>) body.get("rules");
            plugin.getDataConfig().set("server_rules", rules);
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        // ========== PLAYER WARPS ==========
        app.get("/api/player-warps", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.player-warps")) return;
            var data = plugin.getDataConfig();
            Map<String, Object> result = new HashMap<>();
            result.put("cost", data.getInt("pwarp_cost", 5));
            result.put("max", data.getInt("pwarp_max", 3));
            List<Map<String, Object>> warps = new ArrayList<>();
            if (data.contains("pwarps")) {
                for (String id : data.getConfigurationSection("pwarps").getKeys(false)) {
                    String path = "pwarps." + id;
                    Map<String, Object> w = new HashMap<>();
                    w.put("id", id);
                    w.put("name", data.getString(path + ".name", id));
                    w.put("ownerName", data.getString(path + ".ownerName", "Unknown"));
                    w.put("visits", data.getInt(path + ".visits", 0));
                    w.put("world", data.getString(path + ".world", "world"));
                    w.put("x", data.getDouble(path + ".x"));
                    w.put("y", data.getDouble(path + ".y"));
                    w.put("z", data.getDouble(path + ".z"));
                    warps.add(w);
                }
            }
            result.put("warps", warps);
            ctx.json(result);
        });

        app.post("/api/player-warps/settings", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.player-warps")) return;
            var body = ctx.bodyAsClass(Map.class);
            if (body.containsKey("cost")) plugin.getDataConfig().set("pwarp_cost", ((Number)body.get("cost")).intValue());
            if (body.containsKey("max")) plugin.getDataConfig().set("pwarp_max", ((Number)body.get("max")).intValue());
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/player-warps/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.player-warps")) return;
            plugin.getDataConfig().set("pwarps." + ctx.pathParam("id"), null);
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        // ========== CUSTOM RECIPES ==========
        app.get("/api/custom-recipes", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.custom-recipes")) return;
            var data = plugin.getDataConfig();
            List<Map<String, Object>> recipes = new ArrayList<>();
            if (data.contains("custom_recipes")) {
                for (String id : data.getConfigurationSection("custom_recipes").getKeys(false)) {
                    String path = "custom_recipes." + id;
                    Map<String, Object> r = new HashMap<>();
                    r.put("id", id);
                    r.put("result", data.getString(path + ".result", "DIAMOND"));
                    r.put("resultAmount", data.getInt(path + ".resultAmount", 1));
                    r.put("ingredients", data.getStringList(path + ".ingredients"));
                    r.put("name", data.getString(path + ".name", id));
                    recipes.add(r);
                }
            }
            ctx.json(recipes);
        });

        app.post("/api/custom-recipes", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.custom-recipes")) return;
            var body = ctx.bodyAsClass(Map.class);
            var data = plugin.getDataConfig();
            String id = "recipe_" + System.currentTimeMillis();
            String path = "custom_recipes." + id;
            data.set(path + ".name", body.getOrDefault("name", id));
            data.set(path + ".result", body.getOrDefault("result", "DIAMOND"));
            data.set(path + ".resultAmount", ((Number)body.getOrDefault("resultAmount", 1)).intValue());
            data.set(path + ".ingredients", body.get("ingredients"));
            plugin.saveDataFile();
            // Register the recipe in-game
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Material resultMat = Material.valueOf(((String) body.getOrDefault("result", "DIAMOND")).toUpperCase());
                    org.bukkit.inventory.ItemStack resultItem = new org.bukkit.inventory.ItemStack(resultMat, ((Number)body.getOrDefault("resultAmount", 1)).intValue());
                    org.bukkit.inventory.ShapelessRecipe recipe = new org.bukkit.inventory.ShapelessRecipe(
                        new org.bukkit.NamespacedKey(plugin, id), resultItem);
                    List<String> ingredients = (List<String>) body.get("ingredients");
                    if (ingredients != null) {
                        for (String ing : ingredients) {
                            try { recipe.addIngredient(Material.valueOf(ing.toUpperCase())); } catch (Exception ignored) {}
                        }
                    }
                    Bukkit.addRecipe(recipe);
                } catch (Exception ignored) {}
            });
            ctx.json(Map.of("success", true, "id", id));
        });

        app.delete("/api/custom-recipes/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.custom-recipes")) return;
            plugin.getDataConfig().set("custom_recipes." + ctx.pathParam("id"), null);
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        // ========== PVP STATS ==========
        app.get("/api/pvp-stats", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.pvp-stats")) return;
            var data = plugin.getDataConfig();
            List<Map<String, Object>> stats = new ArrayList<>();
            if (data.contains("pvpstats")) {
                for (String uuid : data.getConfigurationSection("pvpstats").getKeys(false)) {
                    String path = "pvpstats." + uuid;
                    Map<String, Object> s = new HashMap<>();
                    s.put("uuid", uuid);
                    s.put("kills", data.getInt(path + ".kills", 0));
                    s.put("deaths", data.getInt(path + ".deaths", 0));
                    s.put("streak", data.getInt(path + ".streak", 0));
                    s.put("bestStreak", data.getInt(path + ".best_streak", 0));
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
                    s.put("name", op.getName() != null ? op.getName() : uuid);
                    stats.add(s);
                }
            }
            // Sort by kills desc
            stats.sort((a, b) -> ((Integer) b.get("kills")).compareTo((Integer) a.get("kills")));
            ctx.json(stats);
        });

        // ========== ACHIEVEMENTS ==========
        app.get("/api/achievements", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.achievements")) return;
            var data = plugin.getDataConfig();
            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> defs = new ArrayList<>();
            if (data.contains("achievement_defs")) {
                for (String key : data.getConfigurationSection("achievement_defs").getKeys(false)) {
                    String path = "achievement_defs." + key;
                    Map<String, Object> a = new HashMap<>();
                    a.put("id", key);
                    a.put("name", data.getString(path + ".name", key));
                    a.put("description", data.getString(path + ".description", ""));
                    a.put("title", data.getString(path + ".title", ""));
                    a.put("xpReward", data.getInt(path + ".xp_reward", 0));
                    defs.add(a);
                }
            }
            result.put("definitions", defs);
            // Unlocked per player
            List<Map<String, Object>> unlocked = new ArrayList<>();
            if (data.contains("achievements")) {
                for (String uuid : data.getConfigurationSection("achievements").getKeys(false)) {
                    Map<String, Object> pu = new HashMap<>();
                    pu.put("uuid", uuid);
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
                    pu.put("name", op.getName() != null ? op.getName() : uuid);
                    List<String> achList = new ArrayList<>();
                    if (data.contains("achievements." + uuid)) {
                        for (String achKey : data.getConfigurationSection("achievements." + uuid).getKeys(false)) {
                            if (data.getBoolean("achievements." + uuid + "." + achKey, false)) achList.add(achKey);
                        }
                    }
                    pu.put("achievements", achList);
                    unlocked.add(pu);
                }
            }
            result.put("unlocked", unlocked);
            ctx.json(result);
        });

        app.post("/api/achievements", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.achievements")) return;
            var body = ctx.bodyAsClass(Map.class);
            var data = plugin.getDataConfig();
            String id = (String) body.getOrDefault("id", "ach_" + System.currentTimeMillis());
            String path = "achievement_defs." + id;
            data.set(path + ".name", body.getOrDefault("name", id));
            data.set(path + ".description", body.getOrDefault("description", ""));
            data.set(path + ".title", body.getOrDefault("title", ""));
            data.set(path + ".xp_reward", ((Number)body.getOrDefault("xpReward", 0)).intValue());
            plugin.saveDataFile();
            ctx.json(Map.of("success", true, "id", id));
        });

        app.delete("/api/achievements/{id}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.achievements")) return;
            plugin.getDataConfig().set("achievement_defs." + ctx.pathParam("id"), null);
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        // ========== DUELS ==========
        app.get("/api/duels", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.duels")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                var data = plugin.getDataConfig();
                Map<String, Object> result = new HashMap<>();
                // Active duels from plugin memory
                List<Map<String, Object>> active = new ArrayList<>();
                Set<UUID> seen = new HashSet<>();
                for (Map.Entry<java.util.UUID, java.util.UUID> entry : plugin.activeDuels.entrySet()) {
                    if (seen.contains(entry.getKey())) continue;
                    seen.add(entry.getKey());
                    seen.add(entry.getValue());
                    org.bukkit.OfflinePlayer p1 = Bukkit.getOfflinePlayer(entry.getKey());
                    org.bukkit.OfflinePlayer p2 = Bukkit.getOfflinePlayer(entry.getValue());
                    Map<String, Object> d = new HashMap<>();
                    d.put("player1", p1.getName() != null ? p1.getName() : entry.getKey().toString());
                    d.put("player2", p2.getName() != null ? p2.getName() : entry.getValue().toString());
                    d.put("wager", plugin.duelWagers.getOrDefault(entry.getKey(), 0));
                    active.add(d);
                }
                result.put("active", active);
                // Duel stats from pvpstats
                List<Map<String, Object>> stats = new ArrayList<>();
                if (data.contains("pvpstats")) {
                    for (String uuidStr : data.getConfigurationSection("pvpstats").getKeys(false)) {
                        Map<String, Object> s = new HashMap<>();
                        s.put("uuid", uuidStr);
                        s.put("kills", data.getInt("pvpstats." + uuidStr + ".kills", 0));
                        s.put("deaths", data.getInt("pvpstats." + uuidStr + ".deaths", 0));
                        try {
                            UUID u = UUID.fromString(uuidStr);
                            String name = data.getString("last_seen_name." + uuidStr, Bukkit.getOfflinePlayer(u).getName());
                            s.put("name", name != null ? name : uuidStr);
                        } catch (Exception e) { s.put("name", uuidStr); }
                        stats.add(s);
                    }
                }
                result.put("stats", stats);
                return result;
            });
            ctx.json(future.get());
        });

        // ========== FIRST JOIN / WELCOME ==========
        app.get("/api/welcome", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.welcome")) return;
            var data = plugin.getDataConfig();
            Map<String, Object> result = new HashMap<>();
            result.put("message", data.getString("welcome_message", "&6Welcome to the server, &e{player}&6!"));
            result.put("broadcast", data.getBoolean("welcome_broadcast", true));
            result.put("starterItems", data.getStringList("welcome_starter_items"));
            // First join history
            List<Map<String, Object>> firstJoins = new ArrayList<>();
            if (data.contains("first_join")) {
                for (String uuid : data.getConfigurationSection("first_join").getKeys(false)) {
                    Map<String, Object> fj = new HashMap<>();
                    fj.put("uuid", uuid);
                    fj.put("time", data.getLong("first_join." + uuid, 0));
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
                    fj.put("name", op.getName() != null ? op.getName() : uuid);
                    firstJoins.add(fj);
                }
            }
            result.put("firstJoins", firstJoins);
            ctx.json(result);
        });

        app.post("/api/welcome", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.welcome")) return;
            var body = ctx.bodyAsClass(Map.class);
            var data = plugin.getDataConfig();
            if (body.containsKey("message")) data.set("welcome_message", body.get("message"));
            if (body.containsKey("broadcast")) data.set("welcome_broadcast", body.get("broadcast"));
            if (body.containsKey("starterItems")) data.set("welcome_starter_items", body.get("starterItems"));
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        // ========== INACTIVE PLAYER ALERTS ==========
        app.get("/api/inactive-players", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.inactive-players")) return;
            var data = plugin.getDataConfig();
            int thresholdDays = data.getInt("inactive_threshold_days", 14);
            long thresholdMs = (long) thresholdDays * 86400000L;
            long now = System.currentTimeMillis();
            List<Map<String, Object>> inactive = new ArrayList<>();
            if (data.contains("last_seen")) {
                for (String uuid : data.getConfigurationSection("last_seen").getKeys(false)) {
                    long lastSeen = data.getLong("last_seen." + uuid, 0);
                    long daysSince = (now - lastSeen) / 86400000L;
                    if ((now - lastSeen) > thresholdMs) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("uuid", uuid);
                        p.put("name", data.getString("last_seen_name." + uuid, uuid));
                        p.put("lastSeen", lastSeen);
                        p.put("daysSince", daysSince);
                        inactive.add(p);
                    }
                }
            }
            inactive.sort((a, b) -> Long.compare((Long) b.get("daysSince"), (Long) a.get("daysSince")));
            ctx.json(Map.of("players", inactive, "thresholdDays", thresholdDays));
        });

        app.post("/api/inactive-players/settings", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.inactive-players")) return;
            var body = ctx.bodyAsClass(Map.class);
            if (body.containsKey("thresholdDays")) plugin.getDataConfig().set("inactive_threshold_days", ((Number)body.get("thresholdDays")).intValue());
            plugin.saveDataFile();
            ctx.json(Map.of("success", true));
        });

        // ========== SCHEDULED ANNOUNCEMENTS ==========
        app.get("/api/announcements", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.announcements")) return;
            var data = plugin.getDataConfig();
            Map<String, Object> result = new HashMap<>();
            result.put("enabled", data.getBoolean("announcements.enabled", false));
            result.put("intervalMinutes", data.getInt("announcements.interval_minutes", 5));
            result.put("prefix", data.getString("announcements.prefix", "&6[&eAnnouncement&6]&r "));
            result.put("messages", data.getStringList("announcements.messages"));
            ctx.json(result);
        });

        app.post("/api/announcements", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.announcements")) return;
            var body = ctx.bodyAsClass(Map.class);
            Bukkit.getScheduler().runTask(plugin, () -> {
                var data = plugin.getDataConfig();
                if (body.containsKey("enabled")) data.set("announcements.enabled", body.get("enabled"));
                if (body.containsKey("intervalMinutes")) data.set("announcements.interval_minutes", ((Number)body.get("intervalMinutes")).intValue());
                if (body.containsKey("prefix")) data.set("announcements.prefix", body.get("prefix"));
                if (body.containsKey("messages")) data.set("announcements.messages", body.get("messages"));
                plugin.saveDataFile();
                plugin.restartScheduledAnnouncements();
            });
            ctx.json(Map.of("success", true));
        });

        // ========== ONE-TIME SCHEDULED ANNOUNCEMENTS ==========
        app.get("/api/announcements/scheduled", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.announcements")) return;
            var data = plugin.getDataConfig();
            List<Map<?, ?>> raw = (List<Map<?, ?>>) data.getList("announcements.scheduled", new ArrayList<>());
            List<Map<String, Object>> announcements = new ArrayList<>();
            int i = 0;
            for (Map<?, ?> r : raw) {
                Map<String, Object> m = new HashMap<>();
                for (Map.Entry<?, ?> entry : r.entrySet()) m.put(String.valueOf(entry.getKey()), entry.getValue());
                m.put("index", i++);
                announcements.add(m);
            }
            ctx.json(Map.of("announcements", announcements));
        });

        app.post("/api/announcements/schedule", ctx -> {
            if (!auth(ctx)) return;
            if (!hasPermission(ctx.header("Authorization"), "webapp.manage.announcements")) { ctx.status(403).result("Forbidden"); return; }
            var body = ctx.bodyAsClass(Map.class);
            String message = (String) body.get("message");
            String time = (String) body.get("time");
            if (message == null || time == null || message.isEmpty() || time.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing message or time"));
                return;
            }
            try {
                java.time.LocalDateTime.parse(time);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "Invalid time format. Use ISO-8601 (yyyy-MM-ddTHH:mm)"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                var data = plugin.getDataConfig();
                List<Map<?, ?>> raw = (List<Map<?, ?>>) data.getList("announcements.scheduled", new ArrayList<>());
                List<Map<String, Object>> scheduled = new ArrayList<>();
                for (Map<?, ?> r : raw) {
                    Map<String, Object> m = new HashMap<>();
                    for (Map.Entry<?, ?> entry : r.entrySet()) m.put(String.valueOf(entry.getKey()), entry.getValue());
                    scheduled.add(m);
                }
                Map<String, Object> entry = new HashMap<>();
                entry.put("message", message);
                entry.put("time", time);
                entry.put("sent", false);
                scheduled.add(entry);
                data.set("announcements.scheduled", scheduled);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/announcements/schedule/{index}", ctx -> {
            if (!auth(ctx)) return;
            if (!hasPermission(ctx.header("Authorization"), "webapp.manage.announcements")) { ctx.status(403).result("Forbidden"); return; }
            int index = Integer.parseInt(ctx.pathParam("index"));
            Bukkit.getScheduler().runTask(plugin, () -> {
                var data = plugin.getDataConfig();
                List<Map<?, ?>> raw = (List<Map<?, ?>>) data.getList("announcements.scheduled", new ArrayList<>());
                List<Map<String, Object>> scheduled = new ArrayList<>();
                for (Map<?, ?> r : raw) {
                    Map<String, Object> m = new HashMap<>();
                    for (Map.Entry<?, ?> entry : r.entrySet()) m.put(String.valueOf(entry.getKey()), entry.getValue());
                    scheduled.add(m);
                }
                if (index >= 0 && index < scheduled.size()) {
                    scheduled.remove(index);
                    data.set("announcements.scheduled", scheduled);
                    plugin.saveDataFile();
                }
            });
            ctx.json(Map.of("success", true));
        });

        // ========== COMMAND SCHEDULER ==========
        app.get("/api/scheduler", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.announcements")) return;
            var data = plugin.getDataConfig();
            List<Map<?, ?>> raw = (List<Map<?, ?>>) data.getList("scheduler.commands", new ArrayList<>());
            List<Map<String, Object>> commands = new ArrayList<>();
            int i = 0;
            for (Map<?, ?> r : raw) {
                Map<String, Object> m = new HashMap<>();
                for (Map.Entry<?, ?> entry : r.entrySet()) m.put(String.valueOf(entry.getKey()), entry.getValue());
                m.put("index", i++);
                commands.add(m);
            }
            ctx.json(Map.of("tasks", commands));
        });

        app.post("/api/scheduler", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.announcements")) return;
            var body = ctx.bodyAsClass(Map.class);
            String command = (String) body.get("command");
            String time = (String) body.get("time");
            if (command == null || time == null || command.isEmpty() || time.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing command or time"));
                return;
            }
            try {
                java.time.LocalDateTime.parse(time);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "Invalid time format. Use ISO-8601 (yyyy-MM-ddTHH:mm)"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                var data = plugin.getDataConfig();
                List<Map<?, ?>> raw = (List<Map<?, ?>>) data.getList("scheduler.commands", new ArrayList<>());
                List<Map<String, Object>> scheduled = new ArrayList<>();
                for (Map<?, ?> r : raw) {
                    Map<String, Object> m = new HashMap<>();
                    for (Map.Entry<?, ?> entry : r.entrySet()) m.put(String.valueOf(entry.getKey()), entry.getValue());
                    scheduled.add(m);
                }
                Map<String, Object> entry = new HashMap<>();
                entry.put("command", command);
                entry.put("time", time);
                entry.put("sent", false);
                scheduled.add(entry);
                data.set("scheduler.commands", scheduled);
                plugin.saveDataFile();
            });
            ctx.json(Map.of("success", true));
        });

        app.delete("/api/scheduler/{index}", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.announcements")) return;
            int index = Integer.parseInt(ctx.pathParam("index"));
            Bukkit.getScheduler().runTask(plugin, () -> {
                var data = plugin.getDataConfig();
                List<Map<?, ?>> raw = (List<Map<?, ?>>) data.getList("scheduler.commands", new ArrayList<>());
                List<Map<String, Object>> scheduled = new ArrayList<>();
                for (Map<?, ?> r : raw) {
                    Map<String, Object> m = new HashMap<>();
                    for (Map.Entry<?, ?> entry : r.entrySet()) m.put(String.valueOf(entry.getKey()), entry.getValue());
                    scheduled.add(m);
                }
                if (index >= 0 && index < scheduled.size()) {
                    scheduled.remove(index);
                    data.set("scheduler.commands", scheduled);
                    plugin.saveDataFile();
                }
            });
            ctx.json(Map.of("success", true));
        });

        // ========== PLAYER REPUTATION ==========
        app.get("/api/reputation", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.reputation")) return;
            String playerParam = ctx.queryParam("player");
            var data = plugin.getDataConfig();

            // If a specific player is requested, return their details
            if (playerParam != null && !playerParam.isEmpty()) {
                Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    UUID uuid = Bukkit.getOfflinePlayer(playerParam).getUniqueId();
                    int warnings = data.getStringList("warnings." + uuid).size();
                    boolean banned = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(playerParam);
                    int bans = data.getInt("bans_count." + uuid, 0) + (banned ? 1 : 0);
                    long playtime = plugin.getPlaytimeHours(uuid);
                    int score = (int)(playtime * 2) - (warnings * 15) - (bans * 30);
                    Map<String, Object> rep = new HashMap<>();
                    rep.put("name", playerParam);
                    rep.put("score", score);
                    rep.put("warnings", warnings);
                    rep.put("bans", bans);
                    rep.put("playtime", playtime);
                    return rep;
                });
                ctx.json(future.get());
                return;
            }

            // Return reputation for all known players
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> players = new ArrayList<>();
                Set<String> seen = new HashSet<>();

                // Online players
                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID uuid = p.getUniqueId();
                    String name = p.getName();
                    if (seen.contains(name)) continue;
                    seen.add(name);
                    int warnings = data.getStringList("warnings." + uuid).size();
                    boolean banned = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(name);
                    int bans = data.getInt("bans_count." + uuid, 0) + (banned ? 1 : 0);
                    long playtime = plugin.getPlaytimeHours(uuid);
                    int score = (int)(playtime * 2) - (warnings * 15) - (bans * 30);
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", name);
                    m.put("score", score);
                    m.put("warnings", warnings);
                    m.put("bans", bans);
                    players.add(m);
                }

                // Offline players with warnings
                if (data.getConfigurationSection("warnings") != null) {
                    for (String key : data.getConfigurationSection("warnings").getKeys(false)) {
                        try {
                            UUID uuid = UUID.fromString(key);
                            String name = data.getString("last_seen_name." + key, Bukkit.getOfflinePlayer(uuid).getName());
                            if (name == null || seen.contains(name)) continue;
                            seen.add(name);
                            int warnings = data.getStringList("warnings." + uuid).size();
                            boolean banned = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(name);
                            int bans = data.getInt("bans_count." + uuid, 0) + (banned ? 1 : 0);
                            long playtime = plugin.getPlaytimeHours(uuid);
                            int score = (int)(playtime * 2) - (warnings * 15) - (bans * 30);
                            Map<String, Object> m = new HashMap<>();
                            m.put("name", name);
                            m.put("score", score);
                            m.put("warnings", warnings);
                            m.put("bans", bans);
                            players.add(m);
                        } catch (Exception ignored) {}
                    }
                }

                // Offline players with playtime but no warnings
                if (data.getConfigurationSection("playtime") != null) {
                    for (String key : data.getConfigurationSection("playtime").getKeys(false)) {
                        try {
                            UUID uuid = UUID.fromString(key);
                            String name = data.getString("last_seen_name." + key, Bukkit.getOfflinePlayer(uuid).getName());
                            if (name == null || seen.contains(name)) continue;
                            seen.add(name);
                            int warnings = data.getStringList("warnings." + uuid).size();
                            boolean banned = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(name);
                            int bans = data.getInt("bans_count." + uuid, 0) + (banned ? 1 : 0);
                            long playtime = plugin.getPlaytimeHours(uuid);
                            int score = (int)(playtime * 2) - (warnings * 15) - (bans * 30);
                            Map<String, Object> m = new HashMap<>();
                            m.put("name", name);
                            m.put("score", score);
                            m.put("warnings", warnings);
                            m.put("bans", bans);
                            players.add(m);
                        } catch (Exception ignored) {}
                    }
                }

                players.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
                return players;
            });
            ctx.json(future.get());
        });

        // ========== AFK MANAGER ==========
        app.get("/api/afk", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.afk")) return;
            Future<Map<String, Object>> future2 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> result = new HashMap<>();
                result.put("timeout", plugin.getAfkTimeoutMinutes());
                result.put("enabled", plugin.getDataConfig().getBoolean("afk_autokick_enabled", true));

                List<Map<String, Object>> afkPlayers = new ArrayList<>();
                long now = System.currentTimeMillis();
                int timeoutMinutes = plugin.getAfkTimeoutMinutes();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Long lastAct = plugin.getLastActivity().get(p.getUniqueId());
                    if (lastAct == null) lastAct = now;
                    long idleMs = now - lastAct;
                    long idleMinutes = idleMs / 60000;
                    if (idleMinutes >= 1) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("name", p.getName());
                        m.put("idleTime", idleMinutes);
                        long lastActTime = lastAct;
                        m.put("lastAction", new SimpleDateFormat("HH:mm:ss").format(new Date(lastActTime)));
                        afkPlayers.add(m);
                    }
                }
                afkPlayers.sort((a, b) -> Long.compare((long) b.get("idleTime"), (long) a.get("idleTime")));
                result.put("players", afkPlayers);
                return result;
            });
            ctx.json(future2.get());
        });

        app.post("/api/afk/settings", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.afk")) return;
            var body = ctx.bodyAsClass(Map.class);
            
            // Log request to console for debugging
            Bukkit.getLogger().info("[WebAdmin] Received AFK settings update: " + body);
            
            if (body.containsKey("timeout")) {
                int timeout = Integer.parseInt(body.get("timeout").toString());
                plugin.setAfkTimeoutMinutes(timeout);
                plugin.logAction("WebAdmin", "updated AFK timeout", timeout + "m");
            }

            if (body.containsKey("enabled")) {
                boolean enabled = Boolean.parseBoolean(body.get("enabled").toString());
                plugin.getDataConfig().set("afk_autokick_enabled", enabled);
                plugin.saveDataFile();
                plugin.logAction("WebAdmin", (enabled ? "enabled" : "disabled") + " AFK autokick", "");
            }

            ctx.json(Map.of("success", true));
        });

        // ========== PLAYER ANALYTICS ==========
        app.get("/api/analytics/players", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.analytics")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                var data = plugin.getDataConfig();
                List<Map<String, Object>> analytics = new ArrayList<>();
                Set<String> seen = new HashSet<>();

                // Gather all players with playtime data
                var playtimeSection = data.getConfigurationSection("playtime");
                if (playtimeSection != null) {
                    for (String uuidStr : playtimeSection.getKeys(false)) {
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            String name = data.getString("last_seen_name." + uuidStr, Bukkit.getOfflinePlayer(uuid).getName());
                            if (name == null || seen.contains(name)) continue;
                            seen.add(name);
                            long minutes = data.getLong("playtime." + uuidStr, 0);
                            long playtimeHours = minutes / 60;
                            long lastSeen = data.getLong("last_seen." + uuidStr, 0);
                            List<String> sessionList = data.getStringList("sessions." + uuidStr);
                            int sessionCount = 0;
                            for (String s : sessionList) {
                                if (s.startsWith("LOGIN")) sessionCount++;
                            }
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("player", name);
                            entry.put("playtimeHours", playtimeHours);
                            entry.put("sessions", sessionCount);
                            entry.put("lastSeen", lastSeen);
                            analytics.add(entry);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }

                // Also include online players that may not have playtime yet
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (seen.contains(p.getName())) continue;
                    seen.add(p.getName());
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("player", p.getName());
                    entry.put("playtimeHours", 0);
                    entry.put("sessions", 1);
                    entry.put("lastSeen", System.currentTimeMillis());
                    analytics.add(entry);
                }

                // Sort by playtime descending
                analytics.sort((a, b) -> Long.compare((long) b.get("playtimeHours"), (long) a.get("playtimeHours")));

                return Map.of("analytics", (Object) analytics);
            });
            ctx.json(future.get());
        });

        // ========== EVENTS MANAGER ==========
        app.get("/api/events/active", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.events")) return;
            var data = plugin.getDataConfig();
            List<Map<String, Object>> events = new ArrayList<>();
            var section = data.getConfigurationSection("events.active");
            if (section != null) {
                for (String name : section.getKeys(false)) {
                    Map<String, Object> e = new HashMap<>();
                    e.put("name", name);
                    e.put("startTime", data.getString("events.active." + name + ".startTime", "-"));
                    e.put("playersOnline", Bukkit.getOnlinePlayers().size());
                    events.add(e);
                }
            }
            ctx.json(Map.of("events", events));
        });

        app.post("/api/events/start", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.events")) return;
            var body = ctx.bodyAsClass(Map.class);
            String eventName = (String) body.get("event");
            if (eventName == null || eventName.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing event name"));
                return;
            }
            var data = plugin.getDataConfig();
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            data.set("events.active." + eventName + ".startTime", timestamp);
            data.set("events.active." + eventName + ".admin", "WebAdmin");
            plugin.saveDataFile();

            // Broadcast in-game and start event effects
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.GREEN + "The " +
                    ChatColor.YELLOW + eventName + ChatColor.GREEN + " event has started! " +
                    ChatColor.GOLD + "★");
                plugin.startEventEffect(eventName);
            });

            ctx.json(Map.of("status", "success"));
        });

        app.post("/api/events/stop", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.events")) return;
            var body = ctx.bodyAsClass(Map.class);
            String eventName = (String) body.get("event");
            if (eventName == null || eventName.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing event name"));
                return;
            }
            var data = plugin.getDataConfig();
            String startTime = data.getString("events.active." + eventName + ".startTime", "");
            String admin = data.getString("events.active." + eventName + ".admin", "WebAdmin");

            // Calculate duration
            String duration = "-";
            if (!startTime.isEmpty()) {
                try {
                    long startMs = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(startTime).getTime();
                    long elapsed = System.currentTimeMillis() - startMs;
                    long hours = elapsed / 3600000;
                    long minutes = (elapsed % 3600000) / 60000;
                    duration = (hours > 0 ? hours + "h " : "") + minutes + "m";
                } catch (Exception ignored) {}
            }

            // Add to history
            List<Map<?, ?>> history = (List<Map<?, ?>>) data.getList("events.history", new ArrayList<>());
            List<Map<String, Object>> historyList = new ArrayList<>();
            for (Map<?, ?> h : history) {
                Map<String, Object> m = new HashMap<>();
                for (Map.Entry<?, ?> entry : h.entrySet()) m.put(String.valueOf(entry.getKey()), entry.getValue());
                historyList.add(m);
            }
            Map<String, Object> record = new HashMap<>();
            record.put("event", eventName);
            record.put("admin", admin);
            record.put("startTime", startTime);
            record.put("duration", duration);
            historyList.add(0, record);
            data.set("events.history", historyList);

            // Remove from active
            data.set("events.active." + eventName, null);
            plugin.saveDataFile();

            // Broadcast in-game and stop event effects
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.RED + "The " +
                    ChatColor.YELLOW + eventName + ChatColor.RED + " event has ended! " +
                    ChatColor.GOLD + "★");
                plugin.stopEventEffect(eventName);
            });

            ctx.json(Map.of("status", "success"));
        });

        app.get("/api/events/history", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.events")) return;
            var data = plugin.getDataConfig();
            List<Map<?, ?>> history = (List<Map<?, ?>>) data.getList("events.history", new ArrayList<>());
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<?, ?> h : history) {
                Map<String, Object> m = new HashMap<>();
                for (Map.Entry<?, ?> entry : h.entrySet()) m.put(String.valueOf(entry.getKey()), entry.getValue());
                result.add(m);
            }
            ctx.json(Map.of("history", result));
        });

        // ========== AUDIT LOG ==========
        app.get("/api/audit", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.audit")) return;
            String adminFilter = ctx.queryParam("admin");
            String actionFilter = ctx.queryParam("action");
            Future<Map<String, Object>> future3 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> history = plugin.getDataConfig().getStringList("action_history");
                List<Map<String, Object>> logs = new ArrayList<>();
                for (int i = history.size() - 1; i >= 0; i--) {
                    String entry = history.get(i);
                    // Format: "2026-03-06 01:42:45 | Actor action Target"
                    String[] parts = entry.split(" \\| ", 2);
                    if (parts.length < 2) continue;
                    String date = parts[0].trim();
                    String rest = parts[1].trim();
                    // Split rest into: admin, action, target (+ optional reason)
                    String[] tokens = rest.split(" ", 3);
                    String admin = tokens.length > 0 ? tokens[0] : "Unknown";
                    String action = tokens.length > 1 ? tokens[1] : "unknown";
                    String target = tokens.length > 2 ? tokens[2] : "";
                    String reason = "";
                    // Extract reason if target contains parentheses
                    if (target.contains("(") && target.contains(")")) {
                        int start = target.indexOf('(');
                        reason = target.substring(start + 1, target.lastIndexOf(')'));
                        target = target.substring(0, start).trim();
                    }
                    if (adminFilter != null && !adminFilter.isEmpty() && !admin.toLowerCase().contains(adminFilter.toLowerCase())) continue;
                    if (actionFilter != null && !actionFilter.isEmpty() && !action.toLowerCase().contains(actionFilter.toLowerCase())) continue;
                    Map<String, Object> log = new HashMap<>();
                    log.put("date", date);
                    log.put("admin", admin);
                    log.put("action", action);
                    log.put("target", target);
                    log.put("reason", reason);
                    logs.add(log);
                    if (logs.size() >= 100) break;
                }
                return Map.of("logs", (Object) logs);
            });
            ctx.json(future3.get());
        });

        // --- PERMISSION GROUPS ---

        app.get("/api/groups", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.groups")) return;
            Future<Map<String, Object>> future4 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> groups = new ArrayList<>();
                var section = plugin.getDataConfig().getConfigurationSection("groups");
                if (section != null) {
                    plugin.getLogger().info("[DEBUG] groups section: " + section.getKeys(false));
                    for (String name : section.getKeys(false)) {
                        Map<String, Object> g = new HashMap<>();
                        g.put("name", name);
                        g.put("color", plugin.getDataConfig().getString("groups." + name + ".color", "#ffffff"));
                        g.put("prefix", plugin.getDataConfig().getString("groups." + name + ".prefix", ""));
                        g.put("permissions", plugin.getDataConfig().getStringList("groups." + name + ".permissions"));
                        List<String> memberUuids = plugin.getDataConfig().getStringList("groups." + name + ".members");
                        List<Map<String, String>> members = new ArrayList<>();
                        for (String uuid : memberUuids) {
                            Map<String, String> m = new HashMap<>();
                            m.put("uuid", uuid);
                            try {
                                String playerName = plugin.getDataConfig().getString("last_seen_name." + uuid,
                                    Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName());
                                m.put("name", playerName != null ? playerName : uuid);
                            } catch (Exception e) { m.put("name", uuid); }
                            members.add(m);
                        }
                        g.put("members", members);
                        groups.add(g);
                    }
                } else {
                    plugin.getLogger().info("[DEBUG] groups section is null");
                }
                return Map.of("groups", (Object) groups);
            });
            ctx.json(future4.get());
        });

        app.post("/api/groups/create", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = om.readValue(ctx.body(), Map.class);
            String name = (String) body.get("name");
            String color = (String) body.get("color");
            String prefix = (String) body.get("prefix");
            if (name == null || name.isBlank()) { ctx.status(400).json(Map.of("error", "Name required")); return; }
            name = name.replaceAll("[^a-zA-Z0-9_-]", "");
            if (name.isEmpty()) { ctx.status(400).json(Map.of("error", "Invalid name")); return; }
            String finalName = name;
            String finalColor = color != null ? color : "#ffffff";
            String finalPrefix = prefix != null ? prefix : "";
            Future<Map<String, Object>> future5 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (plugin.getDataConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group already exists");
                }
                plugin.getDataConfig().set("groups." + finalName + ".color", finalColor);
                plugin.getDataConfig().set("groups." + finalName + ".prefix", finalPrefix);
                plugin.getDataConfig().set("groups." + finalName + ".permissions", new ArrayList<String>());
                plugin.getDataConfig().set("groups." + finalName + ".members", new ArrayList<String>());
                plugin.saveDataFile();
                plugin.logAction("WebPanel", "group_create", finalName);
                return Map.of("success", (Object) true);
            });
            Map<String, Object> result = future5.get();
            if (result.containsKey("error")) { ctx.status(400); }
            ctx.json(result);
        });

        app.post("/api/groups/delete", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = om.readValue(ctx.body(), Map.class);
            String name = (String) body.get("name");
            if (name == null || name.isBlank()) { ctx.status(400).json(Map.of("error", "Name required")); return; }
            String finalName = name;
            Future<Map<String, Object>> future6 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (!plugin.getDataConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group not found");
                }
                plugin.getDataConfig().set("groups." + finalName, null);
                plugin.saveDataFile();
                plugin.refreshAllPermissions();
                plugin.logAction("WebPanel", "group_delete", finalName);
                return Map.of("success", (Object) true);
            });
            Map<String, Object> result = future6.get();
            if (result.containsKey("error")) { ctx.status(400); }
            ctx.json(result);
        });

        app.post("/api/groups/update", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = om.readValue(ctx.body(), Map.class);
            String name = (String) body.get("name");
            String color = (String) body.get("color");
            String prefix = (String) body.get("prefix");
            if (name == null || name.isBlank()) { ctx.status(400).json(Map.of("error", "Name required")); return; }
            String finalName = name;
            String finalColor = color;
            String finalPrefix = prefix;
            Future<Map<String, Object>> future7 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (!plugin.getDataConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group not found");
                }
                if (finalColor != null) plugin.getDataConfig().set("groups." + finalName + ".color", finalColor);
                if (finalPrefix != null) plugin.getDataConfig().set("groups." + finalName + ".prefix", finalPrefix);
                plugin.saveDataFile();
                plugin.logAction("WebPanel", "group_update", finalName);
                return Map.of("success", (Object) true);
            });
            Map<String, Object> result = future7.get();
            if (result.containsKey("error")) { ctx.status(400); }
            ctx.json(result);
        });

        app.post("/api/groups/permissions/add", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = om.readValue(ctx.body(), Map.class);
            String name = (String) body.get("name");
            String permission = (String) body.get("permission");
            if (name == null || permission == null || permission.isBlank()) { ctx.status(400).json(Map.of("error", "Name and permission required")); return; }
            String finalName = name;
            String finalPerm = permission.trim();
            Future<Map<String, Object>> future8 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (!plugin.getDataConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group not found");
                }
                List<String> perms = new ArrayList<>(plugin.getDataConfig().getStringList("groups." + finalName + ".permissions"));
                if (perms.contains(finalPerm)) return Map.of("error", (Object) "Permission already exists");
                perms.add(finalPerm);
                plugin.getDataConfig().set("groups." + finalName + ".permissions", perms);
                plugin.saveDataFile();
                plugin.refreshAllPermissions();
                plugin.logAction("WebPanel", "group_perm_add", finalName + " " + finalPerm);
                return Map.of("success", (Object) true);
            });
            Map<String, Object> result = future8.get();
            if (result.containsKey("error")) { ctx.status(400); }
            ctx.json(result);
        });

        app.post("/api/groups/permissions/remove", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = om.readValue(ctx.body(), Map.class);
            String name = (String) body.get("name");
            String permission = (String) body.get("permission");
            if (name == null || permission == null) { ctx.status(400).json(Map.of("error", "Name and permission required")); return; }
            String finalName = name;
            String finalPerm = permission.trim();
            Future<Map<String, Object>> future9 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> perms = new ArrayList<>(plugin.getDataConfig().getStringList("groups." + finalName + ".permissions"));
                perms.remove(finalPerm);
                plugin.getDataConfig().set("groups." + finalName + ".permissions", perms);
                plugin.saveDataFile();
                plugin.refreshAllPermissions();
                plugin.logAction("WebPanel", "group_perm_remove", finalName + " " + finalPerm);
                return Map.of("success", (Object) true);
            });
            ctx.json(future9.get());
        });

        app.post("/api/groups/members/add", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = om.readValue(ctx.body(), Map.class);
            String name = (String) body.get("name");
            String playerName = (String) body.get("player");
            if (name == null || playerName == null || playerName.isBlank()) { ctx.status(400).json(Map.of("error", "Group name and player required")); return; }
            String finalName = name;
            String finalPlayer = playerName.trim();
            Future<Map<String, Object>> future10 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (!plugin.getDataConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group not found");
                }
                // Resolve player UUID
                Player online = Bukkit.getPlayerExact(finalPlayer);
                UUID uuid = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(finalPlayer).getUniqueId();
                String uuidStr = uuid.toString();
                // Remove from any existing group first
                var groupsSection = plugin.getDataConfig().getConfigurationSection("groups");
                if (groupsSection != null) {
                    for (String gn : groupsSection.getKeys(false)) {
                        List<String> gMembers = new ArrayList<>(plugin.getDataConfig().getStringList("groups." + gn + ".members"));
                        if (gMembers.remove(uuidStr)) {
                            plugin.getDataConfig().set("groups." + gn + ".members", gMembers);
                        }
                    }
                }
                // Add to new group
                List<String> members = new ArrayList<>(plugin.getDataConfig().getStringList("groups." + finalName + ".members"));
                if (!members.contains(uuidStr)) members.add(uuidStr);
                plugin.getDataConfig().set("groups." + finalName + ".members", members);
                // Update last_seen_name for this uuid
                plugin.getDataConfig().set("last_seen_name." + uuidStr, finalPlayer);
                plugin.saveDataFile();
                // Apply permissions if online
                if (online != null) plugin.applyPermissionGroup(online);
                plugin.logAction("WebPanel", "group_member_add", finalPlayer + " -> " + finalName);
                return Map.of("success", (Object) true, "uuid", (Object) uuidStr);
            });
            Map<String, Object> result = future10.get();
            if (result.containsKey("error")) { ctx.status(400); }
            ctx.json(result);
        });

        app.post("/api/groups/members/remove", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = om.readValue(ctx.body(), Map.class);
            String name = (String) body.get("name");
            String uuid = (String) body.get("uuid");
            if (name == null || uuid == null) { ctx.status(400).json(Map.of("error", "Group name and uuid required")); return; }
            String finalName = name;
            String finalUuid = uuid;
            Future<Map<String, Object>> future11 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<String> members = new ArrayList<>(plugin.getDataConfig().getStringList("groups." + finalName + ".members"));
                members.remove(finalUuid);
                plugin.getDataConfig().set("groups." + finalName + ".members", members);
                plugin.saveDataFile();
                // Remove permissions if online
                try {
                    Player online = Bukkit.getPlayer(UUID.fromString(finalUuid));
                    if (online != null) plugin.removePermissionAttachment(online);
                } catch (Exception ignored) {}
                plugin.logAction("WebPanel", "group_member_remove", finalUuid + " from " + finalName);
                return Map.of("success", (Object) true);
            });
            ctx.json(future11.get());
        });

        // ========== RANKS API (Synced with In-Game Ranks) ==========
        app.get("/api/ranks", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.groups")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> ranks = new ArrayList<>();
                var section = plugin.getDataConfig().getConfigurationSection("ranks");
                if (section != null) {
                    for (String name : section.getKeys(false)) {
                        Map<String, Object> r = new HashMap<>();
                        String path = "ranks." + name;
                        r.put("name", name);
                        r.put("color", plugin.getDataConfig().getString(path + ".color", "#ffffff"));
                        r.put("prefix", plugin.getDataConfig().getString(path + ".prefix", ""));
                        r.put("level", plugin.getDataConfig().getInt(path + ".level", 1));
                        r.put("description", plugin.getDataConfig().getString(path + ".description", ""));
                        ranks.add(r);
                    }
                }
                return Map.of("ranks", (Object) ranks);
            });
            ctx.json(future.get());
        });

        app.post("/api/ranks/create", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String name = (String) body.get("name");
            if (name == null || name.isBlank()) { ctx.status(400).json(Map.of("error", "Name required")); return; }
            String finalName = name.replaceAll("[^a-zA-Z0-9_-]", "");
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                String path = "ranks." + finalName;
                if (!plugin.getDataConfig().contains(path)) {
                    String hexColor = (String) body.getOrDefault("color", "#ffffff");
                    plugin.getDataConfig().set(path + ".color", hexColor);
                    plugin.getDataConfig().set(path + ".level", body.getOrDefault("level", 1));
                    plugin.getDataConfig().set(path + ".description", body.getOrDefault("description", ""));
                    
                    String spigotColor = "";
                    if (hexColor.startsWith("#") && hexColor.length() == 7) {
                        spigotColor = "&x";
                        for (char c : hexColor.substring(1).toCharArray()) {
                            spigotColor += "&" + c;
                        }
                    } else {
                        spigotColor = "&7";
                    }
                    plugin.getDataConfig().set(path + ".prefix", spigotColor + "[" + finalName + "] &r");
                    plugin.getDataConfig().set(path + ".permissions", new ArrayList<String>());
                    plugin.getDataConfig().set(path + ".members", new ArrayList<String>());
                    plugin.saveDataFile();
                    plugin.logAction("WebAdmin", "created rank", finalName);
                }
            });
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ranks/update", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String name = (String) body.get("name");
            if (name == null) { ctx.status(400).json(Map.of("error", "Name required")); return; }
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                String path = "ranks." + name;
                if (plugin.getDataConfig().contains(path)) {
                    if (body.containsKey("description")) plugin.getDataConfig().set(path + ".description", body.get("description"));
                    if (body.containsKey("level")) plugin.getDataConfig().set(path + ".level", body.get("level"));
                    if (body.containsKey("color")) {
                        String hexColor = (String) body.get("color");
                        plugin.getDataConfig().set(path + ".color", hexColor);
                        
                        String spigotColor = "";
                        if (hexColor.startsWith("#") && hexColor.length() == 7) {
                            spigotColor = "&x";
                            for (char c : hexColor.substring(1).toCharArray()) {
                                spigotColor += "&" + c;
                            }
                        } else {
                            spigotColor = "&7";
                        }
                        plugin.getDataConfig().set(path + ".prefix", spigotColor + "[" + name + "] &r");
                    }
                    plugin.saveDataFile();
                    plugin.refreshAllPermissions();
                    plugin.logAction("WebAdmin", "updated rank", name);
                }
            });
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ranks/delete", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String name = (String) body.get("name");
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataConfig().set("ranks." + name, null);
                // Remove from player assignments
                if (plugin.getDataConfig().contains("player_rank")) {
                    for (String uuidKey : plugin.getDataConfig().getConfigurationSection("player_rank").getKeys(false)) {
                        String assigned = plugin.getDataConfig().getString("player_rank." + uuidKey);
                        if (assigned != null && assigned.equals(name)) {
                            plugin.getDataConfig().set("player_rank." + uuidKey, null);
                        }
                    }
                }
                plugin.saveDataFile();
                plugin.refreshAllPermissions();
                plugin.logAction("WebAdmin", "deleted rank", name);
            });
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ranks/promote", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
            var body = ctx.bodyAsClass(Map.class);
            String player = (String) body.get("player");
            String rank = (String) body.get("rank");
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                String uuidStr = uuid.toString();
                
                plugin.setPlayerRank(uuid, rank);
                
                plugin.getDataConfig().set("users." + uuidStr + ".promotedBy", "WebAdmin");
                plugin.getDataConfig().set("users." + uuidStr + ".promotionDate", System.currentTimeMillis());
                
                plugin.saveDataFile();
                
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) plugin.applyPermissionGroup(p);
                
                plugin.logAction("WebAdmin", "promoted " + player + " to", rank);
            });
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ranks/demote", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.groups")) return;
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

        app.get("/api/allplayers", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.players")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> players = new ArrayList<>();
                Set<UUID> seen = new HashSet<>();
                
                // Online
                for (Player p : Bukkit.getOnlinePlayers()) {
                    seen.add(p.getUniqueId());
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", p.getName());
                    m.put("uuid", p.getUniqueId().toString());
                    m.put("rank", plugin.getPlayerRank(p.getUniqueId()));
                    m.put("promotedBy", plugin.getDataConfig().getString("users." + p.getUniqueId() + ".promotedBy"));
                    m.put("promotionDate", plugin.getDataConfig().getLong("users." + p.getUniqueId() + ".promotionDate"));
                    players.add(m);
                }
                
                // Offline (from player_rank assignment)
                var playerRankSection = plugin.getDataConfig().getConfigurationSection("player_rank");
                if (playerRankSection != null) {
                    for (String uuidStr : playerRankSection.getKeys(false)) {
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            if (seen.contains(uuid)) continue;
                            seen.add(uuid);
                            String rank = plugin.getDataConfig().getString("player_rank." + uuidStr);
                            String name = plugin.getDataConfig().getString("last_seen_name." + uuidStr, Bukkit.getOfflinePlayer(uuid).getName());
                            Map<String, Object> m = new HashMap<>();
                            m.put("name", name != null ? name : uuidStr);
                            m.put("uuid", uuidStr);
                            m.put("rank", rank);
                            m.put("promotedBy", plugin.getDataConfig().getString("users." + uuidStr + ".promotedBy"));
                            m.put("promotionDate", plugin.getDataConfig().getLong("users." + uuidStr + ".promotionDate"));
                            players.add(m);
                        } catch (Exception ignored) {}
                    }
                }
                
                // Offline (from ranks membership lists)
                var ranksSection = plugin.getDataConfig().getConfigurationSection("ranks");
                if (ranksSection != null) {
                    for (String g : ranksSection.getKeys(false)) {
                        List<String> members = plugin.getDataConfig().getStringList("ranks." + g + ".members");
                        for (String uuidStr : members) {
                            try {
                                UUID uuid = UUID.fromString(uuidStr);
                                if (seen.contains(uuid)) continue;
                                seen.add(uuid);
                                String name = plugin.getDataConfig().getString("last_seen_name." + uuidStr, Bukkit.getOfflinePlayer(uuid).getName());
                                Map<String, Object> m = new HashMap<>();
                                m.put("name", name != null ? name : uuidStr);
                                m.put("uuid", uuidStr);
                                m.put("rank", g);
                                m.put("promotedBy", plugin.getDataConfig().getString("users." + uuidStr + ".promotedBy"));
                                m.put("promotionDate", plugin.getDataConfig().getLong("users." + uuidStr + ".promotionDate"));
                                players.add(m);
                            } catch (Exception ignored) {}
                        }
                    }
                }
                
                return Map.of("players", (Object) players);
            });
            ctx.json(future.get());
        });
    }

    public void stop() { if (app != null) app.stop(); }

    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().equals("session.lock")) continue; // Skip active lock files
            if (file.isDirectory()) {
                zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                continue;
            }
            try {
                zos.putNextEntry(new ZipEntry(parentFolder + "/" + file.getName()));
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            } catch (Exception e) {
                // Ignore individual locked file read errors to ensure zip succeeds
            }
        }
    }

    private static class WebLogHandler extends Handler {
        private final ConcurrentLinkedQueue<WsContext> sessions;
        public static final LinkedList<String> recentLogs = new LinkedList<>();

        public WebLogHandler(ConcurrentLinkedQueue<WsContext> s) { this.sessions = s; }
        @Override
        public void publish(LogRecord record) {
            String msg = "[" + record.getLevel() + "] " + record.getMessage();
            synchronized(recentLogs) {
                recentLogs.add(msg);
                if (recentLogs.size() > 1000) recentLogs.removeFirst();
            }
            for (WsContext s : sessions) { 
                try { if (s.session.isOpen()) s.send(msg); } catch(Exception ignored) {} 
            }
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
package com.stuart.javarealmtool;

import com.stuart.javarealmtool.services.NetworkPlayerProfile;
import com.stuart.javarealmtool.services.NetworkPunishment;
import com.stuart.javarealmtool.services.NetworkWarning;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import com.stuart.javarealmtool.web.WebRankController;
import com.stuart.javarealmtool.web.WebTicketController;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import java.io.File;
import java.text.SimpleDateFormat;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WebServer {
    private static final Pattern MINECRAFT_USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final JavaRealmTool plugin;
    private Javalin app;
    private final WebTicketController ticketController;
    private final WebRankController rankController;
    private final ConcurrentLinkedQueue<WsContext> sessions = new ConcurrentLinkedQueue<>();
    private final Map<WsContext, String> liveSessions = new ConcurrentHashMap<>();
    private final Map<WsContext, Map<String, String>> liveSessionSignatures = new ConcurrentHashMap<>();
    private final Map<String, String> userSessions = new HashMap<>();
    private BukkitTask liveBroadcastTask;

    public WebServer(JavaRealmTool plugin) {
        this.plugin = plugin;
        this.ticketController = new WebTicketController(plugin, this);
        this.rankController = new WebRankController(plugin, this);
    }

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
                        router.ws("/api/live", ws -> {
                            ws.onConnect(ctx -> {
                                String authToken = normalizeAuthorizationToken(ctx.queryParam("token"));
                                if (authToken == null || !userSessions.containsKey(authToken)) {
                                    try { ctx.session.close(); } catch (Exception ignored) {}
                                    return;
                                }
                                liveSessions.put(ctx, authToken);
                                liveSessionSignatures.put(ctx, new HashMap<>());
                            });
                            ws.onClose(ctx -> {
                                liveSessions.remove(ctx);
                                liveSessionSignatures.remove(ctx);
                            });
                        });
                    });
                });

                setupRoutes();
                Bukkit.getLogger().addHandler(new WebLogHandler(sessions));
                String bindHost = plugin.getWebBindHost();
                int bindPort = plugin.getWebPort();
                app.start(bindHost, bindPort);
                startLiveBroadcastTask();
                plugin.getLogger().info("Embedded web server listening on " + bindHost + ":" + bindPort
                    + " (public base URL: " + plugin.getWebPublicBaseUrl() + ")");
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to start embedded web server", e);
                app = null;
            } finally {
                // Restore the original console output streams immediately after startup
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        });
        serverThread.start();
    }

    public boolean auth(io.javalin.http.Context ctx) {
        String token = ctx.header("Authorization");
        if (token == null || !userSessions.containsKey(token)) {
            ctx.status(401).result("Unauthorized");
            return false;
        }
        return true;
    }

    private boolean authSheetsExport(io.javalin.http.Context ctx) {
        String providedKey = Optional.ofNullable(ctx.queryParam("key"))
            .filter(value -> !value.isBlank())
            .orElseGet(() -> Optional.ofNullable(ctx.header("X-API-Key"))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> {
                    String authHeader = ctx.header("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        return authHeader.substring("Bearer ".length()).trim();
                    }
                    return authHeader;
                }));

        if (providedKey == null || providedKey.isBlank() || !Objects.equals(providedKey, plugin.getApiKey())) {
            ctx.status(401).json(Map.of(
                "error", "Unauthorized",
                "message", "Provide the plugin api-key as ?key=... or X-API-Key."
            ));
            return false;
        }
        return true;
    }

    private List<LinkedHashMap<String, Object>> buildSheetsExportRows(String dataset) {
        return switch (dataset) {
            case "players" -> buildPlayerExportRows();
            case "economy" -> buildEconomyExportRows();
            case "tickets" -> buildTicketExportRows("tickets", false);
            case "appeals" -> buildTicketExportRows("appeals", true);
            case "staff-hours" -> buildStaffHourExportRows();
            default -> throw new IllegalArgumentException("Unsupported dataset: " + dataset);
        };
    }

    private List<LinkedHashMap<String, Object>> buildPlayerExportRows() {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Set<String> candidateUuids = new HashSet<>();
        var data = plugin.getDataConfig();
        var economy = plugin.getEconomyConfig();

        var lastSeen = data.getConfigurationSection("last_seen_name");
        if (lastSeen != null) candidateUuids.addAll(lastSeen.getKeys(false));
        var playtime = data.getConfigurationSection("playtime");
        if (playtime != null) candidateUuids.addAll(playtime.getKeys(false));
        for (UUID warnedPlayer : plugin.getAllWarnings().keySet()) {
            candidateUuids.add(warnedPlayer.toString());
        }
        var coins = economy.getConfigurationSection("coins");
        if (coins != null) candidateUuids.addAll(coins.getKeys(false));
        for (Player player : Bukkit.getOnlinePlayers()) {
            candidateUuids.add(player.getUniqueId().toString());
        }

        for (String uuidStr : candidateUuids) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                if (!seen.add(uuid)) continue;

                org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                Player onlinePlayer = Bukkit.getPlayer(uuid);
                NetworkPlayerProfile profile = resolvePlayerProfile(uuid, onlinePlayer != null && onlinePlayer.isOnline());
                String name = profile.lastSeenName();
                if (name == null || name.isBlank()) name = data.getString("last_seen_name." + uuidStr, offlinePlayer.getName());
                if (name == null || name.isBlank()) name = uuidStr;
                String rank = profile.rank();
                if (rank == null || rank.isBlank() || "default".equalsIgnoreCase(rank)) rank = profile.group();

                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", uuidStr);
                row.put("name", name);
                row.put("online", onlinePlayer != null && onlinePlayer.isOnline());
                row.put("world", onlinePlayer != null ? onlinePlayer.getWorld().getName() : "");
                row.put("x", onlinePlayer != null ? Math.round(onlinePlayer.getLocation().getX() * 10.0) / 10.0 : "");
                row.put("y", onlinePlayer != null ? Math.round(onlinePlayer.getLocation().getY() * 10.0) / 10.0 : "");
                row.put("z", onlinePlayer != null ? Math.round(onlinePlayer.getLocation().getZ() * 10.0) / 10.0 : "");
                row.put("playtimeHours", Math.max(0L, Math.round(profile.playtimeHours())));
                row.put("warnings", plugin.getWarningCount(uuid));
                row.put("punished", plugin.isPunished(uuid));
                NetworkPunishment punishment = plugin.getPunishment(uuid);
                row.put("punishmentReason", punishment != null ? punishment.reason() : "");
                row.put("punishmentEnd", punishment != null ? punishment.expiresAt() : 0L);
                row.put("punishedBy", punishment != null ? punishment.actor() : "");
                row.put("punishedAt", punishment != null ? punishment.createdAt() : 0L);
                row.put("banned", Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(name));
                row.put("coins", plugin.getCoins(uuid));
                row.put("rank", rank != null ? rank : "");
                rows.add(row);
            } catch (Exception ignored) {
            }
        }

        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private NetworkPlayerProfile resolvePlayerProfile(UUID uuid, boolean refresh) {
        if (plugin.getNetworkProfileService() == null) {
            return new NetworkPlayerProfile(
                uuid,
                uuid.toString(),
                plugin.getPlayerRank(uuid),
                plugin.getPlayerGroup(uuid),
                plugin.getDiscordLink(uuid),
                plugin.getPlaytimeHours(uuid)
            );
        }

        return refresh
            ? plugin.getNetworkProfileService().refreshProfile(uuid)
            : plugin.getNetworkProfileService().getProfile(uuid);
    }

    private List<LinkedHashMap<String, Object>> buildEconomyExportRows() {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        var data = plugin.getDataConfig();
        var economyData = plugin.getEconomyConfig();
        var coins = economyData.getConfigurationSection("coins");
        if (coins == null) {
            return rows;
        }

        for (String uuidStr : coins.getKeys(false)) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", uuidStr);
            row.put("balance", economyData.getLong("coins." + uuidStr, 0));
            row.put("earned", 0);
            row.put("spent", 0);
            try {
                UUID uuid = UUID.fromString(uuidStr);
                String name = data.getString("last_seen_name." + uuidStr, Bukkit.getOfflinePlayer(uuid).getName());
                row.put("name", name != null ? name : uuidStr);
            } catch (Exception e) {
                row.put("name", uuidStr);
            }
            rows.add(row);
        }

        rows.sort((left, right) -> Long.compare(
            ((Number) right.get("balance")).longValue(),
            ((Number) left.get("balance")).longValue()
        ));
        return rows;
    }

    private List<LinkedHashMap<String, Object>> buildTicketExportRows(String rootPath, boolean appealDataset) {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        var ticketConfig = plugin.getTicketConfig();
        var section = ticketConfig.getConfigurationSection(rootPath);
        if (section == null) {
            return rows;
        }

        for (String key : section.getKeys(false)) {
            if ("next_id".equals(key)) continue;

            String basePath = rootPath + "." + key;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", appealDataset ? -Integer.parseInt(key) : Integer.parseInt(key));
            row.put("player", ticketConfig.getString(basePath + ".player", ""));
            row.put("message", ticketConfig.getString(basePath + ".message", ""));
            row.put("status", ticketConfig.getString(basePath + ".status", "open"));
            row.put("priority", ticketConfig.getString(basePath + ".priority", "medium"));
            row.put("category", ticketConfig.getString(basePath + ".category", "other"));
            row.put("assignee", ticketConfig.getString(basePath + ".assignee", ""));
            row.put("timestamp", ticketConfig.getString(basePath + ".timestamp", ""));
            row.put("type", appealDataset ? "appeal" : "ticket");
            rows.add(row);
        }

        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("timestamp")), Comparator.reverseOrder()));
        return rows;
    }

    private List<LinkedHashMap<String, Object>> buildStaffHourExportRows() {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> staffSummary : plugin.getStaffHourSummaryData()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", staffSummary.getOrDefault("uuid", ""));
            row.put("name", staffSummary.getOrDefault("name", ""));
            row.put("minutes24h", staffSummary.getOrDefault("minutes24h", 0));
            row.put("minutes7d", staffSummary.getOrDefault("minutes7d", 0));
            row.put("minutes14d", staffSummary.getOrDefault("minutes14d", 0));
            rows.add(row);
        }
        return rows;
    }

    private String toCsv(List<LinkedHashMap<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }

        List<String> headers = new ArrayList<>(rows.get(0).keySet());
        StringBuilder csv = new StringBuilder();
        csv.append(headers.stream().map(this::escapeCsv).collect(Collectors.joining(","))).append('\n');
        for (LinkedHashMap<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (String header : headers) {
                values.add(escapeCsv(String.valueOf(row.getOrDefault(header, ""))));
            }
            csv.append(String.join(",", values)).append('\n');
        }
        return csv.toString();
    }

    private String escapeCsv(String value) {
        String safeValue = value == null ? "" : value;
        boolean needsQuotes = safeValue.contains(",") || safeValue.contains("\n") || safeValue.contains("\r") || safeValue.contains("\"");
        if (!needsQuotes) {
            return safeValue;
        }
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private ResolvedPlayer resolveKnownPlayer(String username) {
        if (username == null) {
            return null;
        }

        String trimmedUsername = username.trim();
        if (trimmedUsername.isEmpty()) {
            return null;
        }

        Player onlinePlayer = Bukkit.getPlayerExact(trimmedUsername);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            return new ResolvedPlayer(onlinePlayer.getUniqueId(), onlinePlayer.getName(), onlinePlayer.isOp());
        }

        UUID storedUuid = findStoredUuidByName(trimmedUsername);
        if (storedUuid != null) {
            String storedName = plugin.getDataConfig().getString("last_seen_name." + storedUuid, trimmedUsername);
            return new ResolvedPlayer(storedUuid, storedName, isOperator(storedUuid, storedName));
        }

        if (!MINECRAFT_USERNAME_PATTERN.matcher(trimmedUsername).matches()) {
            return null;
        }

        for (org.bukkit.OfflinePlayer operator : Bukkit.getOperators()) {
            if (operator.getName() != null && operator.getName().equalsIgnoreCase(trimmedUsername)) {
                return new ResolvedPlayer(operator.getUniqueId(), operator.getName(), true);
            }
        }

        for (org.bukkit.OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(trimmedUsername)) {
                return new ResolvedPlayer(offlinePlayer.getUniqueId(), offlinePlayer.getName(), offlinePlayer.isOp());
            }
        }

        return null;
    }

    private UUID findStoredUuidByName(String username) {
        var lastSeenSection = plugin.getDataConfig().getConfigurationSection("last_seen_name");
        if (lastSeenSection == null) {
            return null;
        }

        for (String uuidKey : lastSeenSection.getKeys(false)) {
            String storedName = lastSeenSection.getString(uuidKey);
            if (storedName == null || !storedName.equalsIgnoreCase(username)) {
                continue;
            }

            try {
                return UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        return null;
    }

    private boolean isOperator(UUID uuid, String playerName) {
        for (org.bukkit.OfflinePlayer operator : Bukkit.getOperators()) {
            if (operator.getUniqueId().equals(uuid)) {
                return true;
            }
            if (playerName != null && operator.getName() != null && operator.getName().equalsIgnoreCase(playerName)) {
                return true;
            }
        }
        return false;
    }

    private record ResolvedPlayer(UUID uuid, String name, boolean op) {}

    public boolean hasPermission(String token, String permission) {
        String username = userSessions.get(token);
        if (username == null) return false;

        Future<Boolean> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Player player = Bukkit.getPlayer(username);
            if (player != null && player.isOnline()) {
                return player.hasPermission(permission);
            }

            ResolvedPlayer resolvedPlayer = resolveKnownPlayer(username);
            if (resolvedPlayer == null) return false;
            if (resolvedPlayer.op()) return true;

            UUID uuid = resolvedPlayer.uuid();
            String group = plugin.getPlayerGroup(uuid);
            if (group != null) {
                List<String> perms = plugin.getRankConfig().getStringList("groups." + group + ".permissions");
                if (perms.contains(permission) || perms.contains("webapp.*")) return true;
            }
            
            String rank = plugin.getPlayerRank(uuid);
            if (rank != null) {
                List<String> perms = plugin.getRankConfig().getStringList("ranks." + rank + ".permissions");
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

    private String normalizeAuthorizationToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String trimmed = rawToken.trim();
        return trimmed.startsWith("Bearer ") ? trimmed : "Bearer " + trimmed;
    }

    private String toJson(Object payload) {
        try {
            return JSON_MAPPER.writeValueAsString(payload);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void startLiveBroadcastTask() {
        if (liveBroadcastTask != null) {
            liveBroadcastTask.cancel();
        }
        liveBroadcastTask = Bukkit.getScheduler().runTaskTimer(plugin, this::broadcastLiveSnapshots, 20L, 20L);
    }

    private void broadcastLiveSnapshots() {
        if (liveSessions.isEmpty()) {
            return;
        }

        Map<String, Object> playersSnapshot = buildPlayersSnapshot();
        List<Map<String, String>> chatSnapshot = buildChatSnapshot();
        List<Map<String, Object>> punishmentsSnapshot = buildPunishmentsSnapshot();
        Map<String, Object> warningsSnapshot = buildWarningsSnapshot();
        List<Map<String, Object>> ticketsSnapshot = buildTicketsSnapshot(null, null);
        List<Map<String, String>> mutedSnapshot = buildMutedSnapshot();

        broadcastLiveTopic("players", playersSnapshot, "webapp.view.players");
        broadcastLiveTopic("chat", chatSnapshot, "webapp.view.chat");
        broadcastLiveTopic("punishments", punishmentsSnapshot, "webapp.view.players");
        broadcastLiveTopic("warnings", warningsSnapshot, "webapp.view.warnings");
        broadcastLiveTopic("tickets", ticketsSnapshot, "webapp.view.tickets");
        broadcastLiveTopic("muted", mutedSnapshot, "webapp.view.mutes");
    }

    private void broadcastLiveTopic(String topic, Object payload, String requiredPermission) {
        String payloadJson = toJson(payload);
        String message = toJson(Map.of("type", topic, "data", payload));

        for (Map.Entry<WsContext, String> entry : new ArrayList<>(liveSessions.entrySet())) {
            WsContext session = entry.getKey();
            String token = entry.getValue();

            if (session == null || session.session == null || !session.session.isOpen()) {
                liveSessions.remove(session);
                liveSessionSignatures.remove(session);
                continue;
            }

            if (!hasPermission(token, requiredPermission)) {
                continue;
            }

            Map<String, String> signatures = liveSessionSignatures.computeIfAbsent(session, ignored -> new HashMap<>());
            if (payloadJson.equals(signatures.get(topic))) {
                continue;
            }

            try {
                session.send(message);
                signatures.put(topic, payloadJson);
            } catch (Exception ignored) {
                liveSessions.remove(session);
                liveSessionSignatures.remove(session);
            }
        }
    }

    private Map<String, Object> buildPlayersSnapshot() {
        Map<String, Object> res = new HashMap<>();
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            NetworkPlayerProfile profile = resolvePlayerProfile(p.getUniqueId(), true);
            Map<String, Object> m = new HashMap<>();
            m.put("name", p.getName());
            m.put("health", Math.round(p.getHealth()));
            m.put("x", Math.round(p.getLocation().getX() * 10.0) / 10.0);
            m.put("y", Math.round(p.getLocation().getY() * 10.0) / 10.0);
            m.put("z", Math.round(p.getLocation().getZ() * 10.0) / 10.0);
            m.put("world", p.getWorld().getName());
            m.put("warnings", plugin.getWarningCount(p.getUniqueId()));
            m.put("playtime", Math.max(0L, Math.round(profile.playtimeHours())));
            m.put("punished", plugin.isPunished(p.getUniqueId()));
            m.put("coins", plugin.getCoins(p.getUniqueId()));
            m.put("discord", profile.discordLink());
            String rank = profile.rank();
            if (rank == null || rank.isBlank() || "default".equalsIgnoreCase(rank)) rank = profile.group();
            m.put("rank", rank);
            m.put("rankColor", getRankHexColor(rank));
            m.put("color", getRankHexColor(rank));
            players.add(m);
        }
        res.put("players", players);
        res.put("tps", Math.min(20.0, Math.round(Bukkit.getTPS()[0] * 100.0) / 100.0));
        res.put("usedMem", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024);
        res.put("totalMem", Runtime.getRuntime().totalMemory() / 1024 / 1024);
        res.put("percentMem", Math.round(((double)(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / Runtime.getRuntime().totalMemory()) * 100));
        return res;
    }

    private List<Map<String, String>> buildChatSnapshot() {
        List<String> chat = plugin.getDataConfig().getStringList("chat_history");
        List<Map<String, String>> messages = new ArrayList<>();
        int start = Math.max(0, chat.size() - 50);
        for (int i = start; i < chat.size(); i++) {
            String msg = chat.get(i);
            String[] parts = msg.split(" \\| ", 2);
            if (parts.length != 2) {
                continue;
            }
            String timestamp = parts[0].trim();
            String rest = parts[1].trim();
            int colonIdx = rest.indexOf(':');
            if (colonIdx <= 0) {
                continue;
            }
            Map<String, String> msgMap = new HashMap<>();
            msgMap.put("timestamp", timestamp);
            msgMap.put("player", rest.substring(0, colonIdx).trim());
            msgMap.put("message", rest.substring(colonIdx + 1).trim());
            messages.add(msgMap);
        }
        return messages;
    }

    private List<Map<String, Object>> buildPunishmentsSnapshot() {
        List<Map<String, Object>> punishments = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, NetworkPunishment> entry : plugin.getActivePunishmentRecords().entrySet()) {
            try {
                UUID uuid = entry.getKey();
                NetworkPunishment punishmentRecord = entry.getValue();
                long expiry = punishmentRecord.expiresAt();
                if (expiry <= now || !plugin.isPunished(uuid)) {
                    continue;
                }

                long minutesLeft = Math.max(1L, (expiry - now + 59999L) / 60000L);
                NetworkPlayerProfile profile = resolvePlayerProfile(uuid, false);

                Map<String, Object> punishment = new HashMap<>();
                punishment.put("player", profile.lastSeenName() != null ? profile.lastSeenName() : uuid.toString());
                punishment.put("duration", minutesLeft);
                punishment.put("reason", punishmentRecord.reason() != null && !punishmentRecord.reason().isBlank() ? punishmentRecord.reason() : "Punished in-game");
                punishment.put("issuedBy", punishmentRecord.actor() != null && !punishmentRecord.actor().isBlank() ? punishmentRecord.actor() : "Unknown");
                punishment.put("createdAt", punishmentRecord.createdAt() > 0L ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(punishmentRecord.createdAt())) : "Unknown");
                punishment.put("endsAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(expiry)));
                punishments.add(punishment);
            } catch (Exception ignored) {}
        }

        punishments.sort(Comparator.comparing(map -> String.valueOf(map.get("player"))));
        return punishments;
    }

    private Map<String, Object> buildWarningsSnapshot() {
        List<Map<String, Object>> warnings = new ArrayList<>();
        for (Map.Entry<UUID, List<NetworkWarning>> entry : plugin.getAllWarnings().entrySet()) {
            UUID uuid = entry.getKey();
            NetworkPlayerProfile profile = resolvePlayerProfile(uuid, false);
            String playerName = profile.lastSeenName() != null && !profile.lastSeenName().isBlank()
                ? profile.lastSeenName()
                : uuid.toString();
            for (NetworkWarning warning : entry.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("player", playerName);
                row.put("warningNumber", warning.warningNumber());
                row.put("reason", warning.reason());
                row.put("issuedBy", warning.issuedBy() != null && !warning.issuedBy().isBlank() ? warning.issuedBy() : "Unknown");
                row.put("date", warning.createdAt());
                warnings.add(row);
            }
        }
        warnings.sort((left, right) -> Long.compare(
            ((Number) right.get("date")).longValue(),
            ((Number) left.get("date")).longValue()
        ));
        return Map.of("warnings", warnings);
    }

    private Map<String, Object> buildModerationTimelineSnapshot(
        String playerName,
        boolean includeReputation,
        boolean includeWarnings,
        boolean includeNotes,
        boolean includePunishments,
        boolean includeMutes,
        boolean includeBans,
        boolean includePlaytime
    ) {
        UUID uuid = Bukkit.getOfflinePlayer(playerName).getUniqueId();
        NetworkPlayerProfile profile = resolvePlayerProfile(uuid, false);
        String resolvedName = profile.lastSeenName() != null && !profile.lastSeenName().isBlank()
            ? profile.lastSeenName()
            : playerName;
        long now = System.currentTimeMillis();
        List<Map<String, Object>> timeline = new ArrayList<>();
        int warningCount = 0;
        int positiveNotes = 0;
        Integer muteCount = null;
        Integer banCount = null;
        Long playtime = includePlaytime ? plugin.getPlaytimeHours(uuid) : null;
        boolean hasUndatedEntries = false;

        if (includeWarnings) {
            for (NetworkWarning warning : plugin.getWarnings(uuid)) {
                warningCount += 1;
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "warning");
                event.put("title", "Warning #" + warning.warningNumber());
                event.put("detail", warning.reason() != null && !warning.reason().isBlank() ? warning.reason() : "No reason");
                event.put("actor", warning.issuedBy() != null && !warning.issuedBy().isBlank() ? warning.issuedBy() : "Unknown");
                event.put("timestamp", warning.createdAt());
                event.put("timestampLabel", warning.createdAt() > 0L ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(warning.createdAt())) : "Unknown date");
                event.put("sortTimestamp", warning.createdAt());
                event.put("hasTimestamp", warning.createdAt() > 0L);
                timeline.add(event);
            }
        }

        if (includeNotes) {
            for (String rawNote : plugin.getPlayerNotes(uuid)) {
                String timestamp = "";
                String category = "INFO";
                String text = rawNote;

                if (rawNote != null && rawNote.contains(" | ")) {
                    String[] parts = rawNote.split(" \\| ", 2);
                    timestamp = parts[0].trim();
                    text = parts[1].trim();
                    if (text.startsWith("[")) {
                        int endBracket = text.indexOf(']');
                        if (endBracket > 0) {
                            category = text.substring(1, endBracket).trim().toUpperCase(Locale.ROOT);
                            text = text.substring(endBracket + 1).trim();
                        }
                    }
                }

                if ("POSITIVE".equalsIgnoreCase(category)) {
                    positiveNotes += 1;
                }

                long noteTimestamp = parseModerationTimestamp(timestamp);
                if (noteTimestamp <= 0L) {
                    hasUndatedEntries = true;
                }

                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "note");
                event.put("title", category + " note");
                event.put("detail", text != null && !text.isBlank() ? text : "No content");
                event.put("category", category);
                event.put("timestamp", noteTimestamp > 0L ? noteTimestamp : null);
                event.put("timestampLabel", noteTimestamp > 0L ? timestamp : "Undated note");
                event.put("sortTimestamp", noteTimestamp);
                event.put("hasTimestamp", noteTimestamp > 0L);
                timeline.add(event);
            }
        }

        NetworkPunishment punishment = plugin.getPunishment(uuid);
        boolean hasActivePunishment = includePunishments && punishment != null && punishment.expiresAt() > now && plugin.isPunished(uuid);
        if (hasActivePunishment) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "punishment");
            event.put("title", "Active punishment");
            event.put("detail", punishment.reason() != null && !punishment.reason().isBlank() ? punishment.reason() : "Punished in-game");
            event.put("actor", punishment.actor() != null && !punishment.actor().isBlank() ? punishment.actor() : "Unknown");
            event.put("timestamp", punishment.createdAt() > 0L ? punishment.createdAt() : null);
            event.put("timestampLabel", punishment.createdAt() > 0L ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(punishment.createdAt())) : "Active punishment");
            event.put("endsAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(punishment.expiresAt())));
            event.put("sortTimestamp", Math.max(0L, punishment.createdAt()));
            event.put("hasTimestamp", punishment.createdAt() > 0L);
            timeline.add(event);
            if (punishment.createdAt() <= 0L) {
                hasUndatedEntries = true;
            }
        }

        if (includeMutes) {
            muteCount = 0;
            for (Map<String, String> muted : buildMutedSnapshot()) {
                String mutedUuid = muted.get("uuid");
                String mutedName = muted.get("name");
                if ((mutedUuid != null && mutedUuid.equals(uuid.toString()))
                    || (mutedName != null && mutedName.equalsIgnoreCase(resolvedName))) {
                    muteCount = 1;
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", "mute");
                    event.put("title", "Active mute");
                    event.put("detail", muted.getOrDefault("reason", "No reason"));
                    event.put("timestamp", null);
                    event.put("timestampLabel", "Active mute");
                    event.put("sortTimestamp", 0L);
                    event.put("hasTimestamp", false);
                    timeline.add(event);
                    hasUndatedEntries = true;
                    break;
                }
            }
        }

        if (includeBans) {
            banCount = 0;
            org.bukkit.BanEntry nameBanEntry = null;
            try {
                nameBanEntry = (org.bukkit.BanEntry) Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntry(resolvedName);
                if (nameBanEntry == null && !resolvedName.equalsIgnoreCase(playerName)) {
                    nameBanEntry = (org.bukkit.BanEntry) Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntry(playerName);
                }
            } catch (Throwable ignored) {
            }

            boolean currentlyBanned = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(resolvedName)
                || (!resolvedName.equalsIgnoreCase(playerName) && Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(playerName));
            banCount = plugin.getDataConfig().getInt("bans_count." + uuid, 0) + (currentlyBanned ? 1 : 0);

            if (nameBanEntry != null) {
                Date created = nameBanEntry.getCreated();
                long banTimestamp = created != null ? created.getTime() : 0L;
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "ban");
                event.put("title", "Active ban");
                event.put("detail", nameBanEntry.getReason() != null && !nameBanEntry.getReason().isBlank() ? nameBanEntry.getReason() : "No reason");
                event.put("actor", nameBanEntry.getSource() != null && !nameBanEntry.getSource().isBlank() ? nameBanEntry.getSource() : "Unknown");
                event.put("timestamp", banTimestamp > 0L ? banTimestamp : null);
                event.put("timestampLabel", banTimestamp > 0L ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(created) : "Active ban");
                event.put("endsAt", nameBanEntry.getExpiration() != null ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(nameBanEntry.getExpiration()) : "Never");
                event.put("sortTimestamp", banTimestamp);
                event.put("hasTimestamp", banTimestamp > 0L);
                timeline.add(event);
                if (banTimestamp <= 0L) {
                    hasUndatedEntries = true;
                }
            }
        }

        timeline.sort(
            Comparator.comparingLong((Map<String, Object> event) -> ((Number) event.getOrDefault("sortTimestamp", 0L)).longValue())
                .reversed()
                .thenComparing(event -> String.valueOf(event.get("type")))
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("player", resolvedName);
        response.put("timeline", timeline);
        response.put("hasUndatedEntries", hasUndatedEntries);
        response.put("warnings", includeWarnings ? warningCount : null);
        response.put("mutes", muteCount);
        response.put("bans", banCount);
        response.put("positiveNotes", includeNotes ? positiveNotes : null);
        response.put("playtime", playtime);
        response.put("punished", includePunishments ? hasActivePunishment : null);

        if (includeReputation) {
            long scorePlaytime = playtime != null ? playtime : plugin.getPlaytimeHours(uuid);
            int scoreWarningCount = includeWarnings ? warningCount : plugin.getWarningCount(uuid);
            int scoreBanCount = banCount != null ? banCount : plugin.getDataConfig().getInt("bans_count." + uuid, 0);
            int score = (int) (scorePlaytime * 2) - (scoreWarningCount * 15) - (scoreBanCount * 30);
            response.put("score", score);
            response.put("status", score >= 50 ? "Good" : (score <= -50 ? "Bad" : "Neutral"));
        } else {
            response.put("score", null);
            response.put("status", "Restricted");
        }

        return response;
    }

    private long parseModerationTimestamp(String rawTimestamp) {
        if (rawTimestamp == null || rawTimestamp.isBlank()) {
            return 0L;
        }

        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(rawTimestamp.trim()).getTime();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public List<Map<String, Object>> buildTicketsSnapshot(String status, String priority) {
        List<Map<String, Object>> tickets = new ArrayList<>();
        if (!plugin.getTicketConfig().contains("tickets")) {
            return tickets;
        }

        for (String key : plugin.getTicketConfig().getConfigurationSection("tickets").getKeys(false)) {
            if (key.equals("next_id")) continue;
            String ticketStatus = plugin.getTicketConfig().getString("tickets." + key + ".status", "open");
            String ticketPriority = plugin.getTicketConfig().getString("tickets." + key + ".priority", "medium");

            if ((status == null || status.isEmpty() || status.equals(ticketStatus))
                && (priority == null || priority.isEmpty() || priority.equals(ticketPriority))) {
                Map<String, Object> ticket = new HashMap<>();
                ticket.put("id", key);
                ticket.put("player", plugin.getTicketConfig().getString("tickets." + key + ".player"));
                ticket.put("message", plugin.getTicketConfig().getString("tickets." + key + ".message"));
                ticket.put("status", ticketStatus);
                ticket.put("priority", ticketPriority);
                ticket.put("category", plugin.getTicketConfig().getString("tickets." + key + ".category", "other"));
                ticket.put("assignee", plugin.getTicketConfig().getString("tickets." + key + ".assignee", ""));
                ticket.put("time", plugin.getTicketConfig().getString("tickets." + key + ".timestamp"));
                tickets.add(ticket);
            }
        }

        return tickets;
    }

    public List<Map<String, String>> buildMutedSnapshot() {
        List<String> raw = plugin.getDataConfig().getStringList("muted");
        List<Map<String, String>> result = new ArrayList<>();
        for (String entry : raw) {
            Map<String, String> muted = new HashMap<>();
            String[] parts = entry.split("\\|", 3);
            if (parts.length >= 3) {
                muted.put("uuid", parts[0]);
                muted.put("name", parts[1]);
                muted.put("reason", parts[2]);
            } else if (parts.length == 1) {
                String name = Bukkit.getOfflinePlayer(UUID.fromString(parts[0])).getName();
                muted.put("uuid", parts[0]);
                muted.put("name", name != null ? name : parts[0]);
                muted.put("reason", "No reason");
            }
            result.add(muted);
        }
        return result;
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
            ResolvedPlayer resolvedPlayer = resolveKnownPlayer(username);
            if (resolvedPlayer == null) {
                return permissions;
            }

            if (resolvedPlayer.op()) {
                permissions.add("webapp.*");
            }

            UUID uuid = resolvedPlayer.uuid();
            String group = plugin.getPlayerGroup(uuid);
            if (group != null) {
                permissions.addAll(plugin.getRankConfig().getStringList("groups." + group + ".permissions"));
            }
            String rank = plugin.getPlayerRank(uuid);
            if (rank != null) {
                permissions.addAll(plugin.getRankConfig().getStringList("ranks." + rank + ".permissions"));
            }
            return permissions;
        });

        try {
            return future.get();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String getRankHexColor(String rankOrGroup) {
        if (rankOrGroup == null || rankOrGroup.isEmpty()) return "#ffffff";
        String c = plugin.getRankConfig().getString("ranks." + rankOrGroup + ".color");
        if (c != null && !c.isEmpty() && !c.equals("#ffffff") && !c.equals("#aaaaaa")) return c;
        
        c = plugin.getRankConfig().getString("groups." + rankOrGroup + ".color");
        if (c != null && !c.isEmpty() && !c.equals("#ffffff") && !c.equals("#aaaaaa")) return c;

        String pref = plugin.getRankConfig().getString("ranks." + rankOrGroup + ".prefix");
        if (pref == null) pref = plugin.getRankConfig().getString("groups." + rankOrGroup + ".prefix", "");
        return plugin.inferHexColorFromPrefix(pref);
    }

    private Map<String, Object> buildNetworkSettingsSnapshot() {
        FileConfiguration config = plugin.getConfig();

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("enabled", config.getBoolean("network.enabled", false));
        runtime.put("stagingOnly", config.getBoolean("network.staging_only", true));
        runtime.put("proxyEnabled", config.getBoolean("network.proxy.enabled", false));
        runtime.put("sharedDatabaseEnabled", config.getBoolean("network.shared_database.enabled", false));
        runtime.put("livePlayerTransfersEnabled", config.getBoolean("network.routing.live_player_transfers", false));
        runtime.put("profileBackend", plugin.getNetworkProfileService() != null ? plugin.getNetworkProfileService().getBackendName() : "unavailable");
        runtime.put("tokenBackend", plugin.getNetworkTokenService() != null ? plugin.getNetworkTokenService().getBackendName() : "unavailable");
        runtime.put("moderationBackend", plugin.getNetworkModerationService() != null ? plugin.getNetworkModerationService().getBackendName() : "unavailable");
        runtime.put("profileSharedBackendActive", plugin.getNetworkProfileService() != null && plugin.getNetworkProfileService().isSharedBackendEnabled());
        runtime.put("tokenSharedBackendActive", plugin.getNetworkTokenService() != null && plugin.getNetworkTokenService().isSharedBackendEnabled());
        runtime.put("moderationSharedBackendActive", plugin.getNetworkModerationService() != null && plugin.getNetworkModerationService().isSharedBackendEnabled());

        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("type", config.getString("network.proxy.type", "velocity"));
        proxy.put("serverName", config.getString("network.proxy.server_name", "survival"));
        proxy.put("hubServer", config.getString("network.proxy.hub_server", "hub"));
        proxy.put("fallbackServer", config.getString("network.proxy.fallback_server", "survival"));
        proxy.put("pluginChannel", config.getString("network.proxy.plugin_channel", "drowsycraft:network"));

        Map<String, Object> sharedDatabase = new LinkedHashMap<>();
        sharedDatabase.put("provider", config.getString("network.shared_database.provider", "postgresql"));
        sharedDatabase.put("host", config.getString("network.shared_database.host", "127.0.0.1"));
        sharedDatabase.put("port", config.getInt("network.shared_database.port", 5432));
        sharedDatabase.put("database", config.getString("network.shared_database.database", "drowsycraft_staging"));
        sharedDatabase.put("username", config.getString("network.shared_database.username", ""));
        sharedDatabase.put("ssl", config.getBoolean("network.shared_database.ssl", false));
        sharedDatabase.put("tablePrefix", config.getString("network.shared_database.table_prefix", "drowsy_"));
        sharedDatabase.put("poolMaxSize", config.getInt("network.shared_database.pool_max_size", 10));

        Map<String, Object> brand = new LinkedHashMap<>();
        brand.put("displayName", config.getString("network.brand.display_name", "DrowsyCraft Network"));
        brand.put("primaryHubName", config.getString("network.brand.primary_hub_name", "Drowsy Hub"));
        brand.put("survivalLabel", config.getString("network.brand.mode_labels.survival", "Drowsy SMP"));
        brand.put("factionsLabel", config.getString("network.brand.mode_labels.factions", "Drowsy Factions"));
        brand.put("arcadeLabel", config.getString("network.brand.mode_labels.arcade", "Drowsy Arcade"));
        brand.put("eventsLabel", config.getString("network.brand.mode_labels.events", "Drowsy Events"));

        Map<String, Object> progression = new LinkedHashMap<>();
        progression.put("sharedCurrencyName", config.getString("network.progression.shared_currency_name", "Drowsy Tokens"));
        progression.put("sharedProfileEnabled", config.getBoolean("network.progression.shared_profile_enabled", true));
        progression.put("sharedCosmeticsEnabled", config.getBoolean("network.progression.shared_cosmetics_enabled", true));
        progression.put("seasonalPassEnabled", config.getBoolean("network.progression.seasonal_pass_enabled", false));

        Map<String, Object> matchmaking = new LinkedHashMap<>();
        matchmaking.put("arcadeQueueEnabled", config.getBoolean("network.matchmaking.arcade_queue_enabled", true));
        matchmaking.put("arcadeQueueDisplayName", config.getString("network.matchmaking.arcade_queue_display_name", "Arcade Queue"));
        matchmaking.put("rotateModes", new ArrayList<>(config.getStringList("network.matchmaking.rotate_modes")));

        List<Map<String, Object>> modes = normalizeNetworkModes(config.getMapList("network.modes"));

        Map<String, Object> rolloutPhases = new LinkedHashMap<>();
        rolloutPhases.put("phase1", new ArrayList<>(config.getStringList("network.rollout_phases.phase_1")));
        rolloutPhases.put("phase2", new ArrayList<>(config.getStringList("network.rollout_phases.phase_2")));
        rolloutPhases.put("phase3", new ArrayList<>(config.getStringList("network.rollout_phases.phase_3")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runtime", runtime);
        response.put("proxy", proxy);
        response.put("sharedDatabase", sharedDatabase);
        response.put("brand", brand);
        response.put("progression", progression);
        response.put("matchmaking", matchmaking);
        response.put("modes", modes);
        response.put("rolloutPhases", rolloutPhases);
        return response;
    }

    private int parseInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean parseBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private String parseString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private List<String> normalizeStringList(Object rawValue) {
        List<String> values = new ArrayList<>();
        if (rawValue instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item == null) {
                    continue;
                }
                String value = String.valueOf(item).trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private List<Map<String, Object>> normalizeNetworkModes(Object rawValue) {
        List<Map<String, Object>> modes = new ArrayList<>();
        if (!(rawValue instanceof List<?> rawList)) {
            return modes;
        }

        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawMode)) {
                continue;
            }

            String key = parseString(rawMode.get("key"), "");
            if (key.isEmpty()) {
                continue;
            }

            Map<String, Object> mode = new LinkedHashMap<>();
            mode.put("key", key);
            mode.put("name", parseString(rawMode.get("name"), key));
            mode.put("category", parseString(rawMode.get("category"), "network"));
            mode.put("phase", Math.max(1, parseInt(rawMode.get("phase"), 1)));
            mode.put("enabled", parseBoolean(rawMode.get("enabled"), false));
            mode.put("highlights", normalizeStringList(rawMode.get("highlights")));
            modes.add(mode);
        }

        return modes;
    }


    private void setupRoutes() {
        app.get("/api/export/sheets", ctx -> {
            if (!authSheetsExport(ctx)) return;

            String dataset = Optional.ofNullable(ctx.queryParam("dataset")).orElse("players").trim().toLowerCase(Locale.ROOT);
            String format = Optional.ofNullable(ctx.queryParam("format")).orElse("json").trim().toLowerCase(Locale.ROOT);
            if (!Set.of("json", "csv").contains(format)) {
                ctx.status(400).json(Map.of("error", "Unsupported format", "supportedFormats", List.of("json", "csv")));
                return;
            }

            Future<List<LinkedHashMap<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> buildSheetsExportRows(dataset));

            try {
                List<LinkedHashMap<String, Object>> rows = future.get();
                if ("csv".equals(format)) {
                    ctx.contentType("text/csv; charset=utf-8");
                    ctx.header("Content-Disposition", "inline; filename=\"realmtool-" + dataset + ".csv\"");
                    ctx.result(toCsv(rows));
                    return;
                }

                ctx.json(Map.of(
                    "dataset", dataset,
                    "format", format,
                    "generatedAt", java.time.Instant.now().toString(),
                    "rowCount", rows.size(),
                    "rows", rows
                ));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IllegalArgumentException) {
                    ctx.status(400).json(Map.of(
                        "error", cause.getMessage(),
                        "supportedDatasets", List.of("players", "economy", "tickets", "appeals", "staff-hours")
                    ));
                    return;
                }
                ctx.status(500).json(Map.of("error", "Failed to build export", "message", cause != null ? cause.getMessage() : "Unknown error"));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of(
                    "error", e.getMessage(),
                    "supportedDatasets", List.of("players", "economy", "tickets", "appeals", "staff-hours")
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Failed to build export", "message", e.getMessage()));
            }
        });

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

                ResolvedPlayer resolvedPlayer = resolveKnownPlayer(username);
                if (resolvedPlayer == null) {
                    return Map.of("error", "Player not found. You must have played on the server before.");
                }

                boolean hasAccess = resolvedPlayer.op();
                UUID uuid = resolvedPlayer.uuid();
                if (!hasAccess) {
                    String group = plugin.getPlayerGroup(uuid);
                    if (group != null) {
                        List<String> perms = plugin.getRankConfig().getStringList("groups." + group + ".permissions");
                        if (perms.contains("webapp.access") || perms.contains("webapp.*")) hasAccess = true;
                    }
                }
                if (!hasAccess) {
                    String rank = plugin.getPlayerRank(uuid);
                    if (rank != null) {
                        List<String> perms = plugin.getRankConfig().getStringList("ranks." + rank + ".permissions");
                        if (perms.contains("webapp.access") || perms.contains("webapp.*")) hasAccess = true;
                    }
                }

                if (hasAccess) {
                    String resolvedName = resolvedPlayer.name() != null ? resolvedPlayer.name() : username;
                    return Map.of("name", resolvedName);
                }

                return Map.of("error", "You do not have permission (webapp.access) to log in.");
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

            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, this::buildPlayersSnapshot);
            ctx.json(future.get());
        });

        app.get("/api/staff-hours", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.players")) {
                ctx.status(403).result("Forbidden");
                return;
            }

            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> response = new HashMap<>();
                response.put("staff", plugin.getStaffHourSummaryData());
                response.put("trackedWindows", List.of("24h", "7d", "14d"));
                return response;
            });
            ctx.json(future.get());
        });

        // Ticket endpoints moved to WebTicketController
        ticketController.registerRoutes(app);

        app.get("/api/notes", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.notes")) return;
            String player = ctx.queryParam("player");
            
            // If no player specified, return all players that have notes
            if (player == null || player.isEmpty()) {
                Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Map.Entry<UUID, List<String>> noteEntry : plugin.getAllPlayerNotes().entrySet()) {
                        try {
                            UUID uuid = noteEntry.getKey();
                            List<String> notes = noteEntry.getValue();
                            if (notes == null || notes.isEmpty()) continue;
                            NetworkPlayerProfile profile = resolvePlayerProfile(uuid, false);
                            String key = uuid.toString();
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("player", profile.lastSeenName() != null ? profile.lastSeenName() : key);
                            entry.put("uuid", key);
                            entry.put("count", notes.size());
                            String latest = notes.get(notes.size() - 1);
                            if (latest.contains(" | ")) {
                                entry.put("lastUpdated", latest.split(" \\| ", 2)[0].trim());
                            } else {
                                entry.put("lastUpdated", "Unknown");
                            }
                            result.add(entry);
                        } catch (Exception ignored) {
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
                List<String> notesList = plugin.getPlayerNotes(uuid);
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
                List<String> notes = plugin.getPlayerNotes(uuid);
                if (notes != null && index >= 0 && index < notes.size()) {
                    notes.set(index, newText);
                    plugin.savePlayerNotes(uuid, notes);
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
                List<String> notes = plugin.getPlayerNotes(uuid);
                if (notes != null && index >= 0 && index < notes.size()) {
                    notes.remove(index);
                    plugin.savePlayerNotes(uuid, notes);
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
            Future<List<Map<String, String>>> future = Bukkit.getScheduler().callSyncMethod(plugin, this::buildChatSnapshot);
            ctx.json(future.get());
        });

        app.get("/api/banned", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.banned")) return;
            
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> banned = new ArrayList<>();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                try {
                    for (org.bukkit.OfflinePlayer p : Bukkit.getBannedPlayers()) {
                        Map<String, Object> map = new HashMap<>();
                        String name = p.getName() != null ? p.getName() : (p.getUniqueId() != null ? p.getUniqueId().toString() : "Unknown");
                        map.put("name", name);
                        map.put("target", name);

                        String reason = "No reason";
                        String source = "Unknown";
                        String created = "Unknown";
                        String expiration = "Never";

                        try {
                            org.bukkit.BanEntry entry = null;
                            try {
                                entry = (org.bukkit.BanEntry) Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntry(p.getName());
                            } catch (Throwable ignored) {}

                            if (entry != null) {
                                reason = entry.getReason() != null ? entry.getReason() : reason;
                                source = entry.getSource() != null ? entry.getSource() : source;
                                created = entry.getCreated() != null ? sdf.format(entry.getCreated()) : created;
                                expiration = entry.getExpiration() != null ? sdf.format(entry.getExpiration()) : expiration;
                            }
                        } catch (Exception ignored) {}

                        map.put("reason", reason);
                        map.put("source", source);
                        map.put("created", created);
                        map.put("expiration", expiration);
                        banned.add(map);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error parsing banned players: " + e.getMessage());
                }

                try {
                    org.bukkit.BanList ipBanList = Bukkit.getBanList(org.bukkit.BanList.Type.IP);
                    for (Object obj : ipBanList.getEntries()) {
                        org.bukkit.BanEntry entry = (org.bukkit.BanEntry) obj;
                        String ip = entry.getTarget() != null ? entry.getTarget().toString() : null;
                        if (ip == null || ip.isEmpty() || banned.stream().anyMatch(m -> ip.equals(m.get("name")))) continue;
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", ip);
                        map.put("target", ip);
                        map.put("reason", entry.getReason() != null ? entry.getReason() : "No reason");
                        map.put("source", entry.getSource() != null ? entry.getSource() : "Unknown");
                        map.put("created", entry.getCreated() != null ? sdf.format(entry.getCreated()) : "Unknown");
                        map.put("expiration", entry.getExpiration() != null ? sdf.format(entry.getExpiration()) : "Never");
                        banned.add(map);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error parsing IP bans: " + e.getMessage());
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
            Integer minutesParam = ctx.queryParamAsClass("minutes", Integer.class).getOrDefault(null);
            
            // Try to get params from JSON body if not in query params
            if (targetNameParam == null || reasonParam == null || minutesParam == null) {
                try {
                    String body = ctx.body();
                    if (body != null && !body.isEmpty()) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.Map<String, Object> bodyMap = mapper.readValue(body, java.util.Map.class);
                        
                        String extractedName = null;
                        for (String key : new String[]{"player", "target", "name"}) {
                            Object obj = bodyMap.get(key);
                            if (obj != null) {
                                if (obj instanceof java.util.Map) {
                                    Object nested = ((java.util.Map<?, ?>) obj).get("name");
                                    if (nested == null) nested = ((java.util.Map<?, ?>) obj).get("target");
                                    if (nested != null) extractedName = String.valueOf(nested);
                                } else if (obj instanceof java.util.List && !((java.util.List<?>) obj).isEmpty()) {
                                    extractedName = String.valueOf(((java.util.List<?>) obj).get(0));
                                } else {
                                    extractedName = String.valueOf(obj);
                                }
                                if (extractedName != null && !extractedName.isEmpty()) break;
                            }
                        }
                        if (targetNameParam == null) targetNameParam = extractedName;
                        if (reasonParam == null && bodyMap.get("reason") != null) reasonParam = String.valueOf(bodyMap.get("reason"));
                        if (minutesParam == null && bodyMap.get("minutes") != null) {
                            try {
                                minutesParam = Integer.parseInt(String.valueOf(bodyMap.get("minutes")));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                } catch (Exception e) {
                    // Continue with null params
                }
            }
            
            if (reasonParam == null) reasonParam = ctx.queryParam("value");
            if (reasonParam == null) reasonParam = ctx.queryParam("message");
            
            final String targetName = targetNameParam;
            final String val = reasonParam;
            final Integer minutes = minutesParam;

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
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + targetName + " " + banReason);
                        if (p != null) p.kickPlayer(ChatColor.RED + "You have been banned: " + banReason);
                        plugin.addChatLog("System", "[BAN] " + targetName + ": " + banReason);
                        plugin.logAction("WebAdmin", "banned", targetName + " (" + banReason + ")");
                        plugin.fireDiscordEvent("bans", "Player Banned", "**" + targetName + "** was banned.\nReason: " + banReason, 0xe74c3c, targetName);
                    }
                    else if (action.equals("unban")) {
                        // Use the built-in vanilla command to cleanly resolve and remove the profile/name ban
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + targetName);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon-ip " + targetName);
                        try { plugin.removePunishment(uuid); } catch (Exception ignored) {}
                        plugin.logAction("WebAdmin", "unbanned", targetName);
                    }
                    else if (action.equals("warn")) {
                        String warnReason = val != null ? val : "No reason";
                        plugin.addWarning(uuid, warnReason, "WebAdmin");
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
                        long duration = 3600000L;
                        if (minutes != null && minutes > 0) {
                            duration = minutes.longValue() * 60000L;
                        } else if ("3h".equalsIgnoreCase(val)) {
                            duration = 10800000L;
                        } else if ("24h".equalsIgnoreCase(val)) {
                            duration = 86400000L;
                        }
                        String punishmentReason = val != null && !val.isBlank()
                            ? val
                            : "Punished via web panel" + (minutes != null && minutes > 0 ? " (" + minutes + " min)" : "");
                        plugin.setPunished(uuid, duration, punishmentReason, "WebAdmin");
                        plugin.logAction("WebAdmin", "punished", targetName + " (" + punishmentReason + ")");
                    }
                    else if (action.equals("unpunish")) {
                    plugin.removePunishment(uuid);
                        plugin.logAction("WebAdmin", "unpunished", targetName);
                    }
                    else if (action.equals("addnote")) {
                        List<String> notes = plugin.getPlayerNotes(uuid);
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
                        plugin.savePlayerNotes(uuid, notes);
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

        // Ticket endpoints moved to WebTicketController

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

        app.get("/api/warnings", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.warnings")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, this::buildWarningsSnapshot);
            ctx.json(future.get());
        });

        app.post("/api/warnings", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.warn")) return;

            Map<String, Object> body;
            try {
                body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(ctx.body(), java.util.Map.class);
            } catch (Exception exception) {
                ctx.status(400).result("Invalid request body");
                return;
            }

            String player = body.get("player") != null ? String.valueOf(body.get("player")).trim() : "";
            String reason = body.get("reason") != null ? String.valueOf(body.get("reason")).trim() : "";
            String issuedBy = body.get("issuedBy") != null ? String.valueOf(body.get("issuedBy")).trim() : "WebAdmin";
            if (player.isEmpty() || reason.isEmpty()) {
                ctx.status(400).result("Missing player or reason");
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                plugin.addWarning(uuid, reason, issuedBy.isEmpty() ? "WebAdmin" : issuedBy);
                Player onlinePlayer = Bukkit.getPlayer(player);
                if (onlinePlayer != null) {
                    onlinePlayer.sendMessage(ChatColor.YELLOW + "You have been warned: " + reason);
                }
                plugin.addChatLog("System", "[WARNING] " + player + ": " + reason);
                plugin.logAction("WebAdmin", "warned", player + " (" + reason + ")");
                plugin.fireDiscordEvent("warns", "Player Warned", "**" + player + "** was warned.\nReason: " + reason, 0xf1c40f, player);
            });
            ctx.json(Map.of("status", true));
        });

        app.get("/api/moderation/timeline", ctx -> {
            if (!auth(ctx)) return;

            String authToken = ctx.header("Authorization");
            boolean canViewReputation = hasPermission(authToken, "webapp.view.reputation");
            boolean canViewWarnings = hasPermission(authToken, "webapp.view.warnings");
            boolean canViewNotes = hasPermission(authToken, "webapp.view.notes");
            boolean canViewPunishments = hasPermission(authToken, "webapp.view.players");
            boolean canViewMutes = hasPermission(authToken, "webapp.view.mutes");
            boolean canViewBans = hasPermission(authToken, "webapp.view.banned");

            String player = ctx.queryParam("player");
            if (player == null || player.isBlank()) {
                ctx.status(400).result("Missing player");
                return;
            }

            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () ->
                buildModerationTimelineSnapshot(
                    player.trim(),
                    canViewReputation,
                    canViewWarnings || canViewReputation,
                    canViewNotes,
                    canViewPunishments || canViewReputation,
                    canViewMutes || canViewReputation,
                    canViewBans || canViewReputation,
                    canViewPunishments || canViewReputation
                )
            );
            ctx.json(future.get());
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
                    if (players == null && bodyMap.get("players") != null) {
                        Object pObj = bodyMap.get("players");
                        if (pObj instanceof java.util.List) {
                            java.util.List<?> list = (java.util.List<?>) pObj;
                            java.util.List<String> strList = new ArrayList<>();
                            for (Object o : list) {
                                if (o instanceof java.util.Map) {
                                    Object nested = ((java.util.Map<?, ?>) o).get("name");
                                    if (nested == null) nested = ((java.util.Map<?, ?>) o).get("target");
                                    if (nested != null) strList.add(String.valueOf(nested));
                                } else {
                                    strList.add(String.valueOf(o));
                                }
                            }
                            players = String.join(",", strList);
                        } else {
                            players = String.valueOf(pObj);
                        }
                    }
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
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + target + " " + (fReason != null ? fReason : ""));
                            Player pl = Bukkit.getPlayer(target);
                            if (pl != null) pl.kickPlayer(ChatColor.RED + "Banned: " + (fReason != null ? fReason : ""));
                        } else if ("unban".equals(fAction)) {
                                // Use the built-in vanilla command to cleanly resolve and remove the profile/name ban
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + target);
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon-ip " + target);
                                try { plugin.removePunishment(uuid); } catch (Exception ignored) {}
                        } else if ("kick".equals(fAction)) {
                            Player pl = Bukkit.getPlayer(target);
                            if (pl != null) pl.kickPlayer(ChatColor.RED + (fReason != null ? fReason : "Kicked"));
                        } else if ("warn".equals(fAction)) {
                            plugin.addWarning(uuid, fReason != null ? fReason : "No reason", "WebAdmin");
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
                res.put("webhook_faction", plugin.getDataConfig().getString("discord.webhook_faction", ""));
                res.put("bans", plugin.getDataConfig().getBoolean("discord.bans", true));
                res.put("warns", plugin.getDataConfig().getBoolean("discord.warns", true));
                res.put("reports", plugin.getDataConfig().getBoolean("discord.reports", true));
                res.put("factions", plugin.getDataConfig().getBoolean("discord.factions", false));
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
                String[] boolKeys = {"bans", "warns", "reports", "factions", "joins", "leaves", "deaths",
                    "block_logging", "container_logging", "command_logging",
                    "milestone_alerts", "performance_alerts", "health_check", "daily_summary"};
                String[] webhookKeys = {"webhook_ban", "webhook_warn", "webhook_report", "webhook_faction"};

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

        // --- DROWSYCRAFT NETWORK ---
        app.get("/api/network", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.network")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, this::buildNetworkSettingsSnapshot);
            ctx.json(future.get());
        });

        app.post("/api/network", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.manage.network")) return;

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = mapper.readValue(ctx.body(), Map.class);

            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<?, ?> runtime = body.get("runtime") instanceof Map<?, ?> value ? value : Map.of();
                Map<?, ?> proxy = body.get("proxy") instanceof Map<?, ?> value ? value : Map.of();
                Map<?, ?> sharedDatabase = body.get("sharedDatabase") instanceof Map<?, ?> value ? value : Map.of();
                Map<?, ?> brand = body.get("brand") instanceof Map<?, ?> value ? value : Map.of();
                Map<?, ?> progression = body.get("progression") instanceof Map<?, ?> value ? value : Map.of();
                Map<?, ?> matchmaking = body.get("matchmaking") instanceof Map<?, ?> value ? value : Map.of();
                Map<?, ?> rolloutPhases = body.get("rolloutPhases") instanceof Map<?, ?> value ? value : Map.of();

                FileConfiguration config = plugin.getConfig();
                config.set("network.enabled", parseBoolean(runtime.get("enabled"), false));
                config.set("network.staging_only", parseBoolean(runtime.get("stagingOnly"), true));
                config.set("network.proxy.enabled", parseBoolean(runtime.get("proxyEnabled"), false));
                config.set("network.shared_database.enabled", parseBoolean(runtime.get("sharedDatabaseEnabled"), false));
                config.set("network.routing.live_player_transfers", parseBoolean(runtime.get("livePlayerTransfersEnabled"), false));
                config.set("network.proxy.type", parseString(proxy.get("type"), "velocity"));
                config.set("network.proxy.server_name", parseString(proxy.get("serverName"), "survival"));
                config.set("network.proxy.hub_server", parseString(proxy.get("hubServer"), "hub"));
                config.set("network.proxy.fallback_server", parseString(proxy.get("fallbackServer"), "survival"));
                config.set("network.proxy.plugin_channel", parseString(proxy.get("pluginChannel"), "drowsycraft:network"));
                config.set("network.shared_database.provider", parseString(sharedDatabase.get("provider"), "postgresql"));
                config.set("network.shared_database.host", parseString(sharedDatabase.get("host"), "127.0.0.1"));
                config.set("network.shared_database.port", parseInt(sharedDatabase.get("port"), 5432));
                config.set("network.shared_database.database", parseString(sharedDatabase.get("database"), "drowsycraft_staging"));
                config.set("network.shared_database.username", parseString(sharedDatabase.get("username"), ""));
                config.set("network.shared_database.ssl", parseBoolean(sharedDatabase.get("ssl"), false));
                config.set("network.shared_database.table_prefix", parseString(sharedDatabase.get("tablePrefix"), "drowsy_"));
                config.set("network.shared_database.pool_max_size", parseInt(sharedDatabase.get("poolMaxSize"), 10));
                config.set("network.brand.display_name", parseString(brand.get("displayName"), "DrowsyCraft Network"));
                config.set("network.brand.primary_hub_name", parseString(brand.get("primaryHubName"), "Drowsy Hub"));
                config.set("network.brand.mode_labels.survival", parseString(brand.get("survivalLabel"), "Drowsy SMP"));
                config.set("network.brand.mode_labels.factions", parseString(brand.get("factionsLabel"), "Drowsy Factions"));
                config.set("network.brand.mode_labels.arcade", parseString(brand.get("arcadeLabel"), "Drowsy Arcade"));
                config.set("network.brand.mode_labels.events", parseString(brand.get("eventsLabel"), "Drowsy Events"));

                config.set("network.progression.shared_currency_name", parseString(progression.get("sharedCurrencyName"), "Drowsy Tokens"));
                config.set("network.progression.shared_profile_enabled", parseBoolean(progression.get("sharedProfileEnabled"), true));
                config.set("network.progression.shared_cosmetics_enabled", parseBoolean(progression.get("sharedCosmeticsEnabled"), true));
                config.set("network.progression.seasonal_pass_enabled", parseBoolean(progression.get("seasonalPassEnabled"), false));

                config.set("network.matchmaking.arcade_queue_enabled", parseBoolean(matchmaking.get("arcadeQueueEnabled"), true));
                config.set("network.matchmaking.arcade_queue_display_name", parseString(matchmaking.get("arcadeQueueDisplayName"), "Arcade Queue"));
                config.set("network.matchmaking.rotate_modes", normalizeStringList(matchmaking.get("rotateModes")));

                List<Map<String, Object>> modes = normalizeNetworkModes(body.get("modes"));
                if (!modes.isEmpty()) {
                    config.set("network.modes", modes);
                }
                config.set("network.rollout_phases.phase_1", normalizeStringList(rolloutPhases.get("phase1")));
                config.set("network.rollout_phases.phase_2", normalizeStringList(rolloutPhases.get("phase2")));
                config.set("network.rollout_phases.phase_3", normalizeStringList(rolloutPhases.get("phase3")));
                plugin.saveConfig();
                plugin.logAction("WebAdmin", "updated", "network settings");
                return Map.of("success", true, "network", buildNetworkSettingsSnapshot());
            });

            ctx.json(future.get());
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

        // --- FACTIONS ---
        app.get("/api/factions", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.factions")) return;
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Map<String, Object> result = new LinkedHashMap<>();
                var factionService = plugin.getFactionService();
                if (factionService == null) {
                    result.put("factions", List.of());
                    result.put("raidAlerts", List.of());
                    return result;
                }
                result.put("factions", factionService.getFactionSummaries());
                result.put("raidAlerts", factionService.getRecentRaidAlerts(25));
                return result;
            });
            ctx.json(future.get());
        });

        app.get("/api/factions/{name}/logs", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.factions")) return;
            String factionName = ctx.pathParam("name");
            Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                var factionService = plugin.getFactionService();
                List<String> logs = factionService == null ? List.of() : factionService.getFactionLogsByName(factionName, 50);
                return Map.of(
                    "name", factionName,
                    "logs", logs
                );
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
            enchants.add(Map.of("name", "Excavator", "description", "Mines a 3x3 area (Pickaxes only)"));
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
                    for (Map.Entry<?, ?> entry : r.entrySet()) {
                        if (entry.getValue() != null) m.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
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
                    for (Map.Entry<?, ?> entry : r.entrySet()) {
                        if (entry.getValue() != null) m.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
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
                    for (Map.Entry<?, ?> entry : r.entrySet()) {
                        if (entry.getValue() != null) m.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
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
                    for (Map.Entry<?, ?> entry : r.entrySet()) {
                        if (entry.getValue() != null) m.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
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
                    int warnings = plugin.getWarningCount(uuid);
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
                    int warnings = plugin.getWarningCount(uuid);
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
                for (UUID uuid : plugin.getAllWarnings().keySet()) {
                    try {
                        String key = uuid.toString();
                        String name = data.getString("last_seen_name." + key, Bukkit.getOfflinePlayer(uuid).getName());
                        if (name == null || seen.contains(name)) continue;
                        seen.add(name);
                        int warnings = plugin.getWarningCount(uuid);
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

                // Offline players with playtime but no warnings
                if (data.getConfigurationSection("playtime") != null) {
                    for (String key : data.getConfigurationSection("playtime").getKeys(false)) {
                        try {
                            UUID uuid = UUID.fromString(key);
                            String name = data.getString("last_seen_name." + key, Bukkit.getOfflinePlayer(uuid).getName());
                            if (name == null || seen.contains(name)) continue;
                            seen.add(name);
                            int warnings = plugin.getWarningCount(uuid);
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

        // ========== ECONOMY DASHBOARD ==========
        app.get("/api/economy", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.analytics")) return;
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> list = new ArrayList<>();
                var data = plugin.getDataConfig();
                var economyData = plugin.getEconomyConfig();
                if (economyData.contains("coins")) {
                    for (String uuid : economyData.getConfigurationSection("coins").getKeys(false)) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("uuid", uuid);
                        map.put("balance", economyData.getLong("coins." + uuid, 0));
                        map.put("earned", 0);
                        map.put("spent", 0);
                        try {
                            String name = data.getString("last_seen_name." + uuid, Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName());
                            map.put("name", name != null ? name : uuid);
                        } catch (Exception e) { map.put("name", uuid); }
                        list.add(map);
                    }
                }
                list.sort((a, b) -> Long.compare((long)b.get("balance"), (long)a.get("balance")));
                return list;
            });
            try {
                ctx.json(Map.of("players", future.get()));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Failed to fetch economy data"));
            }
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
                for (Map.Entry<?, ?> entry : h.entrySet()) {
                    if (entry.getValue() != null) m.put(String.valueOf(entry.getKey()), entry.getValue());
                }
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
                var section = plugin.getRankConfig().getConfigurationSection("groups");
                if (section != null) {
                    plugin.getLogger().info("[DEBUG] groups section: " + section.getKeys(false));
                    for (String name : section.getKeys(false)) {
                        Map<String, Object> g = new HashMap<>();
                        g.put("name", name);
                        String c = plugin.getRankConfig().getString("groups." + name + ".color");
                        if (c == null || c.isEmpty() || c.equals("#ffffff") || c.equals("#aaaaaa")) {
                            String inf = plugin.inferHexColorFromPrefix(plugin.getRankConfig().getString("groups." + name + ".prefix", ""));
                            if (!inf.equals("#ffffff")) c = inf;
                        }
                        g.put("color", c);
                        g.put("prefix", plugin.getRankConfig().getString("groups." + name + ".prefix", ""));
                        g.put("permissions", plugin.getRankConfig().getStringList("groups." + name + ".permissions"));
                        List<String> memberUuids = plugin.getRankConfig().getStringList("groups." + name + ".members");
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
                if (plugin.getRankConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group already exists");
                }
                plugin.getRankConfig().set("groups." + finalName + ".color", finalColor);
                plugin.getRankConfig().set("groups." + finalName + ".prefix", finalPrefix);
                plugin.getRankConfig().set("groups." + finalName + ".permissions", new ArrayList<String>());
                plugin.getRankConfig().set("groups." + finalName + ".members", new ArrayList<String>());
                plugin.saveRankFile();
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
                if (!plugin.getRankConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group not found");
                }
                plugin.getRankConfig().set("groups." + finalName, null);
                plugin.saveRankFile();
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
            Object permissions = body.get("permissions");
            if (name == null || name.isBlank()) { ctx.status(400).json(Map.of("error", "Name required")); return; }
            String finalName = name;
            String finalColor = color;
            String finalPrefix = prefix;
            Object finalPermissions = permissions;
            Future<Map<String, Object>> future7 = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (!plugin.getRankConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group not found");
                }
                if (finalColor != null) plugin.getRankConfig().set("groups." + finalName + ".color", finalColor);
                if (finalPrefix != null) plugin.getRankConfig().set("groups." + finalName + ".prefix", finalPrefix);
                if (finalPermissions != null) {
                    List<String> permissionList = new ArrayList<>();
                    if (finalPermissions instanceof String) {
                        for (String permission : ((String) finalPermissions).split(",")) {
                            String trimmed = permission.trim();
                            if (!trimmed.isEmpty()) permissionList.add(trimmed);
                        }
                    } else if (finalPermissions instanceof List<?>) {
                        for (Object permission : (List<?>) finalPermissions) {
                            if (permission != null) {
                                String trimmed = String.valueOf(permission).trim();
                                if (!trimmed.isEmpty()) permissionList.add(trimmed);
                            }
                        }
                    }
                    plugin.getRankConfig().set("groups." + finalName + ".permissions", permissionList);
                }
                plugin.saveRankFile();
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
                if (!plugin.getRankConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group not found");
                }
                List<String> perms = new ArrayList<>(plugin.getRankConfig().getStringList("groups." + finalName + ".permissions"));
                if (perms.contains(finalPerm)) return Map.of("error", (Object) "Permission already exists");
                perms.add(finalPerm);
                plugin.getRankConfig().set("groups." + finalName + ".permissions", perms);
                plugin.saveRankFile();
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
                List<String> perms = new ArrayList<>(plugin.getRankConfig().getStringList("groups." + finalName + ".permissions"));
                perms.remove(finalPerm);
                plugin.getRankConfig().set("groups." + finalName + ".permissions", perms);
                plugin.saveRankFile();
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
                if (!plugin.getRankConfig().contains("groups." + finalName)) {
                    return Map.of("error", (Object) "Group not found");
                }
                // Resolve player UUID
                Player online = Bukkit.getPlayerExact(finalPlayer);
                UUID uuid = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(finalPlayer).getUniqueId();
                String uuidStr = uuid.toString();
                // Remove from any existing group first
                var groupsSection = plugin.getRankConfig().getConfigurationSection("groups");
                if (groupsSection != null) {
                    for (String gn : groupsSection.getKeys(false)) {
                        List<String> gMembers = new ArrayList<>(plugin.getRankConfig().getStringList("groups." + gn + ".members"));
                        if (gMembers.remove(uuidStr)) {
                            plugin.getRankConfig().set("groups." + gn + ".members", gMembers);
                        }
                    }
                }
                // Add to new group
                List<String> members = new ArrayList<>(plugin.getRankConfig().getStringList("groups." + finalName + ".members"));
                if (!members.contains(uuidStr)) members.add(uuidStr);
                plugin.getRankConfig().set("groups." + finalName + ".members", members);
                // Update last_seen_name for this uuid
                plugin.getDataConfig().set("last_seen_name." + uuidStr, finalPlayer);
                plugin.saveDataFile();
                plugin.saveRankFile();
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
                List<String> members = new ArrayList<>(plugin.getRankConfig().getStringList("groups." + finalName + ".members"));
                members.remove(finalUuid);
                plugin.getRankConfig().set("groups." + finalName + ".members", members);
                plugin.saveRankFile();
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
        // Rank endpoints moved to WebRankController
        rankController.registerRoutes(app);


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
                    String rank = plugin.getPlayerRank(p.getUniqueId());
                    if (rank == null) rank = plugin.getPlayerGroup(p.getUniqueId());
                    m.put("rank", rank);
                    m.put("rankColor", getRankHexColor(rank));
                    m.put("color", getRankHexColor(rank));
                    m.put("promotedBy", plugin.getDataConfig().getString("users." + p.getUniqueId() + ".promotedBy"));
                    m.put("promotionDate", plugin.getDataConfig().getLong("users." + p.getUniqueId() + ".promotionDate"));
                    players.add(m);
                }
                
                // Offline (from player_rank assignment)
                var playerRankSection = plugin.getRankConfig().getConfigurationSection("player_rank");
                if (playerRankSection != null) {
                    for (String uuidStr : playerRankSection.getKeys(false)) {
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            if (seen.contains(uuid)) continue;
                            seen.add(uuid);
                            String rank = plugin.getRankConfig().getString("player_rank." + uuidStr);
                            String name = plugin.getDataConfig().getString("last_seen_name." + uuidStr, Bukkit.getOfflinePlayer(uuid).getName());
                            Map<String, Object> m = new HashMap<>();
                            m.put("name", name != null ? name : uuidStr);
                            m.put("uuid", uuidStr);
                            if (rank == null) rank = plugin.getPlayerGroup(uuid);
                            m.put("rank", rank);
                            m.put("rankColor", getRankHexColor(rank));
                            m.put("color", getRankHexColor(rank));
                            m.put("promotedBy", plugin.getDataConfig().getString("users." + uuidStr + ".promotedBy"));
                            m.put("promotionDate", plugin.getDataConfig().getLong("users." + uuidStr + ".promotionDate"));
                            players.add(m);
                        } catch (Exception ignored) {}
                    }
                }
                
                // Offline (from ranks membership lists)
                var ranksSection = plugin.getRankConfig().getConfigurationSection("ranks");
                if (ranksSection != null) {
                    for (String g : ranksSection.getKeys(false)) {
                        List<String> members = plugin.getRankConfig().getStringList("ranks." + g + ".members");
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
                                m.put("rankColor", getRankHexColor(g));
                                m.put("color", getRankHexColor(g));
                                m.put("promotedBy", plugin.getDataConfig().getString("users." + uuidStr + ".promotedBy"));
                                m.put("promotionDate", plugin.getDataConfig().getLong("users." + uuidStr + ".promotionDate"));
                                players.add(m);
                            } catch (Exception ignored) {}
                        }
                    }
                }
                
                // Offline (from groups membership lists)
                var groupsSection = plugin.getRankConfig().getConfigurationSection("groups");
                if (groupsSection != null) {
                    for (String g : groupsSection.getKeys(false)) {
                        List<String> members = plugin.getRankConfig().getStringList("groups." + g + ".members");
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
                                m.put("rankColor", getRankHexColor(g));
                                m.put("color", getRankHexColor(g));
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

        app.get("/api/punishments", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.players")) return;
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, this::buildPunishmentsSnapshot);
            ctx.json(future.get());
        });

        app.post("/api/punishments/create", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.punish")) return;

            Map<String, Object> body;
            try {
                body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(ctx.body(), java.util.Map.class);
            } catch (Exception exception) {
                ctx.status(400).result("Invalid request body");
                return;
            }

            String player = body.get("player") != null ? String.valueOf(body.get("player")).trim() : "";
            String reason = body.get("reason") != null ? String.valueOf(body.get("reason")).trim() : "";
            int durationMinutes;
            try {
                durationMinutes = Integer.parseInt(String.valueOf(body.getOrDefault("duration", "0")));
            } catch (NumberFormatException exception) {
                ctx.status(400).result("Invalid duration");
                return;
            }

            if (player.isEmpty() || durationMinutes <= 0) {
                ctx.status(400).result("Missing player or duration");
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                plugin.setPunished(uuid, durationMinutes * 60000L, reason.isEmpty() ? "Punished via web panel" : reason, "WebAdmin");
                plugin.logAction("WebAdmin", "punished", player + " (" + (reason.isEmpty() ? "Punished via web panel" : reason) + ")");
            });
            ctx.result("OK");
        });

        app.post("/api/punishments/remove", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.action.punish")) return;

            Map<String, Object> body;
            try {
                body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(ctx.body(), java.util.Map.class);
            } catch (Exception exception) {
                ctx.status(400).result("Invalid request body");
                return;
            }

            String player = body.get("player") != null ? String.valueOf(body.get("player")).trim() : "";
            if (player.isEmpty()) {
                ctx.status(400).result("Missing player");
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                UUID uuid = Bukkit.getOfflinePlayer(player).getUniqueId();
                plugin.removePunishment(uuid);
                plugin.logAction("WebAdmin", "unpunished", player);
            });
            ctx.result("OK");
        });

        app.get("/api/search", ctx -> {
            if (!auth(ctx) || !hasPermission(ctx.header("Authorization"), "webapp.view.players")) return;
            String nameFilter = Optional.ofNullable(ctx.queryParam("name")).orElse("").trim().toLowerCase();
            double playtimeMin = ctx.queryParamAsClass("playtimeMin", Double.class).getOrDefault(0.0);
            double playtimeMax = ctx.queryParamAsClass("playtimeMax", Double.class).getOrDefault(Double.MAX_VALUE);
            int warningsMin = ctx.queryParamAsClass("warnings", Integer.class).getOrDefault(0);
            String bannedFilter = Optional.ofNullable(ctx.queryParam("banned")).orElse("").trim().toLowerCase();

            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Set<UUID> seen = new HashSet<>();
                List<Map<String, Object>> results = new ArrayList<>();
                var data = plugin.getDataConfig();

                Set<String> candidateUuids = new HashSet<>();
                var lastSeen = data.getConfigurationSection("last_seen_name");
                if (lastSeen != null) candidateUuids.addAll(lastSeen.getKeys(false));
                var playtime = data.getConfigurationSection("playtime");
                if (playtime != null) candidateUuids.addAll(playtime.getKeys(false));
                for (UUID warnedPlayer : plugin.getAllWarnings().keySet()) {
                    candidateUuids.add(warnedPlayer.toString());
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    candidateUuids.add(player.getUniqueId().toString());
                }

                for (String uuidStr : candidateUuids) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        if (!seen.add(uuid)) continue;

                        String playerName = data.getString("last_seen_name." + uuidStr, Bukkit.getOfflinePlayer(uuid).getName());
                        if (playerName == null || playerName.isBlank()) playerName = uuidStr;
                        double playtimeHours = plugin.getPlaytimeHours(uuid);
                        int warningCount = plugin.getWarningCount(uuid);
                        boolean banned = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(playerName);
                        boolean punished = plugin.isPunished(uuid);
                        int reputation = Math.max(-100, 100 - (warningCount * 10) - (banned ? 40 : 0) - (punished ? 20 : 0));

                        if (!nameFilter.isEmpty() && !playerName.toLowerCase().contains(nameFilter)) continue;
                        if (playtimeHours < playtimeMin || playtimeHours > playtimeMax) continue;
                        if (warningCount < warningsMin) continue;
                        if (!bannedFilter.isEmpty()) {
                            boolean mustBeBanned = bannedFilter.equals("true") || bannedFilter.equals("yes");
                            boolean mustBeClear = bannedFilter.equals("false") || bannedFilter.equals("no");
                            if (mustBeBanned && !banned) continue;
                            if (mustBeClear && banned) continue;
                        }

                        Map<String, Object> row = new HashMap<>();
                        row.put("name", playerName);
                        row.put("playtime", playtimeHours);
                        row.put("warnings", warningCount);
                        row.put("reputation", reputation);
                        row.put("banned", banned);
                        results.add(row);
                    } catch (Exception ignored) {}
                }

                results.sort((left, right) -> Double.compare(
                    ((Number) right.get("playtime")).doubleValue(),
                    ((Number) left.get("playtime")).doubleValue()
                ));
                return results;
            });

            ctx.json(future.get());
        });
    }

    public boolean isRunning() { return app != null; }

    public void stop() {
        if (liveBroadcastTask != null) {
            liveBroadcastTask.cancel();
            liveBroadcastTask = null;
        }
        for (WsContext session : new ArrayList<>(liveSessions.keySet())) {
            try { session.session.close(); } catch (Exception ignored) {}
        }
        liveSessions.clear();
        liveSessionSignatures.clear();
        if (app != null) app.stop();
    }

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
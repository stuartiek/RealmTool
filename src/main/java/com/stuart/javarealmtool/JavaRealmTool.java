package com.stuart.javarealmtool;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class JavaRealmTool extends JavaPlugin implements Listener {

    private File dataFile;
    private FileConfiguration dataConfig;
    private Scoreboard scoreboard;
    private Team punishTeam;
    private WebServer webServer;
    private String apiKey;
    private final Map<UUID, PunishmentContext> pendingActions = new HashMap<>();
    private final Map<String, Integer> pendingNoteEdit = new HashMap<>();
    
    // pending world creation/chat actions are handled via pendingActions and a new enum value

    private final Map<UUID, UUID> tpaRequests = new HashMap<>();
    private final Map<UUID, String> pendingWarpDelete = new HashMap<>();
    private final Map<UUID, String> pendingClaimAction = new HashMap<>();
    private final Map<UUID, String> pendingTrustAction = new HashMap<>();
    private final Map<UUID, Integer> menuPages = new HashMap<>();
    private final Map<UUID, String> currentChunk = new ConcurrentHashMap<>();
    private final Map<String, Material> chunksCornerBlocks = new HashMap<>();
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> permissionAttachments = new HashMap<>();
    private int gridSlotIndex = 0;
    private int gridRowIndex = 0;

    // --- GUI STRINGS ---
    private final String GUI_MAIN = ChatColor.AQUA + "Drowsy Management Tool";
    private final String GUI_PLAYER_LIST = ChatColor.AQUA + "Player Directory";
    private final String GUI_TICKET_LIST = ChatColor.GOLD + "Ticket Viewer";
    private final String GUI_NOTES_VIEW = ChatColor.GOLD + "Player Notes: ";
    private final String GUI_PLAYER_ACTION = ChatColor.AQUA + "Manage: ";
    private final String INSPECTOR_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "Inspector Wand";
    private final String TOOL_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "Drowsy Tool";
    private final String CLAIM_WAND_NAME = ChatColor.BLUE + "" + ChatColor.BOLD + "Claim Wand";
    
    private final String GUI_MENU_SELECTOR = ChatColor.AQUA + "Menu Selection";
    private final String GUI_PLAYER_MENU = ChatColor.GREEN + "Player Menu";
    private final String GUI_PLAYER_LIST_TPA = ChatColor.GREEN + "Players (TPA)";
    private final String GUI_REPORT_PLAYER = ChatColor.RED + "Report Player";
    private final String GUI_WARP_MANAGEMENT = ChatColor.BLUE + "Manage Warp: ";
    private final String GUI_CLAIMS = ChatColor.BLUE + "Chunk Claims";
    private final String GUI_CLAIM_CONFIRM = ChatColor.YELLOW + "Confirm Claim";
    private final String GUI_UNCLAIM_CONFIRM = ChatColor.YELLOW + "Confirm Unclaim";
    private final String GUI_TRUST_PLAYER = ChatColor.BLUE + "Trust Player";
    private final String GUI_UNTRUST_PLAYER = ChatColor.BLUE + "Remove Trusted";
    private final String GUI_WORLD_UTILITIES = ChatColor.AQUA + "World Utilities";
    private final String GUI_WORLD_LIST = ChatColor.GREEN + "Worlds";
    private final String GUI_WORLD_OPTIONS = ChatColor.YELLOW + "World: ";
    private final String GUI_CREATE_TYPE = ChatColor.GOLD + "Create World - Select Type";
    private final String GUI_DELETE_CONFIRM = ChatColor.RED + "Delete World: ";
    private final String GUI_KIT_LIST = ChatColor.GOLD + "Kits";
    private final String GUI_KIT_PREVIEW = ChatColor.GOLD + "Kit: ";
    private final String GUI_KIT_CONFIRM = ChatColor.GREEN + "Purchase Kit: ";
    private final String GUI_CRATE_LIST = ChatColor.LIGHT_PURPLE + "Crates";
    private final String GUI_CRATE_PREVIEW = ChatColor.LIGHT_PURPLE + "Crate: ";
    private final String GUI_BOUNTY_LIST = ChatColor.RED + "Bounties";
    private final String GUI_SHOP_LIST = ChatColor.GREEN + "Player Shops";
    private final String GUI_QUEST_LIST = ChatColor.GOLD + "Quests";
    private final String GUI_AUCTION_HOUSE = ChatColor.GOLD + "Auction House";
    private final String GUI_DUEL_CONFIRM = ChatColor.RED + "Duel Request: ";
    private final String GUI_PWARP_LIST = ChatColor.AQUA + "Player Warps";
    private final String GUI_ACHIEVEMENTS = ChatColor.GOLD + "Achievements";
    private final String GUI_POLL_LIST = ChatColor.GOLD + "Active Polls";
    private final String GUI_POLL_VOTE = ChatColor.GOLD + "Poll: ";
    private final Map<UUID, Long> kitCooldowns = new HashMap<>();
    private final Map<UUID, String> pendingShopAction = new HashMap<>();
    private final Map<UUID, Integer> pendingBountyTarget = new HashMap<>();
    private final List<String> chatFilterWords = new ArrayList<>();
    private final Map<UUID, Integer> spamCounter = new HashMap<>();
    private final Map<UUID, Long> lastChatTime = new HashMap<>();
    private final Map<UUID, UUID> duelRequests = new HashMap<>();
    final Map<UUID, Integer> duelWagers = new HashMap<>();
    final Map<UUID, UUID> activeDuels = new HashMap<>();
    private final Map<UUID, Location> duelReturnLocations = new HashMap<>();
    private final Map<UUID, Long> ticketCooldowns = new HashMap<>();
    private final String GUI_TICKET_DETAIL = ChatColor.GOLD + "Ticket #";
    private int christmasSnowTaskId = -1;
    private int halloweenTaskId = -1;
    private int newYearTaskId = -1;
    private int valentineTaskId = -1;
    private int springTaskId = -1;
    private int summerTaskId = -1;

    private final Map<UUID, Long> reportCooldowns = new HashMap<>();
    private enum ActionType { KICK, BAN, WARN, ANNOUNCE, ADD_NOTE, SET_WARP, WORLD_CREATE_NAME, TICKET_RESPOND, TICKET_RESOLVE, REPORT }
    private static class PunishmentContext {
        String targetName;
        ActionType type;
        PunishmentContext(String n, ActionType t) { this.targetName = n; this.type = t; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupConfig();
        createDataFile();
        setupPunishTeam();

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("dmt") != null) getCommand("dmt").setExecutor(this);
        if (getCommand("ticket") != null) getCommand("ticket").setExecutor(this);
        if (getCommand("tpa") != null) getCommand("tpa").setExecutor(this);
        if (getCommand("kit") != null) getCommand("kit").setExecutor(this);
        if (getCommand("bounty") != null) getCommand("bounty").setExecutor(this);
        if (getCommand("shop") != null) getCommand("shop").setExecutor(this);
        if (getCommand("quest") != null) getCommand("quest").setExecutor(this);
        if (getCommand("apply") != null) getCommand("apply").setExecutor(this);
        if (getCommand("vote") != null) getCommand("vote").setExecutor(this);
        if (getCommand("crate") != null) getCommand("crate").setExecutor(this);
        if (getCommand("nick") != null) getCommand("nick").setExecutor(this);
        if (getCommand("rules") != null) getCommand("rules").setExecutor(this);
        if (getCommand("duel") != null) getCommand("duel").setExecutor(this);
        if (getCommand("pwarp") != null) getCommand("pwarp").setExecutor(this);
        if (getCommand("achievements") != null) getCommand("achievements").setExecutor(this);
        if (getCommand("stats") != null) getCommand("stats").setExecutor(this);
        if (getCommand("report") != null) getCommand("report").setExecutor(this);

        webServer = new WebServer(this);
        webServer.start();

        // Start playtime tracker (every 60 seconds = 1200 ticks)
        startPlaytimeTracker();
        
        // Start punishment expiry checker (every 1 second = 20 ticks)
        startPunishmentChecker();

        // Load auto-mod filter words
        loadChatFilter();

        // Start playtime rewards checker (every 5 min = 6000 ticks)
        startPlaytimeRewardsChecker();

        // Start scheduled announcements (every 60 seconds = 1200 ticks)
        startScheduledAnnouncements();

        // Start AFK auto-kick checker (every 30 seconds = 600 ticks)
        startAfkChecker();

        // Resume event effects if events were active before restart
        var activeEvents = dataConfig.getConfigurationSection("events.active");
        if (activeEvents != null) {
            for (String eventName : activeEvents.getKeys(false)) {
                startEventEffect(eventName);
            }
        }

        getLogger().info("Drowsy Management Tool Fully Loaded!");
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        saveDataFile();
        getLogger().info("DrowsyManagementTool has been disabled.");
    }

    public FileConfiguration getDataConfig() { return dataConfig; }
    public String fetchApiKey() { return apiKey; }

    public boolean isPunished(UUID u) {
        if (!dataConfig.contains("punishments." + u)) return false;
        long expiry = dataConfig.getLong("punishments." + u);
        if (System.currentTimeMillis() > expiry) {
            removePunishment(u);
            return false;
        }
        return true;
    }

    private void setupConfig() {
        FileConfiguration config = getConfig();
        if (!config.contains("api-key")) {
            config.set("api-key", UUID.randomUUID().toString());
            saveConfig();
        }
        this.apiKey = config.getString("api-key");
    }

    public String getApiKey() { return apiKey; }

    private void createDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException ignored) {}
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data to " + dataFile);
            e.printStackTrace();
        }
    }

    // --- Permission Groups ---

    public void applyPermissionGroup(Player player) {
        UUID uuid = player.getUniqueId();
        // Remove old attachment if exists
        removePermissionAttachment(player);
        // Find the player's group
        String groupName = getPlayerGroup(uuid);
        if (groupName == null) return;
        List<String> perms = dataConfig.getStringList("groups." + groupName + ".permissions");
        if (perms.isEmpty()) return;
        PermissionAttachment attachment = player.addAttachment(this);
        for (String perm : perms) {
            if (perm.startsWith("-")) {
                attachment.setPermission(perm.substring(1), false);
            } else {
                attachment.setPermission(perm, true);
            }
        }
        permissionAttachments.put(uuid, attachment);
        player.recalculatePermissions();
    }

    public void removePermissionAttachment(Player player) {
        PermissionAttachment old = permissionAttachments.remove(player.getUniqueId());
        if (old != null) {
            try { player.removeAttachment(old); } catch (Exception ignored) {}
            player.recalculatePermissions();
        }
    }

    public String getPlayerGroup(UUID uuid) {
        var groupsSection = dataConfig.getConfigurationSection("groups");
        if (groupsSection == null) return null;
        for (String groupName : groupsSection.getKeys(false)) {
            List<String> members = dataConfig.getStringList("groups." + groupName + ".members");
            if (members.contains(uuid.toString())) return groupName;
        }
        return null;
    }

    public void refreshAllPermissions() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyPermissionGroup(p);
        }
    }

    public boolean sendDiscordWebhook(String webhookUrl, String title, String description, int color) {
        return sendDiscordWebhook(webhookUrl, title, description, color, null);
    }

    public boolean sendDiscordWebhook(String webhookUrl, String title, String description, int color, String playerName) {
        try {
            java.net.URL url = new java.net.URL(webhookUrl);
            String host = url.getHost();
            if (host == null || (!host.equals("discord.com") && !host.equals("discordapp.com") && !host.endsWith(".discord.com"))) {
                getLogger().warning("Blocked non-Discord webhook URL: " + host);
                return false;
            }
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String thumbnailPart = "";
            if (playerName != null && !playerName.isEmpty()) {
                thumbnailPart = ",\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/" + playerName + "/64\"}";
            }

            String json = "{\"embeds\":[{\"title\":" + escapeJson(title)
                + ",\"description\":" + escapeJson(description)
                + ",\"color\":" + color + thumbnailPart + "}]}";

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code >= 200 && code < 300) {
                int sent = dataConfig.getInt("discord.webhooks_sent", 0);
                dataConfig.set("discord.webhooks_sent", sent + 1);
                saveDataFile();
                return true;
            } else {
                int failed = dataConfig.getInt("discord.webhooks_failed", 0);
                dataConfig.set("discord.webhooks_failed", failed + 1);
                saveDataFile();
                getLogger().warning("Discord webhook returned " + code);
                return false;
            }
        } catch (Exception e) {
            int failed = dataConfig.getInt("discord.webhooks_failed", 0);
            dataConfig.set("discord.webhooks_failed", failed + 1);
            saveDataFile();
            getLogger().warning("Discord webhook failed: " + e.getMessage());
            return false;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    public void fireDiscordEvent(String eventType, String title, String description, int color) {
        fireDiscordEvent(eventType, title, description, color, null);
    }

    public void fireDiscordEvent(String eventType, String title, String description, int color, String playerName) {
        if (!dataConfig.getBoolean("discord." + eventType, false)) return;

        // Check for event-specific webhook first, then fall back to primary
        String specificKey = null;
        switch (eventType) {
            case "bans": specificKey = "webhook_ban"; break;
            case "warns": specificKey = "webhook_warn"; break;
            case "reports": specificKey = "webhook_report"; break;
        }
        String webhook = null;
        if (specificKey != null) {
            webhook = dataConfig.getString("discord." + specificKey, "");
            if (webhook == null || webhook.isEmpty()) webhook = null;
        }
        if (webhook == null) {
            webhook = dataConfig.getString("discord.webhook", "");
        }
        if (webhook == null || webhook.isEmpty()) return;

        final String url = webhook;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> sendDiscordWebhook(url, title, description, color, playerName));
    }

    private void setupPunishTeam() {
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        punishTeam = scoreboard.getTeam("DrowsyPunish");
        if (punishTeam == null) punishTeam = scoreboard.registerNewTeam("DrowsyPunish");
        punishTeam.setPrefix(ChatColor.RED + "PUNISH ");
        punishTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
    }

    // --- COMMANDS ---
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (cmd.getName().equalsIgnoreCase("dmt")) {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelpMessage(p);
                return true;
            }

            if (!p.hasPermission("dmt.admin")) {
                p.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }

            String subcommand = args[0].toLowerCase();
            switch(subcommand) {
                case "setpunishloc":
                    saveLoc("punishment_location", p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Punishment location set to your current location.");
                    break;
                case "setjailloc":
                    saveLoc("jail_location", p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Jail location set to your current location.");
                    break;
                case "tpjail":
                    Location jailLoc = getLoc("jail_location");
                    if (jailLoc == null) {
                        p.sendMessage(ChatColor.RED + "Jail location not set yet. Use /dmt setjailloc");
                        return true;
                    }
                    p.teleport(jailLoc);
                    p.sendMessage(ChatColor.AQUA + "Teleported to jail.");
                    break;
                case "punish":
                    if (args.length < 3) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt punish <username> <duration>");
                        p.sendMessage(ChatColor.GRAY + "Duration format: 20s, 5m, 2hr, 2.5hr");
                        return true;
                    }
                    String targetName = args[1];
                    String durationStr = args[2];
                    long durationMs = parseDuration(durationStr);
                    if (durationMs == -1) {
                        p.sendMessage(ChatColor.RED + "Invalid duration format. Use: 20s, 5m, 2hr");
                        return true;
                    }
                    Player target = Bukkit.getPlayer(targetName);
                    if (target == null) {
                        p.sendMessage(ChatColor.RED + "Player not found.");
                        return true;
                    }
                    setPunished(target.getUniqueId(), durationMs);
                    p.sendMessage(ChatColor.GREEN + "Punished " + targetName + " for " + durationStr);
                    break;
                case "menu":
                    try {
                        openMainMenu(p);
                    } catch (Exception ex) {
                        p.sendMessage(ChatColor.RED + "An unexpected error occurred while opening the menu.");
                        getLogger().severe("Failed to open admin menu: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                    break;
                case "tp":
                    if (args.length >= 3 && args[1].equalsIgnoreCase("world")) {
                        String worldName = args[2];
                        World w = Bukkit.getWorld(worldName);
                        if (w == null) {
                            p.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
                        } else {
                            // respect world lock
                            if (dataConfig.getBoolean("worldlocks." + worldName, false)) {
                                p.sendMessage(ChatColor.RED + "That world is locked.");
                            } else {
                                p.teleport(w.getSpawnLocation());
                                p.sendMessage(ChatColor.GREEN + "Teleported to world '" + worldName + "'.");
                            }
                        }
                    } else {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt tp world <name>");
                    }
                    break;
                case "sethub":
                    saveLoc("hub_location", p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Hub location set to your current position.");
                    break;
                default:
                    p.sendMessage(ChatColor.RED + "Unknown subcommand. Use /dmt help");
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("kit")) {
            openKitListGUI(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("hub")) {
            Location hubLoc = getLoc("hub_location");
            if (hubLoc == null) {
                p.sendMessage(ChatColor.RED + "Hub location not set.");
                return true;
            }
            p.teleport(hubLoc);
            p.sendMessage(ChatColor.AQUA + "Teleported to hub.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("crate")) {
            openCrateListGUI(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("bounty")) {
            if (args.length == 0) {
                openBountyListGUI(p);
                return true;
            }
            if (args[0].equalsIgnoreCase("set") && args.length >= 3) {
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { p.sendMessage(ChatColor.RED + "Player not found."); return true; }
                if (target.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Cannot bounty yourself."); return true; }
                int amount;
                try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Invalid amount."); return true; }
                if (amount < 1) { p.sendMessage(ChatColor.RED + "Amount must be at least 1."); return true; }
                if (p.getLevel() < amount) { p.sendMessage(ChatColor.RED + "Not enough XP levels."); return true; }
                p.setLevel(p.getLevel() - amount);
                String bountyId = String.valueOf(System.currentTimeMillis());
                dataConfig.set("bounties." + bountyId + ".target", target.getUniqueId().toString());
                dataConfig.set("bounties." + bountyId + ".targetName", target.getName());
                dataConfig.set("bounties." + bountyId + ".setter", p.getUniqueId().toString());
                dataConfig.set("bounties." + bountyId + ".setterName", p.getName());
                dataConfig.set("bounties." + bountyId + ".amount", amount);
                dataConfig.set("bounties." + bountyId + ".time", System.currentTimeMillis());
                saveDataFile();
                Bukkit.broadcastMessage(ChatColor.RED + "☠ BOUNTY: " + ChatColor.YELLOW + p.getName() + ChatColor.RED + " placed a " + ChatColor.GOLD + amount + " XP level" + ChatColor.RED + " bounty on " + ChatColor.YELLOW + target.getName() + ChatColor.RED + "!");
                logAction(p.getName(), "set_bounty", target.getName() + " for " + amount + " XP");
                return true;
            }
            p.sendMessage(ChatColor.RED + "Usage: /bounty set <player> <amount> or /bounty");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("shop")) {
            openShopListGUI(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("quest")) {
            openQuestListGUI(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("apply")) {
            if (args.length == 0) {
                p.sendMessage(ChatColor.GOLD + "--- Staff Application ---");
                p.sendMessage(ChatColor.YELLOW + "Usage: /apply <your message explaining why you want to be staff>");
                return true;
            }
            String message = String.join(" ", args);
            String appId = String.valueOf(dataConfig.getInt("applications.next_id", 1));
            dataConfig.set("applications." + appId + ".player", p.getName());
            dataConfig.set("applications." + appId + ".uuid", p.getUniqueId().toString());
            dataConfig.set("applications." + appId + ".message", message);
            dataConfig.set("applications." + appId + ".status", "pending");
            dataConfig.set("applications." + appId + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
            dataConfig.set("applications.next_id", Integer.parseInt(appId) + 1);
            saveDataFile();
            p.sendMessage(ChatColor.GREEN + "✅ Your staff application #" + appId + " has been submitted! An admin will review it.");
            logAction(p.getName(), "submitted_application", "#" + appId);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("vote")) {
            if (args.length == 0) {
                openPollList(p);
                return true;
            }
            if (args.length >= 2) {
                String pollId = args[0];
                String choice = args[1];
                if (!dataConfig.contains("polls." + pollId)) { p.sendMessage(ChatColor.RED + "Poll not found."); return true; }
                if (!dataConfig.getBoolean("polls." + pollId + ".active", false)) { p.sendMessage(ChatColor.RED + "This poll has ended."); return true; }
                List<String> voters = dataConfig.getStringList("polls." + pollId + ".voters");
                if (voters.contains(p.getUniqueId().toString())) { p.sendMessage(ChatColor.RED + "You already voted on this poll."); return true; }
                List<String> options = dataConfig.getStringList("polls." + pollId + ".options");
                int choiceIdx;
                try { choiceIdx = Integer.parseInt(choice); } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Use the option number."); return true; }
                if (choiceIdx < 1 || choiceIdx > options.size()) { p.sendMessage(ChatColor.RED + "Invalid option."); return true; }
                voters.add(p.getUniqueId().toString());
                dataConfig.set("polls." + pollId + ".voters", voters);
                int current = dataConfig.getInt("polls." + pollId + ".votes." + choiceIdx, 0);
                dataConfig.set("polls." + pollId + ".votes." + choiceIdx, current + 1);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "✅ Vote recorded for: " + ChatColor.YELLOW + options.get(choiceIdx - 1));
                return true;
            }
            p.sendMessage(ChatColor.RED + "Usage: /vote <pollId> <option#> or /vote");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("ticket")) {
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
                Long lastTicket = ticketCooldowns.get(p.getUniqueId());
                if (lastTicket != null && (now - lastTicket) < 60000) {
                    long remaining = (60000 - (now - lastTicket)) / 1000;
                    p.sendMessage(ChatColor.RED + "Please wait " + remaining + "s before creating another ticket.");
                    return true;
                }
                // Check if second arg is a category
                Set<String> validCategories = Set.of("bug", "griefing", "chat", "item_loss", "pvp", "other");
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
                int id = dataConfig.getInt("tickets.next_id", 1);
                String path = "tickets." + id;
                dataConfig.set(path + ".player", p.getName());
                dataConfig.set(path + ".uuid", p.getUniqueId().toString());
                dataConfig.set(path + ".message", String.join(" ", Arrays.copyOfRange(args, messageStart, args.length)));
                dataConfig.set(path + ".status", "open");
                dataConfig.set(path + ".priority", "medium");
                dataConfig.set(path + ".category", category);
                dataConfig.set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                // Save location
                dataConfig.set(path + ".world", p.getWorld().getName());
                dataConfig.set(path + ".x", p.getLocation().getBlockX());
                dataConfig.set(path + ".y", p.getLocation().getBlockY());
                dataConfig.set(path + ".z", p.getLocation().getBlockZ());
                dataConfig.set("tickets.next_id", id + 1);
                saveDataFile();
                ticketCooldowns.put(p.getUniqueId(), now);
                p.sendMessage(ChatColor.GREEN + "Ticket #" + id + " created (" + category + "). Staff will review it soon.");
                // Notify online staff
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("realmtool.admin")) {
                        staff.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + p.getName() + " created ticket #" + id + ": " + ChatColor.GRAY + dataConfig.getString(path + ".message"));
                    }
                }
                return true;
            }
            if (sub.equals("list")) {
                boolean found = false;
                if (dataConfig.contains("tickets")) {
                    for (String key : dataConfig.getConfigurationSection("tickets").getKeys(false)) {
                        if (key.equals("next_id")) continue;
                        String ticketPlayer = dataConfig.getString("tickets." + key + ".player", "");
                        if (ticketPlayer.equalsIgnoreCase(p.getName())) {
                            String status = dataConfig.getString("tickets." + key + ".status", "open");
                            String msg = dataConfig.getString("tickets." + key + ".message", "");
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
                if (!dataConfig.contains(base)) { p.sendMessage(ChatColor.RED + "Ticket not found."); return true; }
                String ticketPlayer = dataConfig.getString(base + ".player", "");
                if (!ticketPlayer.equalsIgnoreCase(p.getName()) && !p.hasPermission("realmtool.admin")) {
                    p.sendMessage(ChatColor.RED + "You can only view your own tickets.");
                    return true;
                }
                p.sendMessage(ChatColor.GOLD + "===== Ticket #" + ticketId + " =====");
                p.sendMessage(ChatColor.YELLOW + "Player: " + ChatColor.WHITE + ticketPlayer);
                p.sendMessage(ChatColor.YELLOW + "Status: " + ChatColor.WHITE + dataConfig.getString(base + ".status", "open"));
                p.sendMessage(ChatColor.YELLOW + "Priority: " + ChatColor.WHITE + dataConfig.getString(base + ".priority", "medium"));
                p.sendMessage(ChatColor.YELLOW + "Category: " + ChatColor.WHITE + dataConfig.getString(base + ".category", "other"));
                p.sendMessage(ChatColor.YELLOW + "Message: " + ChatColor.WHITE + dataConfig.getString(base + ".message", ""));
                p.sendMessage(ChatColor.YELLOW + "Created: " + ChatColor.WHITE + dataConfig.getString(base + ".timestamp", ""));
                String resolution = dataConfig.getString(base + ".resolution");
                if (resolution != null) p.sendMessage(ChatColor.YELLOW + "Resolution: " + ChatColor.WHITE + resolution);
                List<String> responses = dataConfig.getStringList(base + ".responses");
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
                if (!dataConfig.contains(base)) { p.sendMessage(ChatColor.RED + "Ticket not found."); return true; }
                String ticketPlayer = dataConfig.getString(base + ".player", "");
                if (!ticketPlayer.equalsIgnoreCase(p.getName()) && !p.hasPermission("realmtool.admin")) {
                    p.sendMessage(ChatColor.RED + "You can only close your own tickets.");
                    return true;
                }
                String currentStatus = dataConfig.getString(base + ".status", "open");
                if (currentStatus.equals("closed")) {
                    p.sendMessage(ChatColor.RED + "This ticket is already closed.");
                    return true;
                }
                dataConfig.set(base + ".status", "closed");
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " closed.");
                return true;
            }
            p.sendMessage(ChatColor.RED + "Unknown subcommand. Use /ticket for help.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("tpa")) {
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Usage: /tpa accept <player> or /tpa deny <player>");
                return true;
            }

            String subcommand = args[0].toLowerCase();
            if (args.length < 2) {
                p.sendMessage(ChatColor.RED + "Usage: /tpa accept <player> or /tpa deny <player>");
                return true;
            }

            Player requester = Bukkit.getPlayer(args[1]);
            if (requester == null) {
                p.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }

            switch(subcommand) {
                case "accept":
                    if (tpaRequests.get(requester.getUniqueId()) != null && tpaRequests.get(requester.getUniqueId()).equals(p.getUniqueId())) {
                        requester.teleport(p.getLocation());
                        requester.sendMessage(ChatColor.GREEN + p.getName() + " accepted your TPA request!");
                        p.sendMessage(ChatColor.GREEN + "You accepted TPA from " + requester.getName());
                        tpaRequests.remove(requester.getUniqueId());
                    } else {
                        p.sendMessage(ChatColor.RED + "No TPA request from " + requester.getName());
                    }
                    break;
                case "deny":
                    if (tpaRequests.get(requester.getUniqueId()) != null && tpaRequests.get(requester.getUniqueId()).equals(p.getUniqueId())) {
                        requester.sendMessage(ChatColor.RED + p.getName() + " denied your TPA request.");
                        p.sendMessage(ChatColor.YELLOW + "You denied TPA from " + requester.getName());
                        tpaRequests.remove(requester.getUniqueId());
                    } else {
                        p.sendMessage(ChatColor.RED + "No TPA request from " + requester.getName());
                    }
                    break;
                default:
                    p.sendMessage(ChatColor.RED + "Usage: /tpa accept <player> or /tpa deny <player>");
            }
            return true;
        }

        // ========== NICKNAME ==========
        if (cmd.getName().equalsIgnoreCase("nick")) {
            if (args.length == 0) {
                // Reset nickname
                String current = dataConfig.getString("nicknames." + p.getUniqueId(), "");
                if (!current.isEmpty()) {
                    dataConfig.set("nicknames." + p.getUniqueId(), null);
                    saveDataFile();
                    p.setDisplayName(p.getName());
                    p.sendMessage(ChatColor.GREEN + "Nickname removed.");
                } else {
                    p.sendMessage(ChatColor.YELLOW + "Usage: /nick <name> or /nick to reset");
                }
            } else {
                String nick = String.join(" ", args);
                String colored = ChatColor.translateAlternateColorCodes('&', nick);
                dataConfig.set("nicknames." + p.getUniqueId(), nick);
                saveDataFile();
                p.setDisplayName(colored);
                p.sendMessage(ChatColor.GREEN + "Nickname set to: " + colored);
            }
            return true;
        }

        // ========== RULES ==========
        if (cmd.getName().equalsIgnoreCase("rules")) {
            List<String> rules = dataConfig.getStringList("server_rules");
            if (rules.isEmpty()) {
                p.sendMessage(ChatColor.YELLOW + "No server rules have been set yet.");
            } else {
                p.sendMessage(ChatColor.GOLD + "===== " + ChatColor.AQUA + "Server Rules" + ChatColor.GOLD + " =====");
                for (int i = 0; i < rules.size(); i++) {
                    p.sendMessage(ChatColor.YELLOW + "" + (i + 1) + ". " + ChatColor.WHITE + ChatColor.translateAlternateColorCodes('&', rules.get(i)));
                }
            }
            return true;
        }

        // ========== DUEL ==========
        if (cmd.getName().equalsIgnoreCase("duel")) {
            if (args.length == 0) {
                p.sendMessage(ChatColor.RED + "Usage: /duel <player> [wager]  or  /duel accept  or  /duel deny");
                return true;
            }
            if (args[0].equalsIgnoreCase("accept")) {
                UUID requesterId = null;
                for (Map.Entry<UUID, UUID> entry : duelRequests.entrySet()) {
                    if (entry.getValue().equals(p.getUniqueId())) { requesterId = entry.getKey(); break; }
                }
                if (requesterId == null) { p.sendMessage(ChatColor.RED + "No pending duel request."); return true; }
                Player requester = Bukkit.getPlayer(requesterId);
                if (requester == null) { p.sendMessage(ChatColor.RED + "Requester is offline."); duelRequests.remove(requesterId); return true; }
                int wager = duelWagers.getOrDefault(requesterId, 0);
                if (wager > 0) {
                    if (p.getLevel() < wager) { p.sendMessage(ChatColor.RED + "Not enough XP for wager (" + wager + " levels)."); return true; }
                    if (requester.getLevel() < wager) { p.sendMessage(ChatColor.RED + "Requester no longer has enough XP."); duelRequests.remove(requesterId); return true; }
                }
                // Start duel
                duelRequests.remove(requesterId);
                activeDuels.put(requesterId, p.getUniqueId());
                activeDuels.put(p.getUniqueId(), requesterId);
                duelReturnLocations.put(requesterId, requester.getLocation());
                duelReturnLocations.put(p.getUniqueId(), p.getLocation());
                requester.sendMessage(ChatColor.GREEN + "⚔ Duel started with " + p.getName() + "!" + (wager > 0 ? " Wager: " + wager + " XP" : ""));
                p.sendMessage(ChatColor.GREEN + "⚔ Duel started with " + requester.getName() + "!" + (wager > 0 ? " Wager: " + wager + " XP" : ""));
                logAction(p.getName(), "duel_started", requester.getName() + (wager > 0 ? " wager:" + wager : ""));
                return true;
            }
            if (args[0].equalsIgnoreCase("deny")) {
                UUID requesterId = null;
                for (Map.Entry<UUID, UUID> entry : duelRequests.entrySet()) {
                    if (entry.getValue().equals(p.getUniqueId())) { requesterId = entry.getKey(); break; }
                }
                if (requesterId != null) {
                    Player requester = Bukkit.getPlayer(requesterId);
                    if (requester != null) requester.sendMessage(ChatColor.RED + p.getName() + " denied your duel request.");
                    duelRequests.remove(requesterId);
                    duelWagers.remove(requesterId);
                    p.sendMessage(ChatColor.YELLOW + "Duel request denied.");
                } else {
                    p.sendMessage(ChatColor.RED + "No pending duel request.");
                }
                return true;
            }
            // Send duel request
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) { p.sendMessage(ChatColor.RED + "Player not found."); return true; }
            if (target.equals(p)) { p.sendMessage(ChatColor.RED + "Can't duel yourself."); return true; }
            int wager = 0;
            if (args.length > 1) {
                try { wager = Integer.parseInt(args[1]); } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Invalid wager amount."); return true; }
                if (wager < 0) wager = 0;
                if (wager > 0 && p.getLevel() < wager) { p.sendMessage(ChatColor.RED + "Not enough XP for wager."); return true; }
            }
            duelRequests.put(p.getUniqueId(), target.getUniqueId());
            if (wager > 0) duelWagers.put(p.getUniqueId(), wager);
            p.sendMessage(ChatColor.GREEN + "⚔ Duel request sent to " + target.getName() + (wager > 0 ? " with " + wager + " XP wager" : ""));
            target.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.YELLOW + p.getName() + ChatColor.GOLD + " challenged you to a duel!" + (wager > 0 ? " Wager: " + ChatColor.AQUA + wager + " XP" : ""));
            target.sendMessage(ChatColor.GREEN + "/duel accept" + ChatColor.WHITE + " or " + ChatColor.RED + "/duel deny");
            return true;
        }

        // ========== PLAYER WARPS ==========
        if (cmd.getName().equalsIgnoreCase("pwarp")) {
            if (args.length == 0) {
                openPwarpListGUI(p);
                return true;
            }
            if (args[0].equalsIgnoreCase("set") && args.length >= 2) {
                String warpName = args[1];
                int cost = dataConfig.getInt("pwarp_cost", 5);
                if (p.getLevel() < cost) { p.sendMessage(ChatColor.RED + "Need " + cost + " XP levels to create a warp."); return true; }
                // Limit per player
                int max = dataConfig.getInt("pwarp_max", 3);
                int count = 0;
                if (dataConfig.contains("pwarps")) {
                    for (String id : dataConfig.getConfigurationSection("pwarps").getKeys(false)) {
                        if (dataConfig.getString("pwarps." + id + ".owner", "").equals(p.getUniqueId().toString())) count++;
                    }
                }
                if (count >= max) { p.sendMessage(ChatColor.RED + "Max " + max + " player warps."); return true; }
                p.setLevel(p.getLevel() - cost);
                String id = warpName.toLowerCase().replace(" ", "_");
                dataConfig.set("pwarps." + id + ".name", warpName);
                dataConfig.set("pwarps." + id + ".owner", p.getUniqueId().toString());
                dataConfig.set("pwarps." + id + ".ownerName", p.getName());
                dataConfig.set("pwarps." + id + ".x", p.getLocation().getX());
                dataConfig.set("pwarps." + id + ".y", p.getLocation().getY());
                dataConfig.set("pwarps." + id + ".z", p.getLocation().getZ());
                dataConfig.set("pwarps." + id + ".world", p.getWorld().getName());
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Player warp '" + warpName + "' created!");
                logAction(p.getName(), "pwarp_created", warpName);
                return true;
            }
            if (args[0].equalsIgnoreCase("delete") && args.length >= 2) {
                String id = args[1].toLowerCase().replace(" ", "_");
                String owner = dataConfig.getString("pwarps." + id + ".owner", "");
                if (!owner.equals(p.getUniqueId().toString()) && !p.hasPermission("dmt.admin")) {
                    p.sendMessage(ChatColor.RED + "That's not your warp."); return true;
                }
                dataConfig.set("pwarps." + id, null);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Player warp deleted.");
                return true;
            }
            // Teleport to warp
            if (args.length >= 1) {
                String id = args[0].toLowerCase().replace(" ", "_");
                if (dataConfig.contains("pwarps." + id)) {
                    World w = Bukkit.getWorld(dataConfig.getString("pwarps." + id + ".world", "world"));
                    if (w != null) {
                        Location loc = new Location(w,
                            dataConfig.getDouble("pwarps." + id + ".x"),
                            dataConfig.getDouble("pwarps." + id + ".y"),
                            dataConfig.getDouble("pwarps." + id + ".z"));
                        p.teleport(loc);
                        dataConfig.set("pwarps." + id + ".visits", dataConfig.getInt("pwarps." + id + ".visits", 0) + 1);
                        saveDataFile();
                        p.sendMessage(ChatColor.GREEN + "Warped to " + dataConfig.getString("pwarps." + id + ".name", id));
                    }
                } else {
                    p.sendMessage(ChatColor.RED + "Warp not found. Use /pwarp to browse.");
                }
                return true;
            }
            return true;
        }

        // ========== ACHIEVEMENTS ==========
        if (cmd.getName().equalsIgnoreCase("achievements")) {
            openAchievementsGUI(p);
            return true;
        }

        // ========== STATS (K/D) ==========
        if (cmd.getName().equalsIgnoreCase("stats")) {
            Player target = args.length > 0 ? Bukkit.getPlayer(args[0]) : p;
            if (target == null) { p.sendMessage(ChatColor.RED + "Player not found."); return true; }
            UUID tid = target.getUniqueId();
            int kills = dataConfig.getInt("pvpstats." + tid + ".kills", 0);
            int deaths = dataConfig.getInt("pvpstats." + tid + ".deaths", 0);
            double kd = deaths > 0 ? Math.round((double) kills / deaths * 100.0) / 100.0 : kills;
            int streak = dataConfig.getInt("pvpstats." + tid + ".streak", 0);
            int bestStreak = dataConfig.getInt("pvpstats." + tid + ".best_streak", 0);
            p.sendMessage(ChatColor.GOLD + "===== " + ChatColor.AQUA + target.getName() + "'s Stats" + ChatColor.GOLD + " =====");
            p.sendMessage(ChatColor.GREEN + "Kills: " + ChatColor.WHITE + kills);
            p.sendMessage(ChatColor.RED + "Deaths: " + ChatColor.WHITE + deaths);
            p.sendMessage(ChatColor.YELLOW + "K/D Ratio: " + ChatColor.WHITE + kd);
            p.sendMessage(ChatColor.AQUA + "Current Streak: " + ChatColor.WHITE + streak);
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Best Streak: " + ChatColor.WHITE + bestStreak);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("report")) {
            if (args.length < 2) {
                p.sendMessage(ChatColor.GOLD + "===== Report System =====");
                p.sendMessage(ChatColor.YELLOW + "/report <player> <reason>" + ChatColor.GRAY + " - Report a player");
                return true;
            }
            // Cooldown check (60 seconds)
            long now = System.currentTimeMillis();
            Long lastReport = reportCooldowns.get(p.getUniqueId());
            if (lastReport != null && (now - lastReport) < 60000) {
                int remaining = (int) ((60000 - (now - lastReport)) / 1000);
                p.sendMessage(ChatColor.RED + "Please wait " + remaining + "s before submitting another report.");
                return true;
            }
            String reportedName = args[0];
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            // Don't allow self-reports
            if (reportedName.equalsIgnoreCase(p.getName())) {
                p.sendMessage(ChatColor.RED + "You cannot report yourself.");
                return true;
            }
            // Store report
            if (!dataConfig.contains("reports")) dataConfig.set("reports", new ArrayList<>());
            List<String> reports = dataConfig.getStringList("reports");
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            reports.add(ts + " | " + p.getName() + " reported " + reportedName + " for: " + reason);
            dataConfig.set("reports", reports);
            saveDataFile();
            reportCooldowns.put(p.getUniqueId(), now);
            p.sendMessage(ChatColor.GREEN + "Report submitted! Staff have been notified.");
            // Notify online admins
            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (admin.hasPermission("dmt.admin")) {
                    admin.sendMessage(ChatColor.RED + "[Report] " + ChatColor.YELLOW + p.getName() + ChatColor.RED + " reported " + ChatColor.YELLOW + reportedName + ChatColor.RED + ": " + ChatColor.WHITE + reason);
                }
            }
            // Discord webhook
            fireDiscordEvent("reports", "New Report", "**" + p.getName() + "** reported **" + reportedName + "**\nReason: " + reason, 0xe67e22, reportedName);
            return true;
        }

        return true;
    }

    private void sendHelpMessage(Player p) {
        p.sendMessage(ChatColor.GOLD + "===== " + ChatColor.AQUA + "DrowsyCraft" + ChatColor.GOLD + " =====");
        p.sendMessage("");
        p.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "Player Commands:");
        p.sendMessage(ChatColor.GREEN + "/kit" + ChatColor.WHITE + " - Browse and claim kits");
        p.sendMessage(ChatColor.GREEN + "/crate" + ChatColor.WHITE + " - Open the crate menu");
        p.sendMessage(ChatColor.GREEN + "/bounty" + ChatColor.WHITE + " - View the bounty board");
        p.sendMessage(ChatColor.GREEN + "/bounty set <player> <amount>" + ChatColor.WHITE + " - Place a bounty");
        p.sendMessage(ChatColor.GREEN + "/shop" + ChatColor.WHITE + " - Browse player shops");
        p.sendMessage(ChatColor.GREEN + "/shop sell <item> <amount> <price>" + ChatColor.WHITE + " - Sell an item");
        p.sendMessage(ChatColor.GREEN + "/quest" + ChatColor.WHITE + " - View quests & claim rewards");
        p.sendMessage(ChatColor.GREEN + "/vote" + ChatColor.WHITE + " - View active polls");
        p.sendMessage(ChatColor.GREEN + "/vote <pollId> <option#>" + ChatColor.WHITE + " - Vote on a poll");
        p.sendMessage(ChatColor.GREEN + "/apply <message>" + ChatColor.WHITE + " - Submit a staff application");
        p.sendMessage(ChatColor.GREEN + "/ticket new <message>" + ChatColor.WHITE + " - Submit a ticket");
        p.sendMessage(ChatColor.GREEN + "/tpa accept|deny <player>" + ChatColor.WHITE + " - Teleport requests");
        p.sendMessage(ChatColor.GREEN + "/nick <name>" + ChatColor.WHITE + " - Set your nickname (& color codes)");
        p.sendMessage(ChatColor.GREEN + "/rules" + ChatColor.WHITE + " - View server rules");
        p.sendMessage(ChatColor.GREEN + "/duel <player> [wager]" + ChatColor.WHITE + " - Challenge to a duel");
        p.sendMessage(ChatColor.GREEN + "/pwarp" + ChatColor.WHITE + " - Browse player warps");
        p.sendMessage(ChatColor.GREEN + "/pwarp set <name>" + ChatColor.WHITE + " - Create a player warp");
        p.sendMessage(ChatColor.GREEN + "/achievements" + ChatColor.WHITE + " - View your achievements");
        p.sendMessage(ChatColor.GREEN + "/stats [player]" + ChatColor.WHITE + " - View PvP stats");
        p.sendMessage(ChatColor.GREEN + "/report <player> <reason>" + ChatColor.WHITE + " - Report a player");

        if (p.hasPermission("dmt.admin")) {
            p.sendMessage("");
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Admin Commands:");
            p.sendMessage(ChatColor.AQUA + "/dmt menu" + ChatColor.WHITE + " - Opens the management GUI");
            p.sendMessage(ChatColor.AQUA + "/dmt punish <player> <duration>" + ChatColor.WHITE + " - Punish a player");
            p.sendMessage(ChatColor.AQUA + "/dmt setpunishloc" + ChatColor.WHITE + " - Set punishment location");
            p.sendMessage(ChatColor.AQUA + "/dmt setjailloc" + ChatColor.WHITE + " - Set jail location");
            p.sendMessage(ChatColor.AQUA + "/dmt tpjail" + ChatColor.WHITE + " - Teleport to jail");
            p.sendMessage(ChatColor.AQUA + "/dmt tp world <name>" + ChatColor.WHITE + " - Teleport to another world");
            p.sendMessage(ChatColor.AQUA + "/dmt sethub" + ChatColor.WHITE + " - Set current location as hub");
            p.sendMessage(ChatColor.AQUA + "/hub" + ChatColor.WHITE + " - Teleport to hub world");
        }
    }

    // --- CHAT LISTENER (Broadcasts, Notes, Reasons) ---
    @EventHandler
    public void onChatReason(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!pendingActions.containsKey(p.getUniqueId())) return;

        e.setCancelled(true);
        PunishmentContext ctx = pendingActions.remove(p.getUniqueId());
        String reason = e.getMessage();
        Player target = ctx.targetName != null ? Bukkit.getPlayer(ctx.targetName) : null;

        Bukkit.getScheduler().runTask(this, () -> {
            switch (ctx.type) {
                case ANNOUNCE:
                    Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[Announcement] " + ChatColor.RESET + ChatColor.YELLOW + reason);
                    p.sendMessage(ChatColor.AQUA + "Broadcast sent!");
                    break;
                case ADD_NOTE:
                    if (ctx.targetName != null) {
                        UUID tid = Bukkit.getOfflinePlayer(ctx.targetName).getUniqueId();
                        List<String> notes = dataConfig.getStringList("notes." + tid);
                        notes.add("[" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + " - " + p.getName() + "] " + reason);
                        dataConfig.set("notes." + tid, notes);
                        saveDataFile();
                        p.sendMessage(ChatColor.GREEN + "Note added.");
                        openPlayerNotesMenu(p, ctx.targetName);
                    }
                    break;
                case SET_WARP:
                    // sanitize warp name similar to world creation rules
                    String warpName = reason.replaceAll("[^A-Za-z0-9_\\-]", "");
                    if (warpName.isEmpty()) {
                        p.sendMessage(ChatColor.RED + "Invalid warp name.");
                        break;
                    }
                    dataConfig.set("warps." + warpName + ".x", p.getLocation().getX());
                    dataConfig.set("warps." + warpName + ".y", p.getLocation().getY());
                    dataConfig.set("warps." + warpName + ".z", p.getLocation().getZ());
                    dataConfig.set("warps." + warpName + ".world", p.getWorld().getName());
                    dataConfig.set("warps." + warpName + ".yaw", p.getLocation().getYaw());
                    dataConfig.set("warps." + warpName + ".pitch", p.getLocation().getPitch());
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Warp '" + warpName + "' set!");
                    break;
                case WORLD_CREATE_NAME:
                    String type = ctx.targetName;
                    String worldName = reason.replaceAll("[^A-Za-z0-9_\\-]", "");
                    if (worldName.isEmpty()) {
                        p.sendMessage(ChatColor.RED + "Invalid world name.");
                        break;
                    }
                    Bukkit.getScheduler().runTask(this, () -> {
                        WorldCreator wc = new WorldCreator(worldName);
                        if ("flat".equals(type)) {
                            wc.type(WorldType.FLAT);
                        } else if ("void".equals(type)) {
                            // use a custom chunk generator to avoid JSON parsing errors
                            wc.generator(new org.bukkit.generator.ChunkGenerator() {
                                @Override
                                public ChunkData generateChunkData(World world, Random random, int x, int z, org.bukkit.generator.ChunkGenerator.BiomeGrid biome) {
                                    // return an empty chunk (void) using the helper provided by ChunkGenerator
                                    return super.createChunkData(world);
                                }
                            });
                            wc.generateStructures(false);
                        }
                        World created = null;
                        try {
                            created = Bukkit.createWorld(wc);
                        } catch (Exception ex) {
                            p.sendMessage(ChatColor.RED + "Failed to create world: " + ex.getMessage());
                            ex.printStackTrace();
                        }
                        if (created != null) {
                            p.sendMessage(ChatColor.GREEN + "World '" + worldName + "' created (" + type + ").");
                        } else if (created == null) {
                            // if an exception occurred it has already been reported
                            if (p.isOnline()) {
                                p.sendMessage(ChatColor.RED + "World creation returned null.");
                            }
                        }
                    });
                    break;
                case WARN: if (target != null) target.sendMessage(ChatColor.RED + "WARNING: " + ChatColor.YELLOW + reason); break;
                case KICK: if (target != null) target.kickPlayer(ChatColor.RED + "Kicked: " + reason); break;
                case BAN: 
                    Bukkit.getBanList(BanList.Type.NAME).addBan(ctx.targetName, reason, null, null);
                    if (target != null) target.kickPlayer(ChatColor.RED + "Banned: " + reason);
                    break;
                case TICKET_RESPOND:
                    addTicketResponse(Integer.parseInt(ctx.targetName), p.getName(), reason);
                    p.sendMessage(ChatColor.GREEN + "Response added to ticket #" + ctx.targetName + ".");
                    break;
                case TICKET_RESOLVE:
                    resolveTicket(Integer.parseInt(ctx.targetName), reason);
                    p.sendMessage(ChatColor.GREEN + "Ticket #" + ctx.targetName + " resolved: " + reason);
                    break;
                case REPORT:
                    // Cooldown check (60 seconds)
                    long now = System.currentTimeMillis();
                    Long lastRep = reportCooldowns.get(p.getUniqueId());
                    if (lastRep != null && (now - lastRep) < 60000) {
                        int rem = (int) ((60000 - (now - lastRep)) / 1000);
                        p.sendMessage(ChatColor.RED + "Please wait " + rem + "s before submitting another report.");
                        break;
                    }
                    if (!dataConfig.contains("reports")) dataConfig.set("reports", new ArrayList<>());
                    List<String> reps = dataConfig.getStringList("reports");
                    String rts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    reps.add(rts + " | " + p.getName() + " reported " + ctx.targetName + " for: " + reason);
                    dataConfig.set("reports", reps);
                    saveDataFile();
                    reportCooldowns.put(p.getUniqueId(), now);
                    p.sendMessage(ChatColor.GREEN + "Report submitted! Staff have been notified.");
                    for (Player adm : Bukkit.getOnlinePlayers()) {
                        if (adm.hasPermission("dmt.admin")) {
                            adm.sendMessage(ChatColor.RED + "[Report] " + ChatColor.YELLOW + p.getName() + ChatColor.RED + " reported " + ChatColor.YELLOW + ctx.targetName + ChatColor.RED + ": " + ChatColor.WHITE + reason);
                        }
                    }
                    fireDiscordEvent("reports", "New Report", "**" + p.getName() + "** reported **" + ctx.targetName + "**\nReason: " + reason, 0xe67e22, ctx.targetName);
                    break;
            }
            if (ctx.targetName != null && ctx.type != ActionType.ADD_NOTE) {
                p.sendMessage(ChatColor.AQUA + "Action applied to " + ctx.targetName);
            }
        });
    }

    // --- GUIS ---
    private void openMenuSelector(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_MENU_SELECTOR);
        
        // Fill all empty slots with gray glass
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        
        // Player Menu button
        ItemStack playerMenu = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta pmMeta = playerMenu.getItemMeta();
        pmMeta.setDisplayName(ChatColor.GREEN + "Player Menu");
        pmMeta.setLore(Arrays.asList(ChatColor.GRAY + "Homes, Warps, TPA"));
        playerMenu.setItemMeta(pmMeta);
        gui.setItem(11, playerMenu);
        
        // Admin Menu button
        ItemStack adminMenu = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta amMeta = adminMenu.getItemMeta();
        amMeta.setDisplayName(ChatColor.RED + "Admin Menu");
        amMeta.setLore(Arrays.asList(ChatColor.GRAY + "Management tools"));
        adminMenu.setItemMeta(amMeta);
        gui.setItem(15, adminMenu);
        
        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        p.openInventory(gui);
    }

    private void openPlayerMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_MENU);
        
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        
        resetGridSlots();
        
        // Set Home
        ItemStack setHome = new ItemStack(Material.RED_BED);
        ItemMeta shMeta = setHome.getItemMeta();
        shMeta.setDisplayName(ChatColor.YELLOW + "Set Home");
        setHome.setItemMeta(shMeta);
        gui.setItem(getNextGridSlot(), setHome);
        
        // TP Home
        ItemStack tpHome = new ItemStack(Material.ORANGE_BED);
        ItemMeta thMeta = tpHome.getItemMeta();
        thMeta.setDisplayName(ChatColor.YELLOW + "TP Home");
        tpHome.setItemMeta(thMeta);
        gui.setItem(getNextGridSlot(), tpHome);
        
        // Set Warp
        ItemStack setWarp = new ItemStack(Material.OAK_SIGN);
        ItemMeta swMeta = setWarp.getItemMeta();
        swMeta.setDisplayName(ChatColor.BLUE + "Set Warp");
        setWarp.setItemMeta(swMeta);
        gui.setItem(getNextGridSlot(), setWarp);
        
        // View Warps
        ItemStack viewWarps = new ItemStack(Material.FILLED_MAP);
        ItemMeta vwMeta = viewWarps.getItemMeta();
        vwMeta.setDisplayName(ChatColor.BLUE + "View Warps");
        viewWarps.setItemMeta(vwMeta);
        gui.setItem(getNextGridSlot(), viewWarps);
        
        // Chunk Claims
        ItemStack claims = new ItemStack(Material.CRYING_OBSIDIAN);
        ItemMeta cMeta = claims.getItemMeta();
        cMeta.setDisplayName(ChatColor.BLUE + "Chunk Claims");
        cMeta.setLore(Arrays.asList(ChatColor.GRAY + "Manage your claims"));
        claims.setItemMeta(cMeta);
        gui.setItem(getNextGridSlot(), claims);
        
        // Player List (TPA)
        ItemStack playerList = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta plMeta = playerList.getItemMeta();
        plMeta.setDisplayName(ChatColor.AQUA + "Players (TPA)");
        plMeta.setLore(Arrays.asList(ChatColor.GRAY + "Send TPA requests"));
        playerList.setItemMeta(plMeta);
        gui.setItem(getNextGridSlot(), playerList);
        
        // Kits
        ItemStack kits = new ItemStack(Material.CHEST);
        ItemMeta kitMeta = kits.getItemMeta();
        kitMeta.setDisplayName(ChatColor.GOLD + "Kits");
        kitMeta.setLore(Arrays.asList(ChatColor.GRAY + "Browse and purchase kits"));
        kits.setItemMeta(kitMeta);
        gui.setItem(getNextGridSlot(), kits);
        
        // Report Player
        ItemStack report = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta rpMeta = report.getItemMeta();
        rpMeta.setDisplayName(ChatColor.RED + "Report Player");
        rpMeta.setLore(Arrays.asList(ChatColor.GRAY + "Report a player to staff"));
        report.setItemMeta(rpMeta);
        gui.setItem(getNextGridSlot(), report);
        
        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        
        p.openInventory(gui);
    }

    private void openKitListGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_KIT_LIST);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();

        if (dataConfig.contains("kits")) {
            for (String kitName : dataConfig.getConfigurationSection("kits").getKeys(false)) {
                String path = "kits." + kitName;
                String icon = dataConfig.getString(path + ".icon", "CHEST");
                int cost = dataConfig.getInt(path + ".cost", 0);
                int cooldown = dataConfig.getInt(path + ".cooldown", 0);
                String desc = dataConfig.getString(path + ".description", "");

                Material mat = Material.CHEST;
                try { mat = Material.valueOf(icon.toUpperCase()); } catch (Exception ignored) {}

                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.GOLD + kitName);
                List<String> lore = new ArrayList<>();
                if (!desc.isEmpty()) lore.add(ChatColor.GRAY + desc);
                lore.add("");
                if (cost > 0) lore.add(ChatColor.GREEN + "Cost: " + cost + " XP Levels");
                else lore.add(ChatColor.GREEN + "Free");
                if (cooldown > 0) lore.add(ChatColor.YELLOW + "Cooldown: " + cooldown + "s");
                lore.add("");
                lore.add(ChatColor.AQUA + "Click to preview & purchase");
                meta.setLore(lore);
                item.setItemMeta(meta);
                gui.setItem(getNextGridSlot(), item);
            }
        }

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);

        p.openInventory(gui);
    }

    private void openKitPreviewGUI(Player p, String kitName) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_KIT_PREVIEW + kitName);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);

        String path = "kits." + kitName;
        List<String> itemStrings = dataConfig.getStringList(path + ".items");
        int slot = 10; // Start in the inner area
        for (String itemStr : itemStrings) {
            if (slot >= 44) break;
            // Skip border slots
            if (slot % 9 == 0 || slot % 9 == 8) { slot++; continue; }
            try {
                // Format: MATERIAL:amount or MATERIAL
                String[] parts = itemStr.split(":");
                Material mat = Material.valueOf(parts[0].toUpperCase());
                int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                ItemStack display = new ItemStack(mat, amount);
                gui.setItem(slot, display);
            } catch (Exception ignored) {}
            slot++;
        }

        // Purchase button
        int cost = dataConfig.getInt(path + ".cost", 0);
        ItemStack purchase = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta pMeta = purchase.getItemMeta();
        pMeta.setDisplayName(ChatColor.GREEN + "Purchase Kit");
        List<String> lore = new ArrayList<>();
        if (cost > 0) lore.add(ChatColor.YELLOW + "Cost: " + cost + " XP Levels");
        else lore.add(ChatColor.GREEN + "Free!");
        pMeta.setLore(lore);
        purchase.setItemMeta(pMeta);
        gui.setItem(49, purchase);

        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back to Kits");
        back.setItemMeta(bMeta);
        gui.setItem(45, back);

        p.openInventory(gui);
    }

    private boolean claimKit(Player p, String kitName) {
        String path = "kits." + kitName;
        if (!dataConfig.contains(path)) {
            p.sendMessage(ChatColor.RED + "Kit not found.");
            return false;
        }
        int cost = dataConfig.getInt(path + ".cost", 0);
        int cooldown = dataConfig.getInt(path + ".cooldown", 0);
        String permission = dataConfig.getString(path + ".permission", "");

        // Permission check
        if (!permission.isEmpty() && !p.hasPermission(permission)) {
            p.sendMessage(ChatColor.RED + "You don't have permission for this kit.");
            return false;
        }

        // Cooldown check
        String cooldownKey = "kit_cooldowns." + p.getUniqueId() + "." + kitName;
        long lastUsed = dataConfig.getLong(cooldownKey, 0);
        long now = System.currentTimeMillis();
        if (cooldown > 0 && lastUsed > 0) {
            long remaining = (lastUsed + (cooldown * 1000L)) - now;
            if (remaining > 0) {
                long secs = remaining / 1000;
                String timeStr = secs >= 3600 ? (secs / 3600) + "h " + ((secs % 3600) / 60) + "m" :
                                 secs >= 60 ? (secs / 60) + "m " + (secs % 60) + "s" : secs + "s";
                p.sendMessage(ChatColor.RED + "Kit on cooldown! " + ChatColor.YELLOW + timeStr + " remaining.");
                return false;
            }
        }

        // Cost check (XP levels)
        if (cost > 0) {
            if (p.getLevel() < cost) {
                p.sendMessage(ChatColor.RED + "Not enough XP levels! You need " + ChatColor.YELLOW + cost + ChatColor.RED + " levels. (You have " + p.getLevel() + ")");
                return false;
            }
            p.setLevel(p.getLevel() - cost);
        }

        // Give items
        List<String> itemStrings = dataConfig.getStringList(path + ".items");
        for (String itemStr : itemStrings) {
            try {
                String[] parts = itemStr.split(":");
                Material mat = Material.valueOf(parts[0].toUpperCase());
                int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                ItemStack item = new ItemStack(mat, amount);
                HashMap<Integer, ItemStack> overflow = p.getInventory().addItem(item);
                for (ItemStack drop : overflow.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), drop);
                }
            } catch (Exception ignored) {}
        }

        // Set cooldown
        if (cooldown > 0) {
            dataConfig.set(cooldownKey, now);
            saveDataFile();
        }

        p.sendMessage(ChatColor.GREEN + "You received the " + ChatColor.GOLD + kitName + ChatColor.GREEN + " kit!");
        logAction(p.getName(), "claimed_kit", kitName);
        return true;
    }

    private void openPlayerListTPA(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_LIST_TPA);
        
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        
        resetGridSlots();
        
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;
            
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta hMeta = (SkullMeta) head.getItemMeta();
            hMeta.setOwningPlayer(target);
            hMeta.setDisplayName(ChatColor.YELLOW + target.getName());
            hMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Health: " + ChatColor.RED + String.format("%.1f", target.getHealth()),
                ChatColor.GRAY + "Location: " + ChatColor.AQUA + target.getLocation().getBlockX() + ", " + 
                                                                   target.getLocation().getBlockY() + ", " +
                                                                   target.getLocation().getBlockZ()
            ));
            head.setItemMeta(hMeta);
            
            int slot = getNextGridSlot();
            if (slot != -1) {
                gui.setItem(slot, head);
                // Map the slot to the player for TPA request
            }
        }
        
        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        
        p.openInventory(gui);
    }

    private void openReportPlayerList(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_REPORT_PLAYER);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(p.getUniqueId())) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta hMeta = (SkullMeta) head.getItemMeta();
            hMeta.setOwningPlayer(target);
            hMeta.setDisplayName(ChatColor.YELLOW + target.getName());
            head.setItemMeta(hMeta);
            int slot = getNextGridSlot();
            if (slot != -1) gui.setItem(slot, head);
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    private void openMainMenu(Player p) {

        Inventory gui = Bukkit.createInventory(null, 54, GUI_MAIN);
        
        // Calculate stats
        int total = Bukkit.getOnlinePlayers().size();
        int punished = 0;
        if (dataConfig.contains("punishments")) {
            for (String key : dataConfig.getConfigurationSection("punishments").getKeys(false)) {
                if (isPunished(UUID.fromString(key))) punished++;
            }
        }
        int nonPunished = total - punished;
        
        // Row 1 (10-16): Stats and Weather
        gui.setItem(10, createGuiItem(Material.EMERALD_BLOCK, ChatColor.AQUA + "Players: " + ChatColor.WHITE + nonPunished));
        gui.setItem(11, createGuiItem(Material.SUNFLOWER, ChatColor.GOLD + "Weather: Clear"));
        gui.setItem(12, createGuiItem(Material.WATER_BUCKET, ChatColor.AQUA + "Weather: Rain"));
        gui.setItem(13, createGuiItem(Material.BEACON, ChatColor.DARK_GRAY + "Weather: Thunder"));
        // Nether lock item (use an item type that supports metadata reliably)
        boolean netherLocked = dataConfig.getBoolean("locks.nether", false);
        ItemStack netherItem = new ItemStack(Material.CRYING_OBSIDIAN);
        ItemMeta nm = netherItem.getItemMeta();
        if (nm != null) {
            nm.setDisplayName(ChatColor.DARK_PURPLE + "Nether Access");
            nm.setLore(Arrays.asList(ChatColor.GRAY + (netherLocked ? "Locked" : "Unlocked")));
            netherItem.setItemMeta(nm);
        }
        gui.setItem(14, netherItem);
        // End lock item
        boolean endLocked = dataConfig.getBoolean("locks.end", false);
        ItemStack endItem = new ItemStack(Material.END_STONE);
        ItemMeta em = endItem.getItemMeta();
        if (em != null) {
            em.setDisplayName(ChatColor.DARK_PURPLE + "The End Access");
            em.setLore(Arrays.asList(ChatColor.GRAY + (endLocked ? "Locked" : "Unlocked")));
            endItem.setItemMeta(em);
        }
        gui.setItem(15, endItem);
        gui.setItem(16, createGuiItem(Material.REDSTONE_BLOCK, ChatColor.RED + "Punished: " + ChatColor.WHITE + punished));
        
        // Row 2 (19-25): Time and Gamemodes
        gui.setItem(19, createGuiItem(Material.CLOCK, ChatColor.AQUA + "Set Day"));
        gui.setItem(20, createGuiItem(Material.COAL, ChatColor.DARK_AQUA + "Set Night"));
        gui.setItem(21, createGuiItem(Material.PLAYER_HEAD, ChatColor.AQUA + "Player Directory"));
        gui.setItem(22, createGuiItem(Material.GRASS_BLOCK, ChatColor.AQUA + "Creative Mode"));
        gui.setItem(23, createGuiItem(Material.BEEF, ChatColor.AQUA + "Survival Mode"));
        gui.setItem(24, createGuiItem(Material.GOLDEN_APPLE, ChatColor.GOLD + "Heal & Feed All"));
        
        // Row 3 (28-34): Utilities
        gui.setItem(28, createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME));
        gui.setItem(29, createGuiItem(Material.WRITABLE_BOOK, ChatColor.GOLD + "Broadcast Message"));
        gui.setItem(30, createGuiItem(Material.PAPER, ChatColor.GOLD + "View Tickets"));
        gui.setItem(31, createGuiItem(Material.COMPASS, ChatColor.BLUE + "World Utilities"));
        
        // Row 4 (43): Close button
        gui.setItem(43, createGuiItem(Material.REDSTONE, ChatColor.RED + "Close Menu"));

        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openPlayerListMenu(Player p) {
        openPlayerListMenu(p, 0);
    }

    private void openPlayerListMenu(Player p, int page) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_LIST);
        resetGridSlots();
        
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        int playersPerPage = 28; // This fits the grid layout
        int start = page * playersPerPage;
        int end = Math.min(start + playersPerPage, players.size());
        
        for (int i = start; i < end; i++) {
            Player target = players.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.AQUA + target.getName());
            head.setItemMeta(meta);
            int slot = getNextGridSlot();
            if (slot != -1) gui.setItem(slot, head);
        }
        
        // Add navigation buttons
        ItemStack back = createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu");
        gui.setItem(53, back);
        
        if (end < players.size()) {
            ItemStack next = createGuiItem(Material.ARROW, ChatColor.GREEN + "Next Page");
            gui.setItem(52, next);
        }
        
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        menuPages.put(p.getUniqueId(), page);
        p.openInventory(gui);
    }

    private void openPlayerActionMenu(Player p, String targetName) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_ACTION + targetName);
        gui.setItem(10, createGuiItem(Material.COMPASS, ChatColor.AQUA + "Teleport to Player"));
        gui.setItem(11, createGuiItem(Material.ENDER_PEARL, ChatColor.AQUA + "Bring Player"));
        gui.setItem(12, createGuiItem(Material.CHEST, ChatColor.AQUA + "See Inventory"));
        gui.setItem(14, createGuiItem(Material.ENDER_CHEST, ChatColor.AQUA + "See Enderchest"));
        gui.setItem(16, createGuiItem(Material.BOOK, ChatColor.GOLD + "View Notes"));

        gui.setItem(19, createGuiItem(Material.GOLDEN_APPLE, ChatColor.GREEN + "Heal & Feed"));
        gui.setItem(20, createGuiItem(Material.IRON_DOOR, ChatColor.YELLOW + "Kick Player"));
        gui.setItem(21, createGuiItem(Material.BARRIER, ChatColor.RED + "Ban Player"));
        gui.setItem(28, createGuiItem(Material.GOAT_HORN, ChatColor.YELLOW + "Warn Player"));

        gui.setItem(37, createGuiItem(Material.IRON_BARS, ChatColor.RED + "Punish 1hr"));
        gui.setItem(38, createGuiItem(Material.IRON_BARS, ChatColor.RED + "Punish 3hr"));
        gui.setItem(39, createGuiItem(Material.IRON_BARS, ChatColor.DARK_RED + "Punish 24hr"));
        gui.setItem(40, createGuiItem(Material.MILK_BUCKET, ChatColor.GREEN + "Unpunish"));

        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Directory"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openTicketListMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_TICKET_LIST);
        resetGridSlots();
        if (dataConfig.contains("tickets")) {
            for (String key : dataConfig.getConfigurationSection("tickets").getKeys(false)) {
                if (key.equals("next_id")) continue;
                String status = dataConfig.getString("tickets." + key + ".status", "open");
                if (!"open".equals(status) && !"in_progress".equals(status)) continue;
                String priority = dataConfig.getString("tickets." + key + ".priority", "medium");
                String category = dataConfig.getString("tickets." + key + ".category", "other");
                String player = dataConfig.getString("tickets." + key + ".player", "???");
                String message = dataConfig.getString("tickets." + key + ".message", "");
                String assignee = dataConfig.getString("tickets." + key + ".assignee", "");
                String time = dataConfig.getString("tickets." + key + ".timestamp", "");
                int responseCount = dataConfig.getStringList("tickets." + key + ".responses").size();

                // Color-coded material by priority
                Material mat;
                switch (priority) {
                    case "critical": mat = Material.REDSTONE_BLOCK; break;
                    case "high": mat = Material.ORANGE_WOOL; break;
                    case "low": mat = Material.LIME_WOOL; break;
                    default: mat = Material.YELLOW_WOOL; break; // medium
                }
                ChatColor statusColor = status.equals("in_progress") ? ChatColor.AQUA : ChatColor.GREEN;

                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.YELLOW + "By: " + ChatColor.WHITE + player);
                lore.add(ChatColor.YELLOW + "Status: " + statusColor + status);
                lore.add(ChatColor.YELLOW + "Priority: " + ChatColor.WHITE + priority);
                lore.add(ChatColor.YELLOW + "Category: " + ChatColor.WHITE + category);
                if (!assignee.isEmpty()) lore.add(ChatColor.YELLOW + "Assignee: " + ChatColor.WHITE + assignee);
                lore.add(ChatColor.YELLOW + "Created: " + ChatColor.GRAY + time);
                lore.add(ChatColor.YELLOW + "Responses: " + ChatColor.WHITE + "" + responseCount);
                lore.add(ChatColor.GRAY + (message.length() > 50 ? message.substring(0, 50) + "..." : message));
                lore.add("");
                lore.add(ChatColor.GREEN + "Click to view details");

                ItemStack ticketItem = createGuiItem(mat, ChatColor.GOLD + "Ticket #" + key, lore);
                int slot = getNextGridSlot();
                if (slot != -1) gui.setItem(slot, ticketItem);
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openTicketDetailMenu(Player p, String ticketId) {
        String base = "tickets." + ticketId;
        if (!dataConfig.contains(base)) { p.sendMessage(ChatColor.RED + "Ticket not found."); return; }

        Inventory gui = Bukkit.createInventory(null, 54, GUI_TICKET_DETAIL + ticketId);
        String player = dataConfig.getString(base + ".player", "???");
        String status = dataConfig.getString(base + ".status", "open");
        String priority = dataConfig.getString(base + ".priority", "medium");
        String category = dataConfig.getString(base + ".category", "other");
        String message = dataConfig.getString(base + ".message", "");
        String assignee = dataConfig.getString(base + ".assignee", "");
        String time = dataConfig.getString(base + ".timestamp", "");
        String resolution = dataConfig.getString(base + ".resolution", "");
        List<String> responses = dataConfig.getStringList(base + ".responses");

        // Ticket info item (slot 4)
        List<String> infoLore = new ArrayList<>();
        infoLore.add(ChatColor.YELLOW + "Player: " + ChatColor.WHITE + player);
        infoLore.add(ChatColor.YELLOW + "Created: " + ChatColor.GRAY + time);
        infoLore.add(ChatColor.YELLOW + "Category: " + ChatColor.WHITE + category);
        infoLore.add("");
        // Word-wrap message
        for (int i = 0; i < message.length(); i += 40) {
            infoLore.add(ChatColor.WHITE + message.substring(i, Math.min(i + 40, message.length())));
        }
        if (dataConfig.contains(base + ".world")) {
            infoLore.add("");
            infoLore.add(ChatColor.AQUA + "Location: " + dataConfig.getString(base + ".world") + " " +
                dataConfig.getInt(base + ".x") + ", " + dataConfig.getInt(base + ".y") + ", " + dataConfig.getInt(base + ".z"));
        }
        gui.setItem(4, createGuiItem(Material.BOOK, ChatColor.GOLD + "Ticket #" + ticketId + " Info", infoLore));

        // Status display (slot 19)
        ChatColor statusColor;
        switch (status) {
            case "in_progress": statusColor = ChatColor.AQUA; break;
            case "resolved": statusColor = ChatColor.GREEN; break;
            case "closed": statusColor = ChatColor.GRAY; break;
            default: statusColor = ChatColor.YELLOW; break;
        }
        gui.setItem(19, createGuiItem(Material.NAME_TAG, statusColor + "Status: " + status));

        // Priority display (slot 20)
        Material prioMat;
        switch (priority) {
            case "critical": prioMat = Material.REDSTONE_BLOCK; break;
            case "high": prioMat = Material.ORANGE_WOOL; break;
            case "low": prioMat = Material.LIME_WOOL; break;
            default: prioMat = Material.YELLOW_WOOL; break;
        }
        gui.setItem(20, createGuiItem(prioMat, ChatColor.YELLOW + "Priority: " + priority));

        // Assignee (slot 21)
        gui.setItem(21, createGuiItem(Material.PLAYER_HEAD, ChatColor.YELLOW + "Assignee: " + (assignee.isEmpty() ? "Unassigned" : assignee)));

        // Resolution (slot 22)
        if (!resolution.isEmpty()) {
            gui.setItem(22, createGuiItem(Material.EMERALD, ChatColor.GREEN + "Resolution: " + resolution));
        }

        // Action buttons row
        gui.setItem(28, createGuiItem(Material.WRITABLE_BOOK, ChatColor.GREEN + "Add Response", Collections.singletonList(ChatColor.GRAY + "Click to type a response")));
        gui.setItem(29, createGuiItem(Material.ARROW, ChatColor.AQUA + "Set In Progress"));
        gui.setItem(30, createGuiItem(Material.GOLD_INGOT, ChatColor.YELLOW + "Set Priority"));
        gui.setItem(31, createGuiItem(Material.ARMOR_STAND, ChatColor.BLUE + "Assign to Me"));
        gui.setItem(32, createGuiItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "Resolve"));
        gui.setItem(33, createGuiItem(Material.BARRIER, ChatColor.RED + "Close Ticket"));

        // Teleport to location (slot 34)
        if (dataConfig.contains(base + ".world")) {
            gui.setItem(34, createGuiItem(Material.ENDER_PEARL, ChatColor.LIGHT_PURPLE + "Teleport to Location"));
        }

        // Show responses (slots 37-44)
        int respSlot = 37;
        for (int i = Math.max(0, responses.size() - 8); i < responses.size() && respSlot <= 44; i++) {
            String raw = responses.get(i);
            String[] parts = raw.split(" \\| ", 3);
            List<String> respLore = new ArrayList<>();
            if (parts.length == 3) {
                respLore.add(ChatColor.GRAY + parts[0]);
                respLore.add(ChatColor.WHITE + parts[2]);
                gui.setItem(respSlot, createGuiItem(Material.MAP, ChatColor.AQUA + parts[1], respLore));
            } else {
                respLore.add(ChatColor.WHITE + raw);
                gui.setItem(respSlot, createGuiItem(Material.MAP, ChatColor.AQUA + "Response", respLore));
            }
            respSlot++;
        }

        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Ticket List"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    // world utility menus
    private void openWorldUtilitiesMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_WORLD_UTILITIES);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        int slot;
        ItemStack item;
        // View Worlds
        slot = getNextGridSlot();
        if (slot >= 0 && slot < gui.getSize()) {
            item = createGuiItem(Material.GRASS_BLOCK, ChatColor.GREEN + "View Worlds");
            if (item != null && item.getItemMeta() != null) gui.setItem(slot, item);
        }
        // Create World
        slot = getNextGridSlot();
        if (slot >= 0 && slot < gui.getSize()) {
            item = createGuiItem(Material.NETHER_STAR, ChatColor.AQUA + "Create World");
            if (item != null && item.getItemMeta() != null) gui.setItem(slot, item);
        }
        // (no top‑level delete button – deletion happens per‑world in options menu)
        // Back button
        item = createGuiItem(Material.BARRIER, ChatColor.RED + "Back to Main Menu");
        if (item != null && item.getItemMeta() != null) gui.setItem(26, item);
        p.openInventory(gui);
    }


    private void openWorldListMenu(Player p) {
        openWorldListMenu(p, 0);
    }

    private void openWorldListMenu(Player p, int page) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_WORLD_LIST);
        resetGridSlots();
        List<World> worlds = Bukkit.getWorlds();
        int perPage = 28;
        int start = page * perPage;
        int end = Math.min(start + perPage, worlds.size());
        for (int i = start; i < end; i++) {
            World w = worlds.get(i);
            gui.setItem(getNextGridSlot(), createGuiItem(Material.GRASS_BLOCK, ChatColor.AQUA + w.getName()));
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Utilities"));
        if (end < worlds.size()) gui.setItem(52, createGuiItem(Material.ARROW, ChatColor.GREEN + "Next Page"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        menuPages.put(p.getUniqueId(), page);
        p.openInventory(gui);
    }

    private void openWorldOptionsMenu(Player p, String worldName) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_WORLD_OPTIONS + worldName);
        gui.setItem(10, createGuiItem(Material.ENDER_PEARL, ChatColor.AQUA + "Teleport"));
        boolean locked = dataConfig.getBoolean("worldlocks." + worldName, false);
        gui.setItem(11, createGuiItem(Material.IRON_DOOR, ChatColor.YELLOW + (locked ? "Unlock" : "Lock")));
        gui.setItem(12, createGuiItem(Material.REDSTONE_BLOCK, ChatColor.RED + "Delete"));
        gui.setItem(26, createGuiItem(Material.BARRIER, ChatColor.RED + "Back"));
        p.openInventory(gui);
    }

    private void openCreateWorldTypeMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 9, GUI_CREATE_TYPE);
        gui.setItem(2, createGuiItem(Material.GRASS_BLOCK, ChatColor.GREEN + "Normal"));
        gui.setItem(4, createGuiItem(Material.PUMPKIN, ChatColor.AQUA + "Flat"));
        gui.setItem(6, createGuiItem(Material.GLASS, ChatColor.DARK_GRAY + "Void"));
        gui.setItem(8, createGuiItem(Material.BARRIER, ChatColor.RED + "Cancel"));
        p.openInventory(gui);
    }

    private void openWorldDeleteConfirmation(Player p, String worldName) {
        Inventory gui = Bukkit.createInventory(null, 9, GUI_DELETE_CONFIRM + worldName);
        gui.setItem(2, createGuiItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "Confirm Delete"));
        gui.setItem(4, createGuiItem(Material.BARRIER, ChatColor.RED + "Cancel"));
        p.openInventory(gui);
    }

    private void openPlayerNotesMenu(Player p, String targetName) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_NOTES_VIEW + targetName);
        resetGridSlots();
        UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        if (dataConfig.contains("notes." + uuid)) {
            List<String> notesList = dataConfig.getStringList("notes." + uuid);
            for (int i = 0; i < notesList.size(); i++) {
                String note = notesList.get(i);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Click for options");
                ItemStack noteItem = createGuiItem(Material.PAPER, ChatColor.YELLOW + note, lore);
                int slot = getNextGridSlot();
                if (slot != -1) gui.setItem(slot, noteItem);
            }
        }
        gui.setItem(45, createGuiItem(Material.WRITABLE_BOOK, ChatColor.GREEN + "Add Note"));
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openNoteManagementMenu(Player p, String targetName, int noteIndex) {
        UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        List<String> notesList = dataConfig.getStringList("notes." + uuid);
        
        if (noteIndex >= notesList.size()) return;
        
        String note = notesList.get(noteIndex);
        String guiTitle = ChatColor.YELLOW + "Note: " + note.substring(0, Math.min(20, note.length()));
        Inventory gui = Bukkit.createInventory(null, 27, guiTitle);
        
        // Fill all slots with gray glass
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        
        // View button
        ItemStack view = new ItemStack(Material.BOOK);
        ItemMeta vMeta = view.getItemMeta();
        vMeta.setDisplayName(ChatColor.AQUA + "View Full Note");
        view.setItemMeta(vMeta);
        gui.setItem(10, view);
        
        // Edit button
        ItemStack edit = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta eMeta = edit.getItemMeta();
        eMeta.setDisplayName(ChatColor.YELLOW + "Edit Note");
        edit.setItemMeta(eMeta);
        gui.setItem(12, edit);
        
        // Delete button
        ItemStack delete = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta dMeta = delete.getItemMeta();
        dMeta.setDisplayName(ChatColor.RED + "Delete Note");
        delete.setItemMeta(dMeta);
        gui.setItem(14, delete);
        
        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        // Store note index in a temporary map for retrieval
        pendingNoteEdit.put(p.getUniqueId() + ":" + targetName, noteIndex);
        
        p.openInventory(gui);
    }

    private void openPunishedPlayersMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.RED + "Punished Players");
        resetGridSlots();
        if (dataConfig.contains("punishments")) {
            for (String key : dataConfig.getConfigurationSection("punishments").getKeys(false)) {
                UUID uuid = UUID.fromString(key);
                if (isPunished(uuid)) {
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                    ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta meta = (SkullMeta) head.getItemMeta();
                    meta.setOwningPlayer(offlinePlayer);
                    meta.setDisplayName(ChatColor.RED + offlinePlayer.getName());
                    head.setItemMeta(meta);
                    int slot = getNextGridSlot();
                    if (slot != -1) gui.setItem(slot, head);
                }
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openWarpListMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.BLUE + "Warps");
        
        // Fill all slots with gray glass
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        
        int warpSlot = 1;
        if (dataConfig.contains("warps")) {
            for (String warpName : dataConfig.getConfigurationSection("warps").getKeys(false)) {
                if (warpSlot >= 26) break; // Don't go past slot 25
                
                ItemStack warp = new ItemStack(Material.FILLED_MAP);
                ItemMeta wMeta = warp.getItemMeta();
                wMeta.setDisplayName(ChatColor.BLUE + warpName);
                double x = dataConfig.getDouble("warps." + warpName + ".x");
                double y = dataConfig.getDouble("warps." + warpName + ".y");
                double z = dataConfig.getDouble("warps." + warpName + ".z");
                String worldName = dataConfig.getString("warps." + warpName + ".world");
                wMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "World: " + ChatColor.WHITE + worldName,
                    ChatColor.GRAY + "X: " + ChatColor.WHITE + String.format("%.1f", x),
                    ChatColor.GRAY + "Y: " + ChatColor.WHITE + String.format("%.1f", y),
                    ChatColor.GRAY + "Z: " + ChatColor.WHITE + String.format("%.1f", z)
                ));
                warp.setItemMeta(wMeta);
                gui.setItem(warpSlot, warp);
                
                // Move to next slot, skip slot 0, 8, 9, 17, 18, 26 (edges)
                warpSlot++;
                if (warpSlot == 8 || warpSlot == 17) warpSlot += 2;
            }
        }
        
        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        p.openInventory(gui);
    }

    private void openWarpManagementMenu(Player p, String warpName) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_WARP_MANAGEMENT + warpName);
        
        // Fill all slots with gray glass
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        
        // Teleport button
        ItemStack teleport = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tMeta = teleport.getItemMeta();
        tMeta.setDisplayName(ChatColor.GREEN + "Teleport");
        tMeta.setLore(Arrays.asList(ChatColor.GRAY + "TP to warp: " + warpName));
        teleport.setItemMeta(tMeta);
        gui.setItem(11, teleport);
        
        // Delete button
        ItemStack delete = new ItemStack(Material.REDSTONE);
        ItemMeta dMeta = delete.getItemMeta();
        dMeta.setDisplayName(ChatColor.RED + "Delete Warp");
        dMeta.setLore(Arrays.asList(ChatColor.GRAY + "Remove warp: " + warpName));
        delete.setItemMeta(dMeta);
        gui.setItem(15, delete);
        
        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        pendingWarpDelete.put(p.getUniqueId(), warpName);
        p.openInventory(gui);
    }

    private void openWarpDeleteConfirmation(Player p, String warpName) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.RED + "Delete: " + warpName);
        
        // Fill all slots with gray glass
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        
        // Confirm button (Emerald)
        ItemStack confirm = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta cMeta = confirm.getItemMeta();
        cMeta.setDisplayName(ChatColor.GREEN + "Confirm Delete");
        cMeta.setLore(Arrays.asList(ChatColor.GRAY + "This cannot be undone"));
        confirm.setItemMeta(cMeta);
        gui.setItem(11, confirm);
        
        // Cancel button (Redstone)
        ItemStack cancel = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta caMeta = cancel.getItemMeta();
        caMeta.setDisplayName(ChatColor.YELLOW + "Cancel");
        caMeta.setLore(Arrays.asList(ChatColor.GRAY + "Keep the warp"));
        cancel.setItemMeta(caMeta);
        gui.setItem(15, cancel);
        
        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        p.openInventory(gui);
    }

    private void openClaimsMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_CLAIMS);
        for (int i = 0; i < 27; i++) gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        
        UUID uuid = p.getUniqueId();
        int chunkLimit = getChunkLimit(uuid);
        int claimedChunks = getClaimedChunks(uuid).size();
        
        ItemStack chunks = new ItemStack(Material.ARMOR_STAND);
        ItemMeta chkMeta = chunks.getItemMeta();
        chkMeta.setDisplayName(ChatColor.AQUA + "Chunks Available");
        chkMeta.setLore(Arrays.asList(ChatColor.YELLOW + "Limit: " + chunkLimit, ChatColor.YELLOW + "Claimed: " + claimedChunks));
        chunks.setItemMeta(chkMeta);
        gui.setItem(10, chunks);
        
        ItemStack claim = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta clmMeta = claim.getItemMeta();
        clmMeta.setDisplayName(ChatColor.GREEN + "Claim Chunk");
        clmMeta.setLore(Arrays.asList(ChatColor.GRAY + "Claims current chunk"));
        claim.setItemMeta(clmMeta);
        gui.setItem(12, claim);
        
        ItemStack unclaim = new ItemStack(Material.DIRT);
        ItemMeta unclMeta = unclaim.getItemMeta();
        unclMeta.setDisplayName(ChatColor.RED + "Unclaim Chunk");
        unclMeta.setLore(Arrays.asList(ChatColor.GRAY + "Unclaims current chunk"));
        unclaim.setItemMeta(unclMeta);
        gui.setItem(14, unclaim);
        
        ItemStack trust = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta trMeta = trust.getItemMeta();
        trMeta.setDisplayName(ChatColor.GREEN + "Trust Player");
        trMeta.setLore(Arrays.asList(ChatColor.GRAY + "Add trusted player"));
        trust.setItemMeta(trMeta);
        gui.setItem(11, trust);
        
        ItemStack untrust = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta untrMeta = untrust.getItemMeta();
        untrMeta.setDisplayName(ChatColor.RED + "Remove Trusted");
        untrMeta.setLore(Arrays.asList(ChatColor.GRAY + "Remove trusted player"));
        untrust.setItemMeta(untrMeta);
        gui.setItem(13, untrust);
        
        ItemStack wand = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta wMeta = wand.getItemMeta();
        wMeta.setDisplayName(CLAIM_WAND_NAME);
        wMeta.setLore(Arrays.asList(ChatColor.GRAY + "Right-click to check chunks", ChatColor.GRAY + "Sneak + Right-click to list your claims"));
        wand.setItemMeta(wMeta);
        gui.setItem(15, wand);
        
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        p.openInventory(gui);
    }

    private void openClaimConfirmMenu(Player p, String action) {
        Inventory gui = Bukkit.createInventory(null, 27, action.equals("claim") ? GUI_CLAIM_CONFIRM : GUI_UNCLAIM_CONFIRM);
        for (int i = 0; i < 27; i++) gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        
        String chunkKey = getChunkKey(p.getLocation());
        String message = action.equals("claim") ? 
            "Do you want to claim this chunk?" : 
            "Do you want to unclaim this chunk?";
        
        ItemStack confirm = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta confMeta = confirm.getItemMeta();
        confMeta.setDisplayName(ChatColor.GREEN + "Confirm");
        confMeta.setLore(Arrays.asList(ChatColor.GRAY + message));
        confirm.setItemMeta(confMeta);
        gui.setItem(11, confirm);
        
        ItemStack cancel = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta canMeta = cancel.getItemMeta();
        canMeta.setDisplayName(ChatColor.RED + "Cancel");
        cancel.setItemMeta(canMeta);
        gui.setItem(15, cancel);
        
        pendingClaimAction.put(p.getUniqueId(), action + ":" + chunkKey);
        p.openInventory(gui);
    }

    private void openTrustPlayerMenu(Player p) {
        openTrustPlayerMenu(p, 0);
    }

    private void openTrustPlayerMenu(Player p, int page) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_TRUST_PLAYER);
        for (int i = 0; i < 27; i++) gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        
        List<Player> onlinePlayers = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(p.getUniqueId())) {
                onlinePlayers.add(online);
            }
        }
        
        int playersPerPage = 7;
        int start = page * playersPerPage;
        int end = Math.min(start + playersPerPage, onlinePlayers.size());
        
        int slot = 10;
        for (int i = start; i < end; i++) {
            Player online = onlinePlayers.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta hMeta = (SkullMeta) head.getItemMeta();
            hMeta.setOwningPlayer(online);
            hMeta.setDisplayName(ChatColor.YELLOW + online.getName());
            head.setItemMeta(hMeta);
            gui.setItem(slot, head);
            slot++;
        }
        
        // Add navigation buttons
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        if (end < onlinePlayers.size()) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nMeta = next.getItemMeta();
            nMeta.setDisplayName(ChatColor.GREEN + "Next Page");
            next.setItemMeta(nMeta);
            gui.setItem(25, next);
        }
        
        menuPages.put(p.getUniqueId(), page);
        p.openInventory(gui);
    }

    private void openUntrustPlayerMenu(Player p) {
        openUntrustPlayerMenu(p, 0);
    }

    private void openUntrustPlayerMenu(Player p, int page) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_UNTRUST_PLAYER);
        for (int i = 0; i < 27; i++) gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        
        List<String> trusted = dataConfig.getStringList("claims." + p.getUniqueId() + ".trusted");
        int playersPerPage = 7;
        int start = page * playersPerPage;
        int end = Math.min(start + playersPerPage, trusted.size());
        
        int slot = 10;
        for (int i = start; i < end; i++) {
            String trustedName = trusted.get(i);
            ItemStack item = new ItemStack(Material.NAME_TAG);
            ItemMeta iMeta = item.getItemMeta();
            iMeta.setDisplayName(ChatColor.YELLOW + trustedName);
            item.setItemMeta(iMeta);
            gui.setItem(slot, item);
            slot++;
        }
        
        // Add navigation buttons
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        if (end < trusted.size()) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nMeta = next.getItemMeta();
            nMeta.setDisplayName(ChatColor.GREEN + "Next Page");
            next.setItemMeta(nMeta);
            gui.setItem(25, next);
        }
        
        menuPages.put(p.getUniqueId(), page);
        p.openInventory(gui);
    }

    private Location getHomeLocation(UUID uuid) {
        if (!dataConfig.contains("homes." + uuid)) return null;
        String worldName = dataConfig.getString("homes." + uuid + ".world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        
        double x = dataConfig.getDouble("homes." + uuid + ".x");
        double y = dataConfig.getDouble("homes." + uuid + ".y");
        double z = dataConfig.getDouble("homes." + uuid + ".z");
        float yaw = (float) dataConfig.getDouble("homes." + uuid + ".yaw");
        float pitch = (float) dataConfig.getDouble("homes." + uuid + ".pitch");
        
        Location loc = new Location(world, x, y, z, yaw, pitch);
        return loc;
    }

    private Location getWarpLocation(String warpName) {
        if (!dataConfig.contains("warps." + warpName)) return null;
        String worldName = dataConfig.getString("warps." + warpName + ".world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        
        double x = dataConfig.getDouble("warps." + warpName + ".x");
        double y = dataConfig.getDouble("warps." + warpName + ".y");
        double z = dataConfig.getDouble("warps." + warpName + ".z");
        float yaw = (float) dataConfig.getDouble("warps." + warpName + ".yaw");
        float pitch = (float) dataConfig.getDouble("warps." + warpName + ".pitch");
        
        Location loc = new Location(world, x, y, z, yaw, pitch);
        return loc;
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        boolean relevant = title.equals(GUI_MAIN)
            || title.equals(GUI_PLAYER_LIST)
            || title.equals(GUI_TICKET_LIST)
            || title.startsWith(GUI_TICKET_DETAIL)
            || title.startsWith(GUI_PLAYER_ACTION)
            || title.startsWith(GUI_NOTES_VIEW)
            || title.equals(ChatColor.RED + "Punished Players")
            || title.startsWith(ChatColor.YELLOW + "Note:")
            || title.equals(GUI_MENU_SELECTOR)
            || title.equals(GUI_PLAYER_MENU)
            || title.equals(GUI_PLAYER_LIST_TPA)
            || title.equals(GUI_REPORT_PLAYER)
            || title.equals(ChatColor.BLUE + "Warps")
            || title.startsWith(GUI_WARP_MANAGEMENT)
            || title.startsWith(ChatColor.RED + "Delete:")
            || title.equals(GUI_CLAIMS)
            || title.equals(GUI_CLAIM_CONFIRM)
            || title.equals(GUI_UNCLAIM_CONFIRM)
            || title.equals(GUI_TRUST_PLAYER)
            || title.equals(GUI_UNTRUST_PLAYER)
            || title.equals(GUI_WORLD_UTILITIES)
            || title.equals(GUI_WORLD_LIST)
            || title.startsWith(GUI_WORLD_OPTIONS)
            || title.equals(GUI_CREATE_TYPE)
            || title.startsWith(GUI_DELETE_CONFIRM)
            || title.equals(GUI_KIT_LIST)
            || title.startsWith(GUI_KIT_PREVIEW)
            || title.startsWith(GUI_KIT_CONFIRM)
            || title.equals(GUI_CRATE_LIST)
            || title.equals(GUI_BOUNTY_LIST)
            || title.equals(GUI_SHOP_LIST)
            || title.equals(GUI_QUEST_LIST)
            || title.equals(GUI_AUCTION_HOUSE)
            || title.equals(GUI_PWARP_LIST)
            || title.equals(GUI_ACHIEVEMENTS)
            || title.equals(GUI_POLL_LIST)
            || title.startsWith(GUI_POLL_VOTE);
        if (!relevant) return;

        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null) return;
        Player p = (Player) e.getWhoClicked();
        Material type = clicked.getType();
        ItemMeta clickedMeta = clicked.getItemMeta();
        String itemName = "";
        if (clickedMeta != null && clickedMeta.hasDisplayName()) itemName = ChatColor.stripColor(clickedMeta.getDisplayName());

        // wrap handler in try/catch to log unexpected errors
        try {
        // Menu Selector
        if (title.equals(GUI_MENU_SELECTOR)) {
            if (type == Material.EMERALD_BLOCK) {
                openPlayerMenu(p);
            } else if (type == Material.REDSTONE_BLOCK) {
                openMainMenu(p);
            } else if (type == Material.BARRIER) {
                p.closeInventory();
            }
            return;
        }

        // Player Menu
        if (title.equals(GUI_PLAYER_MENU)) {
            if (itemName.equals("Set Home")) {
                p.closeInventory();
                dataConfig.set("homes." + p.getUniqueId() + ".x", p.getLocation().getX());
                dataConfig.set("homes." + p.getUniqueId() + ".y", p.getLocation().getY());
                dataConfig.set("homes." + p.getUniqueId() + ".z", p.getLocation().getZ());
                dataConfig.set("homes." + p.getUniqueId() + ".world", p.getWorld().getName());
                dataConfig.set("homes." + p.getUniqueId() + ".yaw", p.getLocation().getYaw());
                dataConfig.set("homes." + p.getUniqueId() + ".pitch", p.getLocation().getPitch());
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Home set!");
            } else if (itemName.equals("TP Home")) {
                p.closeInventory();
                Location homeLoc = getHomeLocation(p.getUniqueId());
                if (homeLoc != null) {
                    p.teleport(homeLoc);
                    p.sendMessage(ChatColor.GREEN + "Teleported home!");
                } else {
                    p.sendMessage(ChatColor.RED + "You have not set a home yet!");
                }
            } else if (itemName.equals("Set Warp")) {
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(null, ActionType.SET_WARP));
                p.sendMessage(ChatColor.GOLD + "Enter warp name:");
            } else if (itemName.equals("View Warps")) {
                openWarpListMenu(p);
            } else if (itemName.equals("Chunk Claims")) {
                openClaimsMenu(p);
            } else if (itemName.equals("Players (TPA)")) {
                openPlayerListTPA(p);
            } else if (itemName.equals("Kits")) {
                openKitListGUI(p);
            } else if (itemName.equals("Report Player")) {
                openReportPlayerList(p);
            } else if (type == Material.BARRIER) {
                p.closeInventory();
            }
            return;
        }

        // Kit List
        if (title.equals(GUI_KIT_LIST)) {
            if (type == Material.BARRIER) {
                p.closeInventory();
            } else if (type != Material.GRAY_STAINED_GLASS_PANE && type != Material.BLACK_STAINED_GLASS_PANE) {
                openKitPreviewGUI(p, itemName);
            }
            return;
        }

        // Kit Preview
        if (title.startsWith(GUI_KIT_PREVIEW)) {
            String kitName = title.replace(GUI_KIT_PREVIEW, "");
            if (type == Material.BARRIER) {
                openKitListGUI(p);
            } else if (type == Material.EMERALD_BLOCK) {
                p.closeInventory();
                claimKit(p, kitName);
            }
            return;
        }

        // Crate List
        if (title.equals(GUI_CRATE_LIST)) {
            if (type == Material.BARRIER) { p.closeInventory(); }
            else if (type != Material.GRAY_STAINED_GLASS_PANE && type != Material.BLACK_STAINED_GLASS_PANE) {
                p.closeInventory();
                openCrateReward(p, itemName);
            }
            return;
        }

        // Bounty List
        if (title.equals(GUI_BOUNTY_LIST)) {
            if (type == Material.BARRIER) p.closeInventory();
            return;
        }

        // Shop List
        if (title.equals(GUI_SHOP_LIST)) {
            if (type == Material.BARRIER) { p.closeInventory(); }
            else if (type != Material.GRAY_STAINED_GLASS_PANE && type != Material.BLACK_STAINED_GLASS_PANE) {
                // Find matching shop listing and purchase
                if (dataConfig.contains("shops")) {
                    for (String shopId : dataConfig.getConfigurationSection("shops").getKeys(false)) {
                        String sItem = dataConfig.getString("shops." + shopId + ".item", "");
                        int sAmt = dataConfig.getInt("shops." + shopId + ".amount", 1);
                        String expected = sAmt + "x " + sItem.replace("_", " ");
                        if (itemName.equalsIgnoreCase(expected)) {
                            int price = dataConfig.getInt("shops." + shopId + ".price", 0);
                            if (p.getLevel() < price) {
                                p.sendMessage(ChatColor.RED + "Not enough XP! Need " + price + " levels.");
                                return;
                            }
                            p.setLevel(p.getLevel() - price);
                            Material mat = Material.valueOf(sItem.toUpperCase());
                            ItemStack bought = new ItemStack(mat, sAmt);
                            HashMap<Integer, ItemStack> overflow = p.getInventory().addItem(bought);
                            for (ItemStack drop : overflow.values()) p.getWorld().dropItemNaturally(p.getLocation(), drop);
                            p.sendMessage(ChatColor.GREEN + "Purchased " + sAmt + "x " + mat.name().replace("_", " ") + "!");
                            String owner = dataConfig.getString("shops." + shopId + ".ownerName", "");
                            p.closeInventory();
                            // Notify seller if online
                            Player seller = Bukkit.getPlayer(owner);
                            if (seller != null) seller.sendMessage(ChatColor.GREEN + p.getName() + " bought " + sAmt + "x " + mat.name().replace("_", " ") + " from your shop!");
                            // Remove listing
                            dataConfig.set("shops." + shopId, null);
                            saveDataFile();
                            logAction(p.getName(), "shop_purchase", shopId);
                            return;
                        }
                    }
                }
            }
            return;
        }

        // Quest List
        if (title.equals(GUI_QUEST_LIST)) {
            if (type == Material.BARRIER) { p.closeInventory(); }
            else if (type == Material.LIME_DYE) {
                // Already completed
                p.sendMessage(ChatColor.GREEN + "Already claimed.");
            } else if (type == Material.YELLOW_DYE) {
                // Check if ready to claim - look for "Click to claim" in lore
                ItemMeta meta = e.getCurrentItem().getItemMeta();
                if (meta != null && meta.hasLore()) {
                    boolean canClaim = false;
                    for (String line : meta.getLore()) {
                        if (ChatColor.stripColor(line).contains("Click to claim")) { canClaim = true; break; }
                    }
                    if (canClaim) {
                        p.closeInventory();
                        claimQuestReward(p, itemName);
                    } else {
                        p.sendMessage(ChatColor.YELLOW + "Quest not yet complete!");
                    }
                }
            }
            return;
        }

        // Auction House GUI click
        if (title.equals(GUI_AUCTION_HOUSE)) {
            if (type == Material.BARRIER) { p.closeInventory(); return; }
            ItemMeta meta = e.getCurrentItem().getItemMeta();
            if (meta != null && meta.hasLore()) {
                String auctionId = null;
                for (String line : meta.getLore()) {
                    String stripped = ChatColor.stripColor(line);
                    if (stripped.startsWith("ID:")) { auctionId = stripped.substring(3); break; }
                }
                if (auctionId != null) {
                    int currentBid = dataConfig.getInt("auctions." + auctionId + ".currentBid", 0);
                    int nextBid = currentBid + dataConfig.getInt("auctions." + auctionId + ".bidIncrement", 5);
                    if (p.getLevel() < nextBid) { p.sendMessage(ChatColor.RED + "Not enough XP to bid (" + nextBid + " levels needed)."); return; }
                    String seller = dataConfig.getString("auctions." + auctionId + ".seller", "");
                    if (seller.equals(p.getUniqueId().toString())) { p.sendMessage(ChatColor.RED + "Can't bid on your own auction."); return; }
                    dataConfig.set("auctions." + auctionId + ".currentBid", nextBid);
                    dataConfig.set("auctions." + auctionId + ".highBidder", p.getUniqueId().toString());
                    dataConfig.set("auctions." + auctionId + ".highBidderName", p.getName());
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Bid placed: " + nextBid + " XP levels!");
                    logAction(p.getName(), "auction_bid", auctionId + " for " + nextBid);
                    p.closeInventory();
                    openAuctionHouseGUI(p);
                }
            }
            return;
        }

        // Player Warp List GUI click
        if (title.equals(GUI_PWARP_LIST)) {
            if (type == Material.BARRIER) { p.closeInventory(); return; }
            ItemMeta meta = e.getCurrentItem().getItemMeta();
            if (meta != null && meta.hasLore()) {
                String warpId = null;
                for (String line : meta.getLore()) {
                    String stripped = ChatColor.stripColor(line);
                    if (stripped.startsWith("ID:")) { warpId = stripped.substring(3); break; }
                }
                if (warpId != null && dataConfig.contains("pwarps." + warpId)) {
                    p.closeInventory();
                    World w = Bukkit.getWorld(dataConfig.getString("pwarps." + warpId + ".world", "world"));
                    if (w != null) {
                        Location loc = new Location(w,
                            dataConfig.getDouble("pwarps." + warpId + ".x"),
                            dataConfig.getDouble("pwarps." + warpId + ".y"),
                            dataConfig.getDouble("pwarps." + warpId + ".z"));
                        p.teleport(loc);
                        dataConfig.set("pwarps." + warpId + ".visits", dataConfig.getInt("pwarps." + warpId + ".visits", 0) + 1);
                        saveDataFile();
                        p.sendMessage(ChatColor.GREEN + "Warped to " + dataConfig.getString("pwarps." + warpId + ".name", warpId));
                    }
                }
            }
            return;
        }

        // Achievements GUI click
        if (title.equals(GUI_ACHIEVEMENTS)) {
            if (type == Material.BARRIER) { p.closeInventory(); }
            return;
        }

        // Poll List
        if (title.equals(GUI_POLL_LIST)) {
            if (type == Material.BARRIER) { p.closeInventory(); return; }
            if (type == Material.PAPER) {
                String pollId = ChatColor.stripColor(clicked.getItemMeta().getLore().get(0)).replace("ID: ", "");
                openPollVote(p, pollId);
            }
            return;
        }

        // Poll Voting
        if (title.startsWith(GUI_POLL_VOTE)) {
            if (type == Material.BARRIER) { openPollList(p); return; }
            if (type == Material.LIME_CONCRETE) {
                String pollId = ChatColor.stripColor(clicked.getItemMeta().getLore().get(0)).replace("ID: ", "");
                int choiceIdx = Integer.parseInt(ChatColor.stripColor(clicked.getItemMeta().getLore().get(1)).replace("Option: ", ""));
                List<String> voters = dataConfig.getStringList("polls." + pollId + ".voters");
                if (voters.contains(p.getUniqueId().toString())) {
                    p.sendMessage(ChatColor.RED + "You already voted on this poll.");
                    p.closeInventory();
                    return;
                }
                List<String> options = dataConfig.getStringList("polls." + pollId + ".options");
                if (choiceIdx < 1 || choiceIdx > options.size()) { p.closeInventory(); return; }
                voters.add(p.getUniqueId().toString());
                dataConfig.set("polls." + pollId + ".voters", voters);
                int current = dataConfig.getInt("polls." + pollId + ".votes." + choiceIdx, 0);
                dataConfig.set("polls." + pollId + ".votes." + choiceIdx, current + 1);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "\u2705 Vote recorded for: " + ChatColor.YELLOW + options.get(choiceIdx - 1));
                p.closeInventory();
            }
            return;
        }

        // Player List for TPA
        if (title.equals(GUI_PLAYER_LIST_TPA)) {
            if (type == Material.BARRIER) {
                openMenuSelector(p);
            } else if (type == Material.PLAYER_HEAD) {
                p.closeInventory();
                Player target = Bukkit.getPlayer(itemName);
                if (target != null) {
                    // Send TPA request
                    tpaRequests.put(p.getUniqueId(), target.getUniqueId());
                    p.sendMessage(ChatColor.GREEN + "TPA request sent to " + target.getName());
                    target.sendMessage(ChatColor.YELLOW + p.getName() + ChatColor.YELLOW + " has sent you a TPA request!");
                    target.sendMessage(ChatColor.AQUA + "Type " + ChatColor.YELLOW + "/tpa accept " + p.getName() + ChatColor.AQUA + " to accept the request");
                } else {
                    p.sendMessage(ChatColor.RED + "That player is no longer online.");
                }
            }
            return;
        }

        // Report Player List
        if (title.equals(GUI_REPORT_PLAYER)) {
            if (type == Material.BARRIER) {
                openPlayerMenu(p);
            } else if (type == Material.PLAYER_HEAD) {
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(itemName, ActionType.REPORT));
                p.sendMessage(ChatColor.GOLD + "Enter the reason for reporting " + ChatColor.YELLOW + itemName + ChatColor.GOLD + ":");
            }
            return;
        }

        // Warp List Menu
        if (title.equals(ChatColor.BLUE + "Warps")) {
            if (type == Material.BARRIER) {
                openPlayerMenu(p);
            } else if (type == Material.FILLED_MAP) {
                openWarpManagementMenu(p, itemName);
            }
            return;
        }

        // Warp Management Menu
        if (title.startsWith(GUI_WARP_MANAGEMENT)) {
            String warpName = title.replace(GUI_WARP_MANAGEMENT, "");
            if (type == Material.BARRIER) {
                openWarpListMenu(p);
            } else if (type == Material.ENDER_PEARL) {
                // Teleport to warp
                p.closeInventory();
                Location warpLoc = getWarpLocation(warpName);
                if (warpLoc != null) {
                    p.teleport(warpLoc);
                    p.sendMessage(ChatColor.GREEN + "Teleported to warp '" + warpName + "'!");
                } else {
                    p.sendMessage(ChatColor.RED + "Warp location not found.");
                }
            } else if (type == Material.REDSTONE) {
                // Open delete confirmation
                openWarpDeleteConfirmation(p, warpName);
            }
            return;
        }

        // Warp Delete Confirmation
        if (title.startsWith(ChatColor.RED + "Delete:")) {
            String warpName = title.replace(ChatColor.RED + "Delete: ", "");
            if (type == Material.BARRIER) {
                openWarpManagementMenu(p, warpName);
            } else if (type == Material.EMERALD_BLOCK) {
                // Confirm delete
                p.closeInventory();
                dataConfig.set("warps." + warpName, null);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Warp '" + warpName + "' deleted!");
                pendingWarpDelete.remove(p.getUniqueId());
            } else if (type == Material.REDSTONE_BLOCK) {
                // Cancel delete
                openWarpManagementMenu(p, warpName);
            }
            return;
        }




        // World Utilities Menu
        if (title.equals(GUI_WORLD_UTILITIES)) {
            p.sendMessage(ChatColor.YELLOW + "[DEBUG] world utilities menu click type=" + type + " name=" + itemName);
            if (type == Material.GRASS_BLOCK || type == Material.NETHER_STAR) {
                p.closeInventory();
                if (type == Material.GRASS_BLOCK) {
                    try { openWorldListMenu(p); }
                    catch (Exception ex) { p.sendMessage(ChatColor.RED + "Error opening world list"); ex.printStackTrace(); }
                }
                else if (type == Material.NETHER_STAR) {
                    try { openCreateWorldTypeMenu(p); }
                    catch (Exception ex) { p.sendMessage(ChatColor.RED + "Error opening world type chooser"); ex.printStackTrace(); }
                }
            } else if (type == Material.BARRIER) {
                openMainMenu(p);
            }
            return;
        }

        if (title.equals(GUI_WORLD_LIST)) {
            if (type == Material.BARRIER) {
                openWorldUtilitiesMenu(p);
            } else if (type == Material.ARROW) {
                int current = menuPages.getOrDefault(p.getUniqueId(), 0);
                openWorldListMenu(p, current + 1);
            } else if (type == Material.GRASS_BLOCK) {
                p.closeInventory();
                openWorldOptionsMenu(p, itemName);
            } else if (type == Material.REDSTONE) {
                // "Back to Utilities" button
                openWorldUtilitiesMenu(p);
            }
            return;
        }

        if (title.startsWith(GUI_WORLD_OPTIONS)) {
            String worldName = title.replace(GUI_WORLD_OPTIONS, "");
            if (type == Material.BARRIER) {
                openWorldListMenu(p);
            } else if (type == Material.ENDER_PEARL) {
                World w = Bukkit.getWorld(worldName);
                if (w != null) {
                    // check lock
                    if (dataConfig.getBoolean("worldlocks." + worldName, false)) {
                        p.sendMessage(ChatColor.RED + "That world is locked.");
                    } else {
                        p.closeInventory();
                        p.teleport(w.getSpawnLocation());
                        p.sendMessage(ChatColor.GREEN + "Teleported to " + worldName);
                    }
                } else p.sendMessage(ChatColor.RED + "World not found.");
            } else if (type == Material.IRON_DOOR) {
                boolean locked = dataConfig.getBoolean("worldlocks." + worldName, false);
                dataConfig.set("worldlocks." + worldName, !locked);
                saveDataFile();
                p.sendMessage(ChatColor.AQUA + "World " + worldName + " " + (locked ? "unlocked" : "locked"));
                openWorldOptionsMenu(p, worldName);
            } else if (type == Material.REDSTONE_BLOCK) {
                p.closeInventory();
                openWorldDeleteConfirmation(p, worldName);
            }
            return;
        }

        if (title.equals(GUI_CREATE_TYPE)) {
            if (type == Material.BARRIER) {
                openWorldUtilitiesMenu(p);
            } else {
                String worldType;
                if (type == Material.GRASS_BLOCK) worldType = "normal";
                else if (type == Material.PUMPKIN) worldType = "flat";
                else if (type == Material.GLASS) worldType = "void";
                else { return; }
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(worldType, ActionType.WORLD_CREATE_NAME));
                p.sendMessage(ChatColor.GOLD + "Enter name for new " + worldType + " world:");
            }
            return;
        }

        if (title.startsWith(GUI_DELETE_CONFIRM)) {
            String worldName = title.replace(GUI_DELETE_CONFIRM, "");
            if (type == Material.BARRIER) {
                openWorldOptionsMenu(p, worldName);
            } else if (type == Material.EMERALD_BLOCK) {
                p.closeInventory();
                World w = Bukkit.getWorld(worldName);
                if (w != null) {
                    Bukkit.unloadWorld(w, false);
                    deleteWorldFolder(new File(w.getWorldFolder().getPath()));
                }
                dataConfig.set("worldlocks." + worldName, null);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "World " + worldName + " deleted.");
            }
            return;
        }
        // Claims Menu
        if (title.equals(GUI_CLAIMS)) {
            if (type == Material.GRASS_BLOCK) {
                openClaimConfirmMenu(p, "claim");
            } else if (type == Material.DIRT) {
                openClaimConfirmMenu(p, "unclaim");
            } else if (type == Material.EMERALD_BLOCK) {
                openTrustPlayerMenu(p);
            } else if (type == Material.REDSTONE_BLOCK) {
                openUntrustPlayerMenu(p);
            } else if (type == Material.AMETHYST_SHARD) {
                p.closeInventory();
                ItemStack wand = new ItemStack(Material.AMETHYST_SHARD);
                ItemMeta wMeta = wand.getItemMeta();
                wMeta.setDisplayName(CLAIM_WAND_NAME);
                wand.setItemMeta(wMeta);
                p.getInventory().addItem(wand);
                p.sendMessage(ChatColor.BLUE + "Claim Wand added to your inventory!");
            } else if (type == Material.BARRIER) {
                openPlayerMenu(p);
            }
            return;
        }

        // Claim Confirmation Menu
        if (title.equals(GUI_CLAIM_CONFIRM) || title.equals(GUI_UNCLAIM_CONFIRM)) {
            String action = pendingClaimAction.get(p.getUniqueId());
            if (action == null) {
                p.closeInventory();
                return;
            }
            
            String[] parts = action.split(":");
            String actionType = parts[0];
            String chunkKey = action.substring(actionType.length() + 1);
            
            if (type == Material.EMERALD_BLOCK) {
                // Confirm
                p.closeInventory();
                if (actionType.equals("claim")) {
                    int limit = getChunkLimit(p.getUniqueId());
                    int claimed = getClaimedChunks(p.getUniqueId()).size();
                    if (claimed < limit) {
                        claimChunk(p.getUniqueId(), chunkKey);
                        p.sendMessage(ChatColor.GREEN + "Chunk claimed!");
                    } else {
                        p.sendMessage(ChatColor.RED + "You have reached your claim limit!");
                    }
                } else {
                    unclaimChunk(p.getUniqueId(), chunkKey);
                    p.sendMessage(ChatColor.GREEN + "Chunk unclaimed!");
                }
                pendingClaimAction.remove(p.getUniqueId());
            } else if (type == Material.REDSTONE_BLOCK) {
                // Cancel
                p.closeInventory();
                pendingClaimAction.remove(p.getUniqueId());
                openClaimsMenu(p);
            }
            return;
        }

        // Trust Player Menu
        if (title.equals(GUI_TRUST_PLAYER)) {
            if (type == Material.PLAYER_HEAD) {
                String playerName = itemName;
                trustPlayer(p.getUniqueId(), playerName);
                p.sendMessage(ChatColor.GREEN + "Trusted " + playerName + "!");
                p.closeInventory();
                openClaimsMenu(p);
            } else if (type == Material.BARRIER) {
                openClaimsMenu(p);
            } else if (type == Material.ARROW) {
                int currentPage = menuPages.getOrDefault(p.getUniqueId(), 0);
                openTrustPlayerMenu(p, currentPage + 1);
            }
            return;
        }

        // Untrust Player Menu
        if (title.equals(GUI_UNTRUST_PLAYER)) {
            if (type == Material.NAME_TAG) {
                String playerName = itemName;
                untrustPlayer(p.getUniqueId(), playerName);
                p.sendMessage(ChatColor.RED + "Removed trust from " + playerName + "!");
                p.closeInventory();
                openClaimsMenu(p);
            } else if (type == Material.BARRIER) {
                openClaimsMenu(p);
            } else if (type == Material.ARROW) {
                int currentPage = menuPages.getOrDefault(p.getUniqueId(), 0);
                openUntrustPlayerMenu(p, currentPage + 1);
            }
            return;
        }


        if (title.equals(GUI_MAIN)) {

            int slot = e.getRawSlot();
            switch(slot) {
                case 11: p.getWorld().setStorm(false); break;
                case 12: p.getWorld().setThundering(false); p.getWorld().setStorm(true); break;
                case 13: p.getWorld().setThundering(true); p.getWorld().setStorm(true); break;
                case 14:
                    // Toggle nether lock
                    boolean currentNether = dataConfig.getBoolean("locks.nether", false);
                    dataConfig.set("locks.nether", !currentNether);
                    saveDataFile();
                    p.sendMessage(ChatColor.AQUA + "Nether access " + (currentNether ? "unlocked" : "locked") + "!");
                    openMainMenu(p);
                    break;
                case 15:
                    // Toggle end lock
                    boolean currentEnd = dataConfig.getBoolean("locks.end", false);
                    dataConfig.set("locks.end", !currentEnd);
                    saveDataFile();
                    p.sendMessage(ChatColor.AQUA + "The End access " + (currentEnd ? "unlocked" : "locked") + "!");
                    openMainMenu(p);
                    break;
                case 19: p.getWorld().setTime(1000); break;
                case 20: p.getWorld().setTime(13000); break;
                case 21: openPlayerListMenu(p); break;
                case 22: p.setGameMode(GameMode.CREATIVE); break;
                case 23: p.setGameMode(GameMode.SURVIVAL); break;
                case 24: 
                    for (Player o : Bukkit.getOnlinePlayers()) { o.setHealth(20); o.setFoodLevel(20); }
                    p.sendMessage(ChatColor.GREEN + "Healed all."); break;
                case 28: p.getInventory().addItem(createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME)); break;
                case 29: 
                    p.closeInventory();
                    pendingActions.put(p.getUniqueId(), new PunishmentContext(null, ActionType.ANNOUNCE));
                    p.sendMessage(ChatColor.GOLD + "Enter broadcast message:"); break;
                case 30: openTicketListMenu(p); break;
                case 31: 
                    p.sendMessage(ChatColor.YELLOW + "[DEBUG] main menu world utilities clicked slot=" + slot);
                    try { openWorldUtilitiesMenu(p); } catch (Exception ex) {
                        p.sendMessage(ChatColor.RED + "Error opening world utilities menu");
                        ex.printStackTrace();
                    }
                    break;
                case 16: openPunishedPlayersMenu(p); break;
                case 43: p.closeInventory(); break;
            }
        } else if (title.equals(GUI_TICKET_LIST)) {
            if (type == Material.REDSTONE) { openMainMenu(p); return; }
            // Any colored wool or redstone block = ticket item, click to open detail
            if (type == Material.YELLOW_WOOL || type == Material.ORANGE_WOOL || type == Material.LIME_WOOL || type == Material.REDSTONE_BLOCK) {
                String id = ChatColor.stripColor(itemName).replace("Ticket #", "").trim();
                openTicketDetailMenu(p, id);
            }
        } else if (title.startsWith(GUI_TICKET_DETAIL)) {
            String ticketId = title.replace(GUI_TICKET_DETAIL, "").trim();
            if (type == Material.REDSTONE) { openTicketListMenu(p); return; }
            if (type == Material.WRITABLE_BOOK) {
                // Add response via chat
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(ticketId, ActionType.TICKET_RESPOND));
                p.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + "Type your response for ticket #" + ticketId + ":");
            } else if (type == Material.ARROW) {
                // Set in progress
                dataConfig.set("tickets." + ticketId + ".status", "in_progress");
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " set to in_progress.");
                openTicketDetailMenu(p, ticketId);
            } else if (type == Material.GOLD_INGOT) {
                // Cycle priority
                String current = dataConfig.getString("tickets." + ticketId + ".priority", "medium");
                String next;
                switch (current) {
                    case "low": next = "medium"; break;
                    case "medium": next = "high"; break;
                    case "high": next = "critical"; break;
                    default: next = "low"; break;
                }
                dataConfig.set("tickets." + ticketId + ".priority", next);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " priority set to " + next + ".");
                openTicketDetailMenu(p, ticketId);
            } else if (type == Material.ARMOR_STAND) {
                // Assign to me
                dataConfig.set("tickets." + ticketId + ".assignee", p.getName());
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " assigned to you.");
                openTicketDetailMenu(p, ticketId);
            } else if (type == Material.EMERALD_BLOCK) {
                // Resolve
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(ticketId, ActionType.TICKET_RESOLVE));
                p.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + "Type a resolution reason for ticket #" + ticketId + ":");
            } else if (type == Material.BARRIER) {
                // Close ticket
                dataConfig.set("tickets." + ticketId + ".status", "closed");
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " closed.");
                // Notify player
                String ticketPlayer = dataConfig.getString("tickets." + ticketId + ".player", "");
                Player target = Bukkit.getPlayer(ticketPlayer);
                if (target != null && target.isOnline()) {
                    target.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + "Your ticket #" + ticketId + " has been closed by " + p.getName() + ".");
                }
                openTicketListMenu(p);
            } else if (type == Material.ENDER_PEARL) {
                // Teleport to ticket location
                String world = dataConfig.getString("tickets." + ticketId + ".world");
                if (world != null && Bukkit.getWorld(world) != null) {
                    int x = dataConfig.getInt("tickets." + ticketId + ".x");
                    int y = dataConfig.getInt("tickets." + ticketId + ".y");
                    int z = dataConfig.getInt("tickets." + ticketId + ".z");
                    p.teleport(new Location(Bukkit.getWorld(world), x + 0.5, y, z + 0.5));
                    p.closeInventory();
                    p.sendMessage(ChatColor.GREEN + "Teleported to ticket #" + ticketId + " location.");
                } else {
                    p.sendMessage(ChatColor.RED + "World not found for this ticket.");
                }
            }
        } else if (title.equals(ChatColor.RED + "Punished Players")) {
            if (type == Material.PLAYER_HEAD) openPlayerActionMenu(p, itemName);
            else if (type == Material.REDSTONE) openMainMenu(p);
        } else if (title.equals(GUI_PLAYER_LIST)) {
            if (type == Material.PLAYER_HEAD) openPlayerActionMenu(p, itemName);
            else if (type == Material.REDSTONE) openMainMenu(p);
            else if (type == Material.ARROW) {
                int currentPage = menuPages.getOrDefault(p.getUniqueId(), 0);
                openPlayerListMenu(p, currentPage + 1);
            }
        } else if (title.startsWith(GUI_NOTES_VIEW)) {
            String target = title.replace(GUI_NOTES_VIEW, "");
            if (type == Material.WRITABLE_BOOK) {
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(target, ActionType.ADD_NOTE));
                p.sendMessage(ChatColor.GOLD + "Enter note:");
            } else if (type == Material.REDSTONE) {
                openPlayerActionMenu(p, target);
            } else if (type == Material.PAPER) {
                // User clicked on a note - find it by display name
                String clickedNoteName = itemName;
                UUID uuid = Bukkit.getOfflinePlayer(target).getUniqueId();
                List<String> notesList = dataConfig.getStringList("notes." + uuid);
                
                // Find which note index was clicked
                for (int i = 0; i < notesList.size(); i++) {
                    if (notesList.get(i).equals(clickedNoteName)) {
                        openNoteManagementMenu(p, target, i);
                        break;
                    }
                }
            }
        } else if (title.startsWith(ChatColor.YELLOW + "Note:")) {
            // Note Management Menu
            String targetKey = null;
            for (String key : pendingNoteEdit.keySet()) {
                if (key.startsWith(p.getUniqueId().toString())) {
                    targetKey = key;
                    break;
                }
            }
            
            if (targetKey != null) {
                String targetName = targetKey.split(":")[1];
                int noteIndex = pendingNoteEdit.get(targetKey);
                
                if (type == Material.BARRIER) {
                    // Back to notes
                    openPlayerNotesMenu(p, targetName);
                } else if (type == Material.BOOK) {
                    // View full note
                    UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
                    List<String> notesList = dataConfig.getStringList("notes." + uuid);
                    if (noteIndex < notesList.size()) {
                        p.sendMessage(ChatColor.GOLD + "=== Full Note ===");
                        p.sendMessage(ChatColor.YELLOW + notesList.get(noteIndex));
                    }
                } else if (type == Material.WRITABLE_BOOK) {
                    // Edit note
                    p.closeInventory();
                    pendingActions.put(p.getUniqueId(), new PunishmentContext(targetName, ActionType.ADD_NOTE));
                    p.sendMessage(ChatColor.GOLD + "Enter updated note:");
                } else if (type == Material.REDSTONE_BLOCK) {
                    // Delete note
                    UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
                    List<String> notesList = dataConfig.getStringList("notes." + uuid);
                    if (noteIndex < notesList.size()) {
                        notesList.remove(noteIndex);
                        dataConfig.set("notes." + uuid, notesList);
                        saveDataFile();
                        p.sendMessage(ChatColor.RED + "Note deleted.");
                        openPlayerNotesMenu(p, targetName);
                    }
                }
            }
            return;
        } else if (title.startsWith(GUI_PLAYER_ACTION)) {
            String targetName = title.replace(GUI_PLAYER_ACTION, "");
            Player target = Bukkit.getPlayer(targetName);
            UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();

            if (type == Material.REDSTONE) { openPlayerListMenu(p); return; }
            if (type == Material.BOOK) { openPlayerNotesMenu(p, targetName); return; }

            p.closeInventory();
            switch(type) {
                case COMPASS: if (target!=null) p.teleport(target); break;
                case ENDER_PEARL: if (target!=null) target.teleport(p); break;
                case CHEST: if (target!=null) p.openInventory(target.getInventory()); break;
                case ENDER_CHEST: if (target!=null) p.openInventory(target.getEnderChest()); break;
                case GOLDEN_APPLE: if (target!=null) { target.setHealth(20); target.setFoodLevel(20); } break;
                case GOAT_HORN: pendingActions.put(p.getUniqueId(), new PunishmentContext(targetName, ActionType.WARN)); p.sendMessage(ChatColor.AQUA + "Enter WARN reason:"); break;
                case IRON_DOOR: pendingActions.put(p.getUniqueId(), new PunishmentContext(targetName, ActionType.KICK)); p.sendMessage(ChatColor.AQUA + "Enter KICK reason:"); break;
                case BARRIER: pendingActions.put(p.getUniqueId(), new PunishmentContext(targetName, ActionType.BAN)); p.sendMessage(ChatColor.AQUA + "Enter BAN reason:"); break;
                case IRON_BARS:
                    long d = itemName.contains("1hr") ? 3600000 : itemName.contains("3hr") ? 10800000 : 86400000;
                    setPunished(uuid, d);
                    p.sendMessage(ChatColor.RED + "Punished " + targetName); break;
                case MILK_BUCKET: 
                    removePunishment(uuid); 
                    p.sendMessage(ChatColor.GREEN + "Unpunished " + targetName); break;
            }
        }
        } catch (Exception ex) {
            getLogger().severe("Error handling GUI click (" + title + "): " + ex.getMessage());
            ex.printStackTrace();
        }
    }


    // --- LISTENERS ---
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) {
            // Prevent punished players from moving
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
                e.setCancelled(true);
            }
            return;
        }
        
        // AFK Activity Tracking
        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());

        // Chunk Entry/Exit Logic
        Player p = e.getPlayer();
        if (e.getTo() == null) return;
        
        String newChunk = getChunkKey(e.getTo());
        String oldChunk = currentChunk.getOrDefault(p.getUniqueId(), newChunk);
        
        if (!newChunk.equals(oldChunk)) {
            // Leaving a claimed chunk
            if (isChunkClaimed(oldChunk)) {
                UUID owner = getChunkOwner(oldChunk);
                if (owner != null && !p.getUniqueId().equals(owner)) {
                    Player ownerPlayer = Bukkit.getPlayer(owner);
                    String ownerName = ownerPlayer != null ? ownerPlayer.getName() : "Unknown";
                    p.sendMessage(ChatColor.YELLOW + "You have left " + ownerName + "'s claim!");
                }
            }
            
            // Entering a claimed chunk
            if (isChunkClaimed(newChunk)) {
                UUID owner = getChunkOwner(newChunk);
                if (owner != null && !p.getUniqueId().equals(owner) && !isTrustedInChunk(p, newChunk)) {
                    Player ownerPlayer = Bukkit.getPlayer(owner);
                    String ownerName = ownerPlayer != null ? ownerPlayer.getName() : "Unknown";
                    p.sendMessage(ChatColor.YELLOW + "You have entered " + ownerName + "'s claim!");
                }
            }
            currentChunk.put(p.getUniqueId(), newChunk);
        }
    }
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        // prevent players from using portals when locks are enabled
        Player p = e.getPlayer();
        if (e.getTo() == null) return;
        World.Environment env = e.getTo().getWorld().getEnvironment();
        if (env == World.Environment.NETHER && dataConfig.getBoolean("locks.nether", false)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Nether access is locked.");
        } else if (env == World.Environment.THE_END && dataConfig.getBoolean("locks.end", false)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "The End access is locked.");
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Location loc = e.getRespawnLocation();
        if (loc == null) return;
        World.Environment env = loc.getWorld().getEnvironment();
        if (env == World.Environment.NETHER && dataConfig.getBoolean("locks.nether", false)) {
            e.setRespawnLocation(Bukkit.getWorlds().get(0).getSpawnLocation());
            e.getPlayer().sendMessage(ChatColor.RED + "Nether access is locked. Respawning in overworld.");
        }
        if (env == World.Environment.THE_END && dataConfig.getBoolean("locks.end", false)) {
            e.setRespawnLocation(Bukkit.getWorlds().get(0).getSpawnLocation());
            e.getPlayer().sendMessage(ChatColor.RED + "The End access is locked. Respawning in overworld.");
        }
    }
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        // catch teleport commands / plugins
        if (e.getTo() == null) return;
        World.Environment env = e.getTo().getWorld().getEnvironment();
        Player p = e.getPlayer();
        if (env == World.Environment.NETHER && dataConfig.getBoolean("locks.nether", false)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Nether access is locked.");
        }
        if (env == World.Environment.THE_END && dataConfig.getBoolean("locks.end", false)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "The End access is locked.");
        }
    }

    @EventHandler
    public void onPunishDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isPunished(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (isPunished(e.getPlayer().getUniqueId())) e.setCancelled(true);
        else {
            // Check chunk claims
            String chunkKey = getChunkKey(e.getBlock().getLocation());
            if (isChunkClaimed(chunkKey) && !isTrustedInChunk(e.getPlayer(), chunkKey)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot build in this claimed chunk!");
                return;
            }
            saveLog(e.getBlock().getLocation(), ChatColor.GREEN + "Placed by " + e.getPlayer().getName());
        }
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (isPunished(e.getPlayer().getUniqueId())) e.setCancelled(true);
        else {
            // Check chunk claims
            String chunkKey = getChunkKey(e.getBlock().getLocation());
            if (isChunkClaimed(chunkKey) && !isTrustedInChunk(e.getPlayer(), chunkKey)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot break blocks in this claimed chunk!");
                return;
            }
            saveLog(e.getBlock().getLocation(), ChatColor.RED + "Broken by " + e.getPlayer().getName());
            // Quest tracking
            trackQuestProgress(e.getPlayer(), "break_blocks", 1);
            trackQuestProgress(e.getPlayer(), "mine_" + e.getBlock().getType().name().toLowerCase(), 1);
        }
    }
    @EventHandler
    public void onChestAccess(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player) {
            lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
        if (e.getInventory().getType() == InventoryType.CHEST) {
            saveLog(e.getInventory().getLocation(), ChatColor.YELLOW + "Opened by " + e.getPlayer().getName());
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClickForAfk(InventoryClickEvent e) {
        lastActivity.put(e.getWhoClicked().getUniqueId(), System.currentTimeMillis());
    }
    @EventHandler
    public void onWandUse(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getItem() != null && e.getItem().hasItemMeta()) {
            String wandName = e.getItem().getItemMeta().getDisplayName();
            
            // Inspector Wand
            if (wandName.equals(INSPECTOR_NAME)) {
                e.setCancelled(true);
                Location checkLocation = e.getClickedBlock().getLocation();
                if (e.getPlayer().isSneaking()) {
                    checkLocation = checkLocation.add(0, 1, 0);
                    e.getPlayer().sendMessage(ChatColor.AQUA + "--- Block Above History ---");
                } else {
                    e.getPlayer().sendMessage(ChatColor.AQUA + "--- Block History ---");
                }
                List<String> logs = getLogs(checkLocation);
                if (logs.isEmpty()) e.getPlayer().sendMessage(ChatColor.RED + "No history.");
                else logs.forEach(l -> e.getPlayer().sendMessage(ChatColor.GRAY + "- " + l));
            }
            // Claim Wand
            else if (wandName.equals(CLAIM_WAND_NAME)) {
                e.setCancelled(true);
                Player p = e.getPlayer();
                String chunkKey = getChunkKey(e.getClickedBlock().getLocation());
                
                if (p.isSneaking()) {
                    // List all claimed chunks
                    List<String> claimed = getClaimedChunks(p.getUniqueId());
                    p.sendMessage(ChatColor.BLUE + "--- Your Claimed Chunks ---");
                    if (claimed.isEmpty()) {
                        p.sendMessage(ChatColor.RED + "You have no claimed chunks.");
                    } else {
                        for (String chunk : claimed) {
                            String coords = formatChunkCoordinates(chunk);
                            p.sendMessage(ChatColor.YELLOW + coords);
                        }
                    }
                } else {
                    // Check current chunk and highlight corners
                    highlightChunkCorners(e.getClickedBlock().getLocation());
                    if (isChunkClaimed(chunkKey)) {
                        UUID owner = getChunkOwner(chunkKey);
                        Player ownerPlayer = Bukkit.getPlayer(owner);
                        String ownerName = ownerPlayer != null ? ownerPlayer.getName() : "Unknown";
                        p.sendMessage(ChatColor.YELLOW + "Chunk claimed by " + ownerName);
                    } else {
                        p.sendMessage(ChatColor.GRAY + "Unclaimed");
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerGeneralActivity(PlayerInteractEvent e) {
        // General activity tracking for any interaction
        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onToolUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!item.getItemMeta().getDisplayName().equals(TOOL_NAME)) return;
        
        Player p = e.getPlayer();
        e.setCancelled(true);
        
        if (p.hasPermission("dmt.admin") || p.isOp()) {
            openMenuSelector(p);
        } else {
            openPlayerMenu(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent e) {
        if (dataConfig.getBoolean("maintenance.enabled", false)) {
            String name = e.getPlayer().getName();
            List<String> whitelist = dataConfig.getStringList("maintenance.whitelist");
            boolean isExempt = whitelist.contains(name);
            if (!isExempt) {
                String msg = dataConfig.getString("maintenance.message", "Server is under maintenance...");
                e.disallow(PlayerLoginEvent.Result.KICK_OTHER, ChatColor.RED + msg);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        lastActivity.put(uuid, System.currentTimeMillis());
        
        if (isPunished(uuid)) {
            punishTeam.addEntry(e.getPlayer().getName());
            long min = (dataConfig.getLong("punishments." + uuid) - System.currentTimeMillis()) / 60000;
            e.getPlayer().sendMessage(ChatColor.RED + "You are punished for " + min + " more minutes.");
        } else {
            // Player is not currently punished - check if they were punished while offline
            Location playerLoc = getLoc("player_location." + uuid);
            if (playerLoc != null) {
                // Restore to original location and clean up (delay 1 tick)
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (e.getPlayer().isOnline()) {
                        e.getPlayer().teleport(playerLoc);
                        e.getPlayer().sendMessage(ChatColor.GREEN + "You have been restored to your original location.");
                    }
                }, 1L);
                dataConfig.set("player_location." + uuid, null);
                dataConfig.set("respawn_location." + uuid, null);
                saveDataFile();
            }
        }
        
        // Also check for respawn location (backup in case it was set)
        Location respawnLoc = getLoc("respawn_location." + uuid);
        if (respawnLoc != null) {
            // Delay respawn teleport by 1 tick to ensure player is fully ready
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (e.getPlayer().isOnline()) {
                    e.getPlayer().teleport(respawnLoc);
                    e.getPlayer().sendMessage(ChatColor.GREEN + "You have been restored to your original location.");
                }
            }, 1L);
            dataConfig.set("respawn_location." + uuid, null);
            saveDataFile();
        }
        
        // Track IPs, sessions and log join
        try {
            this.trackPlayerIP(uuid, e.getPlayer().getName(), e.getPlayer().getAddress().getAddress().getHostAddress());
        } catch (Exception ignored) {}
        this.trackSession(uuid, e.getPlayer().getName(), true);
        this.logAction("System", "player_joined", e.getPlayer().getName());

        // Give admin tool if missing
        boolean hasTool = Arrays.stream(e.getPlayer().getInventory().getContents()).anyMatch(i -> i != null && i.hasItemMeta() && i.getItemMeta().getDisplayName().equals(TOOL_NAME));
        if (!hasTool) {
            ItemStack tool = new ItemStack(Material.DIAMOND);
            ItemMeta m = tool.getItemMeta();
            m.setDisplayName(TOOL_NAME);
            m.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            tool.setItemMeta(m);
            e.getPlayer().getInventory().addItem(tool);
        }

        fireDiscordEvent("joins", "Player Joined", "**" + e.getPlayer().getName() + "** joined the server.", 0x4ec9b0, e.getPlayer().getName());

        // --- PERMISSION GROUPS ---
        applyPermissionGroup(e.getPlayer());

        // --- DAILY LOGIN REWARDS ---
        if (dataConfig.getBoolean("daily_login_enabled", false)) {
            long lastLogin = dataConfig.getLong("daily_login." + uuid + ".last", 0);
            long now = System.currentTimeMillis();
            long dayMs = 86400000L;
            boolean isNewDay = (now - lastLogin) >= dayMs;
            if (isNewDay) {
                int streak = dataConfig.getInt("daily_login." + uuid + ".streak", 0);
                if ((now - lastLogin) < dayMs * 2) {
                    streak++;
                } else {
                    streak = 1; // reset if missed a day
                }
                dataConfig.set("daily_login." + uuid + ".streak", streak);
                dataConfig.set("daily_login." + uuid + ".last", now);
                dataConfig.set("daily_login." + uuid + ".total", dataConfig.getInt("daily_login." + uuid + ".total", 0) + 1);
                saveDataFile();
                int xpReward = dataConfig.getInt("daily_login_base_xp", 10) + (streak * dataConfig.getInt("daily_login_streak_bonus", 2));
                e.getPlayer().giveExp(xpReward);
                e.getPlayer().sendMessage(ChatColor.GOLD + "⭐ Daily Login Reward! " + ChatColor.GREEN + "+" + xpReward + " XP " + ChatColor.YELLOW + "(Streak: " + streak + " days)");
                logAction("System", "daily_login", e.getPlayer().getName() + " streak:" + streak + " xp:" + xpReward);
            }
        }

        // --- FIRST JOIN WELCOME ---
        if (!dataConfig.contains("first_join." + uuid)) {
            dataConfig.set("first_join." + uuid, System.currentTimeMillis());
            saveDataFile();
            String welcomeMsg = dataConfig.getString("welcome_message", "&6Welcome to the server, &e{player}&6!");
            welcomeMsg = welcomeMsg.replace("{player}", e.getPlayer().getName());
            String finalMsg = ChatColor.translateAlternateColorCodes('&', welcomeMsg);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (e.getPlayer().isOnline()) {
                    e.getPlayer().sendMessage(finalMsg);
                    // Give starter items
                    List<String> starterItems = dataConfig.getStringList("welcome_starter_items");
                    for (String item : starterItems) {
                        try {
                            String[] parts = item.split(":");
                            Material mat = Material.valueOf(parts[0].toUpperCase());
                            int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                            e.getPlayer().getInventory().addItem(new ItemStack(mat, amount));
                        } catch (Exception ignored2) {}
                    }
                }
            }, 20L);
            // Broadcast to server
            if (dataConfig.getBoolean("welcome_broadcast", true)) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "✦ " + ChatColor.YELLOW + e.getPlayer().getName() + ChatColor.GOLD + " joined for the first time! Welcome!");
            }
            fireDiscordEvent("joins", "New Player!", "**" + e.getPlayer().getName() + "** joined for the first time! 🎉", 0xf1c40f, e.getPlayer().getName());
        }

        // --- APPLY NICKNAME ---
        String nick = dataConfig.getString("nicknames." + uuid);
        if (nick != null && !nick.isEmpty()) {
            e.getPlayer().setDisplayName(ChatColor.translateAlternateColorCodes('&', nick));
        }

        // --- INACTIVE ALERT TRACKING ---
        dataConfig.set("last_seen." + uuid, System.currentTimeMillis());
        dataConfig.set("last_seen_name." + uuid, e.getPlayer().getName());
        saveDataFile();

        // --- TICKET NOTIFICATIONS ON JOIN ---
        if (dataConfig.contains("tickets")) {
            int updatedCount = 0;
            for (String key : dataConfig.getConfigurationSection("tickets").getKeys(false)) {
                if (key.equals("next_id")) continue;
                String ticketPlayer = dataConfig.getString("tickets." + key + ".player", "");
                if (ticketPlayer.equalsIgnoreCase(e.getPlayer().getName()) && dataConfig.getBoolean("tickets." + key + ".has_new_response", false)) {
                    updatedCount++;
                    dataConfig.set("tickets." + key + ".has_new_response", false);
                }
            }
            if (updatedCount > 0) {
                saveDataFile();
                final int count = updatedCount;
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (e.getPlayer().isOnline()) {
                        e.getPlayer().sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + "You have " + count + " ticket(s) with new responses! Use /ticket list to check.");
                    }
                }, 40L);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastActivity.remove(e.getPlayer().getUniqueId());
        removePermissionAttachment(e.getPlayer());
        this.trackSession(e.getPlayer().getUniqueId(), e.getPlayer().getName(), false);
        this.logAction("System", "player_left", e.getPlayer().getName());
        fireDiscordEvent("leaves", "Player Left", "**" + e.getPlayer().getName() + "** left the server.", 0xe74c3c, e.getPlayer().getName());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (isMuted(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You are muted and cannot chat.");
            return;
        }

        // Auto-moderation
        if (dataConfig.getBoolean("automod.enabled", false) && !e.getPlayer().hasPermission("dmt.admin")) {
            String msg = e.getMessage().toLowerCase();
            // Word filter
            if (dataConfig.getBoolean("automod.filter_enabled", true)) {
                for (String word : chatFilterWords) {
                    if (msg.contains(word.toLowerCase())) {
                        e.setCancelled(true);
                        e.getPlayer().sendMessage(ChatColor.RED + "Your message was blocked by the chat filter.");
                        int violations = dataConfig.getInt("automod.violations." + e.getPlayer().getUniqueId(), 0) + 1;
                        dataConfig.set("automod.violations." + e.getPlayer().getUniqueId(), violations);
                        int maxViolations = dataConfig.getInt("automod.max_violations", 5);
                        if (violations >= maxViolations) {
                            Bukkit.getScheduler().runTask(this, () -> {
                                mutePlayer(e.getPlayer().getUniqueId(), e.getPlayer().getName(), "Auto-muted: chat filter (" + violations + " violations)");
                                e.getPlayer().sendMessage(ChatColor.RED + "You have been auto-muted for repeated filter violations.");
                            });
                        }
                        Bukkit.getScheduler().runTask(this, this::saveDataFile);
                        return;
                    }
                }
            }
            // Spam detection
            if (dataConfig.getBoolean("automod.antispam_enabled", true)) {
                long now = System.currentTimeMillis();
                long lastTime = lastChatTime.getOrDefault(e.getPlayer().getUniqueId(), 0L);
                int cooldownMs = dataConfig.getInt("automod.spam_cooldown_ms", 1000);
                if (now - lastTime < cooldownMs) {
                    int count = spamCounter.getOrDefault(e.getPlayer().getUniqueId(), 0) + 1;
                    spamCounter.put(e.getPlayer().getUniqueId(), count);
                    if (count >= dataConfig.getInt("automod.spam_threshold", 4)) {
                        e.setCancelled(true);
                        e.getPlayer().sendMessage(ChatColor.RED + "Slow down! You are sending messages too fast.");
                        spamCounter.put(e.getPlayer().getUniqueId(), 0);
                        return;
                    }
                } else {
                    spamCounter.put(e.getPlayer().getUniqueId(), 0);
                }
                lastChatTime.put(e.getPlayer().getUniqueId(), now);
            }
            // Caps filter
            if (dataConfig.getBoolean("automod.caps_filter", true) && e.getMessage().length() > 6) {
                long caps = e.getMessage().chars().filter(Character::isUpperCase).count();
                if (caps > e.getMessage().length() * 0.7) {
                    e.setMessage(e.getMessage().toLowerCase());
                    e.getPlayer().sendMessage(ChatColor.YELLOW + "Please don't use excessive caps.");
                }
            }
        }

        // Chat tag + nickname formatting
        String chatTag = dataConfig.getString("chat_tags." + e.getPlayer().getUniqueId(), "");
        String displayName = e.getPlayer().getDisplayName();
        String prefix = "";
        if (!chatTag.isEmpty()) {
            prefix = ChatColor.translateAlternateColorCodes('&', chatTag) + " ";
        }
        e.setFormat(prefix + displayName + ChatColor.WHITE + ": " + e.getMessage().replace("%", "%%"));

        this.addChatLog(e.getPlayer().getName(), e.getMessage());
    }

    public void setPunished(UUID u, long d) {
        dataConfig.set("punishments." + u, System.currentTimeMillis() + d);
        
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            // Store player's current location for later restoration
            saveLoc("player_location." + u, p.getLocation());
            
            punishTeam.addEntry(p.getName());
            p.sendMessage(ChatColor.RED + "You are punished! You cannot move/build.");
            
            // Teleport to punishment location if set
            Location punishLoc = getLoc("punishment_location");
            if (punishLoc != null) {
                p.teleport(punishLoc);
                p.sendMessage(ChatColor.RED + "You have been teleported to the punishment location.");
            }
        }
        saveDataFile();
    }
    
    public void removePunishment(UUID u) {
        dataConfig.set("punishments." + u, null);
        saveDataFile();
        Player p = Bukkit.getPlayer(u);
        // Restore player's original location
        Location originalLoc = getLoc("player_location." + u);
        if (originalLoc != null) {
            if (p != null) {
                // Player is online - schedule teleport next tick to ensure they're fully loaded
                punishTeam.removeEntry(p.getName());
                p.sendMessage(ChatColor.GREEN + "Punishment lifted.");
                Bukkit.getScheduler().runTask(this, () -> {
                    if (p.isOnline()) {
                        p.teleport(originalLoc);
                        p.sendMessage(ChatColor.GREEN + "You have been restored to your original location.");
                    }
                });
            } else {
                    // Player is offline - store spawn location for when they rejoin
                    // Use saveLoc to ensure a consistent string format the plugin expects
                    saveLoc("respawn_location." + u, originalLoc);
                }
        }
        // Clear stored location
        dataConfig.set("player_location." + u, null);
        saveDataFile();
    }
    private void saveLog(Location loc, String msg) {
        if (loc == null) return;
        String k = "logs." + loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        List<String> l = dataConfig.getStringList(k);
        l.add(msg + " (" + new SimpleDateFormat("HH:mm").format(new Date()) + ")");
        if (l.size() > 10) l.remove(0);
        dataConfig.set(k, l);
        saveDataFile();
    }

    private List<String> getLogs(Location loc) {
        return dataConfig.getStringList("logs." + loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    private void saveLoc(String p, Location l) { dataConfig.set(p, l.getWorld().getName()+","+l.getX()+","+l.getY()+","+l.getZ()+","+l.getYaw()+","+l.getPitch()); saveDataFile(); }
    private Location getLoc(String p) {
        if (!dataConfig.contains(p)) return null;
        try {
            // Prefer the CSV string format produced by saveLoc
            if (dataConfig.isString(p)) {
                String raw = dataConfig.getString(p);
                if (raw == null) return null;
                String[] s = raw.split(",");
                if (s.length == 6) {
                    World w = Bukkit.getWorld(s[0]);
                    if (w == null) return null;
                    return new Location(w, Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3]), Float.parseFloat(s[4]), Float.parseFloat(s[5]));
                } else {
                    getLogger().warning("Invalid saved location string for key '" + p + "': " + raw);
                    return null;
                }
            }

            // Also accept the alternative structured format used elsewhere (world/x/y/z/yaw/pitch)
            if (dataConfig.contains(p + ".world")) {
                String worldName = dataConfig.getString(p + ".world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) return null;
                double x = dataConfig.getDouble(p + ".x");
                double y = dataConfig.getDouble(p + ".y");
                double z = dataConfig.getDouble(p + ".z");
                float yaw = (float) dataConfig.getDouble(p + ".yaw");
                float pitch = (float) dataConfig.getDouble(p + ".pitch");
                return new Location(world, x, y, z, yaw, pitch);
            }

            // If the value was stored as a Location object (unlikely), try casting
            Object obj = dataConfig.get(p);
            if (obj instanceof Location) return (Location) obj;

            // Unknown format
            getLogger().warning("Unknown location format for key '" + p + "' (type: " + (obj == null ? "null" : obj.getClass().getName()) + ")");
            return null;
        } catch (NumberFormatException ex) {
            getLogger().warning("Failed to parse numeric value for location key '" + p + "': " + ex.getMessage());
            return null;
        } catch (Exception ex) {
            getLogger().warning("Unexpected error parsing location for key '" + p + "': " + ex.getMessage());
            return null;
        }
    }

    private ItemStack createGuiItem(Material m, String n) { ItemStack i = new ItemStack(m); ItemMeta im = i.getItemMeta(); im.setDisplayName(n); i.setItemMeta(im); return i; }
    private ItemStack createGuiItem(Material m, String n, List<String> l) { ItemStack i = createGuiItem(m, n); ItemMeta im = i.getItemMeta(); im.setLore(l); i.setItemMeta(im); return i; }

    // --- WebServer helper methods (added to satisfy WebServer calls) ---
    public long getPlaytimeHours(UUID u) {
        try {
            return dataConfig.getLong("playtime." + u, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    public Map<UUID, Long> getLastActivity() { return lastActivity; }

    public int getAfkTimeoutMinutes() {
        return dataConfig.getInt("afk_timeout_minutes", 30);
    }

    public void setAfkTimeoutMinutes(int minutes) {
        dataConfig.set("afk_timeout_minutes", minutes);
        saveDataFile();
    }

    public void logAction(String actor, String action, String target) {
        List<String> history = dataConfig.getStringList("action_history");
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + actor + " " + action + " " + target;
        history.add(entry);
        if (history.size() > 200) history.remove(0);
        dataConfig.set("action_history", history);
        saveDataFile();
    }

    public void addChatLog(String source, String msg) {
        List<String> chat = dataConfig.getStringList("chat_history");
        String entry = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " | " + source + ": " + msg;
        chat.add(entry);
        if (chat.size() > 500) chat.remove(0);
        dataConfig.set("chat_history", chat);
        saveDataFile();
    }

    public void addWarning(UUID uuid, String reason) {
        List<String> warns = dataConfig.getStringList("warnings." + uuid);
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + reason;
        warns.add(entry);
        dataConfig.set("warnings." + uuid, warns);
        saveDataFile();
    }

    public Map<String, Object> getTicketData(int id) {
        Map<String, Object> m = new HashMap<>();
        String base = "tickets." + id;
        if (!dataConfig.contains(base)) return m;
        m.put("id", id);
        m.put("player", dataConfig.getString(base + ".player", ""));
        m.put("message", dataConfig.getString(base + ".message", ""));
        m.put("status", dataConfig.getString(base + ".status", "open"));
        m.put("priority", dataConfig.getString(base + ".priority", "medium"));
        m.put("category", dataConfig.getString(base + ".category", "other"));
        m.put("assignee", dataConfig.getString(base + ".assignee", ""));
        m.put("timestamp", dataConfig.getString(base + ".timestamp", ""));
        m.put("resolution", dataConfig.getString(base + ".resolution", ""));
        // Location
        if (dataConfig.contains(base + ".world")) {
            Map<String, Object> loc = new HashMap<>();
            loc.put("world", dataConfig.getString(base + ".world", ""));
            loc.put("x", dataConfig.getInt(base + ".x", 0));
            loc.put("y", dataConfig.getInt(base + ".y", 0));
            loc.put("z", dataConfig.getInt(base + ".z", 0));
            m.put("location", loc);
        }
        // Parse responses into proper objects
        List<Map<String, String>> parsedResponses = new ArrayList<>();
        for (String raw : dataConfig.getStringList(base + ".responses")) {
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
        String path = "tickets." + id + ".responses";
        List<String> responses = dataConfig.getStringList(path);
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + admin + " | " + message;
        responses.add(entry);
        dataConfig.set(path, responses);
        dataConfig.set("tickets." + id + ".has_new_response", true);
        saveDataFile();
        // Notify player if online
        String playerName = dataConfig.getString("tickets." + id + ".player", "");
        Player target = Bukkit.getPlayer(playerName);
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.GREEN + admin + " responded to your ticket #" + id + ": " + ChatColor.WHITE + message);
        }
    }

    public void updateTicketField(int id, String field, String value) {
        dataConfig.set("tickets." + id + "." + field, value);
        saveDataFile();
    }

    public void resolveTicket(int id, String reason) {
        dataConfig.set("tickets." + id + ".status", "resolved");
        dataConfig.set("tickets." + id + ".resolution", reason);
        dataConfig.set("tickets." + id + ".has_new_response", true);
        saveDataFile();
        // Notify player if online
        String playerName = dataConfig.getString("tickets." + id + ".player", "");
        Player target = Bukkit.getPlayer(playerName);
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.GREEN + "Your ticket #" + id + " has been resolved: " + ChatColor.WHITE + reason);
        }
    }

    public void mutePlayer(UUID u, String playerName, String reason) {
        List<String> m = new ArrayList<>(dataConfig.getStringList("muted"));
        // Remove existing entry for this player
        m.removeIf(s -> s.startsWith(u.toString() + "|"));
        m.add(u.toString() + "|" + playerName + "|" + (reason != null ? reason : "No reason"));
        dataConfig.set("muted", m);
        saveDataFile();
    }

    public void unmutePlayer(UUID u) {
        List<String> m = new ArrayList<>(dataConfig.getStringList("muted"));
        m.removeIf(s -> s.startsWith(u.toString() + "|") || s.equals(u.toString()));
        dataConfig.set("muted", m);
        saveDataFile();
    }

    public void saveTemplate(String name, String content) {
        dataConfig.set("templates." + name, content);
        saveDataFile();
    }

    public String loadTemplate(String name) {
        return dataConfig.getString("templates." + name, "");
    }

    public void trackPlayerIP(UUID uuid, String playerName, String ip) {
        String key = "ips." + uuid;
        if (!dataConfig.contains(key)) dataConfig.set(key, new ArrayList<>());
        List<String> ips = dataConfig.getStringList(key);
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + ip;
        ips.add(entry);
        if (ips.size() > 50) ips.remove(0);
        dataConfig.set(key, ips);
        saveDataFile();
    }

    public void trackSession(UUID uuid, String playerName, boolean login) {
        String key = "sessions." + uuid;
        if (!dataConfig.contains(key)) dataConfig.set(key, new ArrayList<>());
        List<String> sessions = dataConfig.getStringList(key);
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        sessions.add((login ? "LOGIN" : "LOGOUT") + " " + ts);
        if (sessions.size() > 100) sessions.remove(0);
        dataConfig.set(key, sessions);
        saveDataFile();
    }

    public void addPlayerIp(UUID uuid, String ip) {
        this.trackPlayerIP(uuid, Bukkit.getOfflinePlayer(uuid).getName(), ip);
    }

    public boolean isMuted(UUID uuid) {
        if (!dataConfig.contains("muted")) return false;
        List<String> muted = dataConfig.getStringList("muted");
        return muted.stream().anyMatch(s -> s.startsWith(uuid.toString()));
    }

    private void fillGUIBorders(Inventory gui) {
        int size = gui.getSize();
        // top row
        for (int i = 0; i < 9 && i < size; i++) {
            if (gui.getItem(i) == null) gui.setItem(i, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        // bottom row (last 9 slots)
        int bottomStart = size - 9;
        for (int i = 0; i < 9; i++) {
            int slot = bottomStart + i;
            if (slot >= 0 && slot < size && gui.getItem(slot) == null) {
                gui.setItem(slot, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
            }
        }
        // left/right borders for middle rows
        int rows = size / 9;
        for (int r = 1; r < rows - 1; r++) {
            int left = r * 9;
            int right = r * 9 + 8;
            if (left < size && gui.getItem(left) == null)
                gui.setItem(left, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
            if (right < size && gui.getItem(right) == null)
                gui.setItem(right, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
    }

    private void fillGUIEmpty(Inventory gui) {
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
    }

    private void addItemToGrid(Inventory gui, ItemStack item) {
        int[] slotOffsets = new int[]{11,20,29,38};
        int slotIndex = 0;
        int rowIndex = 0;
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) != null || rowIndex >= slotOffsets.length) continue;
            int baseSlot = slotOffsets[rowIndex];
            int row = baseSlot / 9;
            int col = baseSlot % 9;
            int targetSlot = row * 9 + col + slotIndex;
            if (slotIndex >= 7) continue;
            gui.setItem(targetSlot, item);
            if (++slotIndex == 7) { slotIndex = 0; rowIndex++; }
            return;
        }
    }

    private void resetGridSlots() { this.gridSlotIndex = 0; this.gridRowIndex = 0; }

    private int getNextGridSlot() {
        if (this.gridRowIndex >= 4) return -1;
        int[] slotOffsets = new int[]{10,19,28,37};
        int baseSlot = slotOffsets[this.gridRowIndex];
        int slot = baseSlot + this.gridSlotIndex;
        this.gridSlotIndex++;
        if (this.gridSlotIndex >= 7) { this.gridSlotIndex = 0; this.gridRowIndex++; }
        return slot;
    }

    public void scheduleRestart(long minutes) {
        dataConfig.set("scheduled_restart", System.currentTimeMillis() + minutes * 60000L);
        saveDataFile();
    }

    private String getChunkKey(Location loc) {
        Chunk c = loc.getChunk();
        return c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ();
    }

    private String formatChunkCoordinates(String chunkKey) {
        // Format: "world:chunkX:chunkZ" -> "startX startY startZ - endX endY endZ"
        String[] parts = chunkKey.split(":");
        if (parts.length != 3) return chunkKey;
        
        try {
            int chunkX = Integer.parseInt(parts[1]);
            int chunkZ = Integer.parseInt(parts[2]);
            
            int startX = chunkX * 16;
            int startZ = chunkZ * 16;
            int endX = startX + 15;
            int endZ = startZ + 15;
            
            return startX + " 72 " + startZ + " - " + endX + " 72 " + endZ;
        } catch (NumberFormatException e) {
            return chunkKey;
        }
    }

    private void highlightChunkCorners(Location loc) {
        World world = loc.getWorld();
        Chunk chunk = loc.getChunk();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        int y = loc.getBlockY();
        
        // Four corners of the chunk
        Location[] corners = {
            new Location(world, chunkX * 16, y, chunkZ * 16),           // Northwest
            new Location(world, chunkX * 16 + 15, y, chunkZ * 16),      // Northeast
            new Location(world, chunkX * 16, y, chunkZ * 16 + 15),      // Southwest
            new Location(world, chunkX * 16 + 15, y, chunkZ * 16 + 15)  // Southeast
        };
        
        // Store original blocks and change to glowstone
        for (Location corner : corners) {
            String key = corner.getBlockX() + ":" + corner.getBlockY() + ":" + corner.getBlockZ() + ":" + world.getName();
            Material original = corner.getBlock().getType();
            chunksCornerBlocks.put(key, original);
            corner.getBlock().setType(Material.GLOWSTONE);
        }
        
        // Schedule revert after 4 seconds (80 ticks)
        Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
            for (Location corner : corners) {
                String key = corner.getBlockX() + ":" + corner.getBlockY() + ":" + corner.getBlockZ() + ":" + world.getName();
                Material original = chunksCornerBlocks.remove(key);
                if (original != null) {
                    corner.getBlock().setType(original);
                }
            }
        }, 80L);
    }

    private int getChunkLimit(UUID uuid) {
        int hours = (int) getPlaytimeHours(uuid);
        return 16 + hours;
    }

    private List<String> getClaimedChunks(UUID uuid) {
        return dataConfig.getStringList("claims." + uuid + ".claimed");
    }

    private void claimChunk(UUID uuid, String chunkKey) {
        String path = "claims." + uuid + ".claimed";
        List<String> claimed = dataConfig.getStringList(path);
        if (!claimed.contains(chunkKey)) {
            claimed.add(chunkKey);
            dataConfig.set(path, claimed);
            saveDataFile();
        }
    }

    private void unclaimChunk(UUID uuid, String chunkKey) {
        String path = "claims." + uuid + ".claimed";
        List<String> claimed = dataConfig.getStringList(path);
        claimed.remove(chunkKey);
        dataConfig.set(path, claimed);
        saveDataFile();
    }

    private void trustPlayer(UUID owner, String trustedName) {
        String path = "claims." + owner + ".trusted";
        List<String> trusted = dataConfig.getStringList(path);
        if (!trusted.contains(trustedName)) {
            trusted.add(trustedName);
            dataConfig.set(path, trusted);
            saveDataFile();
        }
    }

    private void untrustPlayer(UUID owner, String trustedName) {
        String path = "claims." + owner + ".trusted";
        List<String> trusted = dataConfig.getStringList(path);
        trusted.remove(trustedName);
        dataConfig.set(path, trusted);
        saveDataFile();
    }

    private boolean isChunkClaimed(String chunkKey) {
        for (String key : dataConfig.getKeys(true)) {
            if (key.contains("claims") && key.contains("claimed")) {
                List<String> chunks = dataConfig.getStringList(key);
                if (chunks.contains(chunkKey)) return true;
            }
        }
        return false;
    }

    private UUID getChunkOwner(String chunkKey) {
        for (String key : dataConfig.getKeys(true)) {
            if (key.contains("claims") && key.contains("claimed")) {
                List<String> chunks = dataConfig.getStringList(key);
                if (chunks.contains(chunkKey)) {
                    String[] parts = key.split("\\.");
                    if (parts.length > 1) {
                        try {
                            return UUID.fromString(parts[1]);
                        } catch (IllegalArgumentException e) {
                            return null;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isTrustedInChunk(Player p, String chunkKey) {
        UUID owner = getChunkOwner(chunkKey);
        if (owner == null) return true;
        if (p.getUniqueId().equals(owner)) return true;
        
        List<String> trusted = dataConfig.getStringList("claims." + owner + ".trusted");
        return trusted.contains(p.getName());
    }

    private void startPlaytimeTracker() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                String path = "playtime." + p.getUniqueId();
                long currentMinutes = dataConfig.getLong(path, 0);
                dataConfig.set(path, currentMinutes + 1);
            }
            saveDataFile();
        }, 1200L, 1200L); // Run every 60 seconds
    }

    private void startPunishmentChecker() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (String key : dataConfig.getKeys(true)) {
                if (key.startsWith("punishments.")) {
                    String uuidStr = key.replace("punishments.", "");
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        long expiry = dataConfig.getLong(key);
                        if (System.currentTimeMillis() > expiry) {
                            removePunishment(uuid);
                        }
                    } catch (IllegalArgumentException e) {
                        // Invalid UUID format
                    }
                }
            }
        }, 20L, 20L); // Run every 1 second
    }

    private void startAfkChecker() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!dataConfig.getBoolean("afk_autokick_enabled", true)) return;
            int timeoutMinutes = getAfkTimeoutMinutes();
            long now = System.currentTimeMillis();
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                Long lastAct = lastActivity.get(p.getUniqueId());
                if (lastAct == null) {
                    lastActivity.put(p.getUniqueId(), now);
                    continue;
                }
                long idleMs = now - lastAct;
                long idleMinutes = idleMs / 60000;
                if (idleMinutes >= timeoutMinutes) {
                    if (p.hasPermission("dmt.afk.exempt")) {
                        // Log only when they are near the threshold to avoid spamming console
                        if (idleMinutes == timeoutMinutes) {
                            getLogger().info("[AFK] Player " + p.getName() + " is AFK (" + idleMinutes + "m) but is exempt from kicking.");
                        }
                        continue;
                    }
                    p.kickPlayer(ChatColor.RED + "You were kicked for being AFK.\n"
                        + ChatColor.YELLOW + "You were idle for " + idleMinutes + " minute" + (idleMinutes != 1 ? "s" : "") + ".\n"
                        + ChatColor.GRAY + "The server auto-kick threshold is " + timeoutMinutes + " minute" + (timeoutMinutes != 1 ? "s" : "") + ".");
                    logAction("System", "afk_kick", p.getName() + " (idle " + idleMinutes + "m)");
                    getLogger().info("[AFK] Kicked " + p.getName() + " for being idle for " + idleMinutes + " minutes.");
                    lastActivity.remove(p.getUniqueId());
                }
            }
        }, 600L, 600L); // Run every 30 seconds
    }

    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) return -1;
        try {
            String lower = duration.toLowerCase();
            double value = 0;
            long multiplier = 0;
            if (lower.endsWith("s")) {
                value = Double.parseDouble(lower.substring(0, lower.length() - 1));
                multiplier = 1000; // seconds to milliseconds
            } else if (lower.endsWith("m")) {
                value = Double.parseDouble(lower.substring(0, lower.length() - 1));
                multiplier = 60 * 1000; // minutes to milliseconds
            } else if (lower.endsWith("hr")) {
                value = Double.parseDouble(lower.substring(0, lower.length() - 2));
                multiplier = 60 * 60 * 1000; // hours to milliseconds
            } else {
                return -1;
            }
            return (long) (value * multiplier);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // recursive delete for world folders
    private void deleteWorldFolder(File path) {
        if (path.isDirectory()) {
            for (File file : path.listFiles()) {
                deleteWorldFolder(file);
            }
        }
        path.delete();
    }

    // ========== CRATES SYSTEM ==========
    private void openCrateListGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_CRATE_LIST);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        if (dataConfig.contains("crates")) {
            for (String crateName : dataConfig.getConfigurationSection("crates").getKeys(false)) {
                String path = "crates." + crateName;
                String icon = dataConfig.getString(path + ".icon", "CHEST");
                Material mat = Material.CHEST;
                try { mat = Material.valueOf(icon.toUpperCase()); } catch (Exception ignored) {}
                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.LIGHT_PURPLE + crateName);
                int keyCost = dataConfig.getInt(path + ".key_cost", 0);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + (dataConfig.getString(path + ".description", "")));
                lore.add("");
                lore.add(keyCost > 0 ? ChatColor.GREEN + "Key Cost: " + keyCost + " XP Levels" : ChatColor.GREEN + "Free to open");
                List<String> rewards = dataConfig.getStringList(path + ".rewards");
                lore.add(ChatColor.YELLOW + "" + rewards.size() + " possible rewards");
                lore.add("");
                lore.add(ChatColor.AQUA + "Click to open!");
                meta.setLore(lore);
                item.setItemMeta(meta);
                gui.setItem(getNextGridSlot(), item);
            }
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    private void openCrateReward(Player p, String crateName) {
        String path = "crates." + crateName;
        if (!dataConfig.contains(path)) { p.sendMessage(ChatColor.RED + "Crate not found."); return; }

        int keyCost = dataConfig.getInt(path + ".key_cost", 0);
        if (keyCost > 0 && p.getLevel() < keyCost) {
            p.sendMessage(ChatColor.RED + "Not enough XP! Need " + keyCost + " levels.");
            return;
        }
        if (keyCost > 0) p.setLevel(p.getLevel() - keyCost);

        List<String> rewards = dataConfig.getStringList(path + ".rewards");
        if (rewards.isEmpty()) { p.sendMessage(ChatColor.RED + "This crate is empty!"); return; }

        // Weighted random: format is MATERIAL:amount:weight (weight is optional, default 100)
        int totalWeight = 0;
        List<int[]> weightRanges = new ArrayList<>();
        for (String r : rewards) {
            String[] parts = r.split(":");
            int weight = parts.length > 2 ? Integer.parseInt(parts[2]) : 100;
            weightRanges.add(new int[]{totalWeight, totalWeight + weight});
            totalWeight += weight;
        }
        int roll = new Random().nextInt(totalWeight);
        int winIndex = 0;
        for (int i = 0; i < weightRanges.size(); i++) {
            if (roll >= weightRanges.get(i)[0] && roll < weightRanges.get(i)[1]) { winIndex = i; break; }
        }
        String winReward = rewards.get(winIndex);
        String[] parts = winReward.split(":");
        Material mat = Material.valueOf(parts[0].toUpperCase());
        int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
        ItemStack reward = new ItemStack(mat, amount);
        HashMap<Integer, ItemStack> overflow = p.getInventory().addItem(reward);
        for (ItemStack drop : overflow.values()) p.getWorld().dropItemNaturally(p.getLocation(), drop);

        p.sendMessage(ChatColor.LIGHT_PURPLE + "✨ You opened " + ChatColor.GOLD + crateName + ChatColor.LIGHT_PURPLE + " and received " + ChatColor.WHITE + amount + "x " + mat.name().replace("_", " ") + ChatColor.LIGHT_PURPLE + "!");
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "✨ " + ChatColor.YELLOW + p.getName() + ChatColor.LIGHT_PURPLE + " opened a " + ChatColor.GOLD + crateName + ChatColor.LIGHT_PURPLE + " crate and got " + ChatColor.WHITE + amount + "x " + mat.name().replace("_", " ") + ChatColor.LIGHT_PURPLE + "!");
        logAction(p.getName(), "opened_crate", crateName + " -> " + amount + "x " + mat.name());
    }

    // ========== BOUNTY SYSTEM ==========
    private void openBountyListGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_BOUNTY_LIST);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        if (dataConfig.contains("bounties")) {
            for (String bountyId : dataConfig.getConfigurationSection("bounties").getKeys(false)) {
                String bPath = "bounties." + bountyId;
                String targetName = dataConfig.getString(bPath + ".targetName", "Unknown");
                String setterName = dataConfig.getString(bPath + ".setterName", "Unknown");
                int amount = dataConfig.getInt(bPath + ".amount", 0);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sMeta = (SkullMeta) head.getItemMeta();
                sMeta.setOwningPlayer(Bukkit.getOfflinePlayer(targetName));
                sMeta.setDisplayName(ChatColor.RED + "☠ " + targetName);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GOLD + "Reward: " + amount + " XP Levels");
                lore.add(ChatColor.GRAY + "Set by: " + setterName);
                lore.add("");
                lore.add(ChatColor.YELLOW + "Kill this player to collect!");
                sMeta.setLore(lore);
                head.setItemMeta(sMeta);
                gui.setItem(getNextGridSlot(), head);
            }
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        // --- PVP STATS TRACKING ---
        UUID killerUUID = killer.getUniqueId();
        UUID victimUUID = victim.getUniqueId();
        dataConfig.set("pvpstats." + killerUUID + ".kills", dataConfig.getInt("pvpstats." + killerUUID + ".kills", 0) + 1);
        int killerStreak = dataConfig.getInt("pvpstats." + killerUUID + ".streak", 0) + 1;
        dataConfig.set("pvpstats." + killerUUID + ".streak", killerStreak);
        int bestStreak = dataConfig.getInt("pvpstats." + killerUUID + ".best_streak", 0);
        if (killerStreak > bestStreak) dataConfig.set("pvpstats." + killerUUID + ".best_streak", killerStreak);
        dataConfig.set("pvpstats." + victimUUID + ".deaths", dataConfig.getInt("pvpstats." + victimUUID + ".deaths", 0) + 1);
        dataConfig.set("pvpstats." + victimUUID + ".streak", 0);

        // Achievement check: kill milestone
        int totalKills = dataConfig.getInt("pvpstats." + killerUUID + ".kills", 0);
        checkAchievement(killer, "kills_10", totalKills >= 10);
        checkAchievement(killer, "kills_100", totalKills >= 100);
        checkAchievement(killer, "streak_5", killerStreak >= 5);

        // --- DUEL SYSTEM ---
        if (activeDuels.containsKey(killerUUID) && activeDuels.get(killerUUID).equals(victimUUID)) {
            int wager = duelWagers.getOrDefault(killerUUID, duelWagers.getOrDefault(victimUUID, 0));
            activeDuels.remove(killerUUID);
            activeDuels.remove(victimUUID);
            // Return to pre-duel locations
            Location killerReturn = duelReturnLocations.remove(killerUUID);
            Location victimReturn = duelReturnLocations.remove(victimUUID);
            if (wager > 0) {
                killer.setLevel(killer.getLevel() + wager);
                victim.setLevel(Math.max(0, victim.getLevel() - wager));
                killer.sendMessage(ChatColor.GREEN + "⚔ You won the duel! +" + wager + " XP levels!");
                victim.sendMessage(ChatColor.RED + "⚔ You lost the duel! -" + wager + " XP levels.");
            } else {
                killer.sendMessage(ChatColor.GREEN + "⚔ You won the duel against " + victim.getName() + "!");
                victim.sendMessage(ChatColor.RED + "⚔ You lost the duel against " + killer.getName() + ".");
            }
            duelWagers.remove(killerUUID);
            duelWagers.remove(victimUUID);
            // Teleport back after delay
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (killer.isOnline() && killerReturn != null) killer.teleport(killerReturn);
            }, 60L);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (victim.isOnline() && victimReturn != null) victim.teleport(victimReturn);
            }, 60L);
            logAction(killer.getName(), "duel_won", "vs " + victim.getName() + (wager > 0 ? " wager:" + wager : ""));
        }

        // Check for bounties on the victim
        if (dataConfig.contains("bounties")) {
            List<String> toRemove = new ArrayList<>();
            int totalReward = 0;
            for (String bountyId : dataConfig.getConfigurationSection("bounties").getKeys(false)) {
                String target = dataConfig.getString("bounties." + bountyId + ".target", "");
                if (target.equals(victim.getUniqueId().toString())) {
                    totalReward += dataConfig.getInt("bounties." + bountyId + ".amount", 0);
                    toRemove.add(bountyId);
                }
            }
            if (totalReward > 0) {
                for (String id : toRemove) dataConfig.set("bounties." + id, null);
                killer.setLevel(killer.getLevel() + totalReward);
                Bukkit.broadcastMessage(ChatColor.RED + "☠ BOUNTY CLAIMED! " + ChatColor.YELLOW + killer.getName() + ChatColor.RED + " collected " + ChatColor.GOLD + totalReward + " XP levels" + ChatColor.RED + " for killing " + ChatColor.YELLOW + victim.getName() + ChatColor.RED + "!");
                logAction(killer.getName(), "claimed_bounty", victim.getName() + " for " + totalReward + " XP");
            }
        }
        saveDataFile();
    }

    // ========== PLAYER SHOPS ==========
    private void openShopListGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_SHOP_LIST);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        if (dataConfig.contains("shops")) {
            for (String shopId : dataConfig.getConfigurationSection("shops").getKeys(false)) {
                String sPath = "shops." + shopId;
                String owner = dataConfig.getString(sPath + ".ownerName", "Unknown");
                String item = dataConfig.getString(sPath + ".item", "DIRT");
                int amount = dataConfig.getInt(sPath + ".amount", 1);
                int price = dataConfig.getInt(sPath + ".price", 0);

                Material mat = Material.DIRT;
                try { mat = Material.valueOf(item.toUpperCase()); } catch (Exception ignored) {}
                ItemStack display = new ItemStack(mat, amount);
                ItemMeta meta = display.getItemMeta();
                meta.setDisplayName(ChatColor.GREEN + "" + amount + "x " + mat.name().replace("_", " "));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Seller: " + owner);
                lore.add(ChatColor.GOLD + "Price: " + price + " XP Levels");
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click to buy!");
                meta.setLore(lore);
                display.setItemMeta(meta);
                gui.setItem(getNextGridSlot(), display);
            }
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    // ========== QUEST SYSTEM ==========
    private void openQuestListGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_QUEST_LIST);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        if (dataConfig.contains("quests")) {
            for (String questId : dataConfig.getConfigurationSection("quests").getKeys(false)) {
                String qPath = "quests." + questId;
                if (!dataConfig.getBoolean(qPath + ".active", true)) continue;
                String name = dataConfig.getString(qPath + ".name", questId);
                String desc = dataConfig.getString(qPath + ".description", "");
                String type = dataConfig.getString(qPath + ".type", "break_blocks");
                int goal = dataConfig.getInt(qPath + ".goal", 1);
                int reward = dataConfig.getInt(qPath + ".reward", 0);
                String rewardKit = dataConfig.getString(qPath + ".reward_kit", "");

                // Get player progress
                int progress = dataConfig.getInt("quest_progress." + p.getUniqueId() + "." + questId, 0);
                boolean completed = dataConfig.getBoolean("quest_completed." + p.getUniqueId() + "." + questId, false);

                Material mat = completed ? Material.LIME_DYE : (progress > 0 ? Material.YELLOW_DYE : Material.GRAY_DYE);
                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName((completed ? ChatColor.GREEN + "✅ " : ChatColor.GOLD) + name);
                List<String> lore = new ArrayList<>();
                if (!desc.isEmpty()) lore.add(ChatColor.GRAY + desc);
                lore.add("");
                lore.add(ChatColor.YELLOW + "Type: " + type.replace("_", " "));
                lore.add(ChatColor.AQUA + "Progress: " + Math.min(progress, goal) + "/" + goal);
                if (reward > 0) lore.add(ChatColor.GREEN + "Reward: " + reward + " XP Levels");
                if (!rewardKit.isEmpty()) lore.add(ChatColor.GREEN + "Reward Kit: " + rewardKit);
                if (completed) lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "COMPLETED!");
                else if (progress >= goal) {
                    lore.add("");
                    lore.add(ChatColor.GREEN + "Click to claim reward!");
                }
                meta.setLore(lore);
                item.setItemMeta(meta);
                gui.setItem(getNextGridSlot(), item);
            }
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    @EventHandler
    public void onEntityKillForQuest(org.bukkit.event.entity.EntityDeathEvent e) {
        if (e.getEntity().getKiller() != null) {
            Player killer = e.getEntity().getKiller();
            trackQuestProgress(killer, "kill_mobs", 1);
            trackQuestProgress(killer, "kill_" + e.getEntity().getType().name().toLowerCase(), 1);
        }
    }

    private void trackQuestProgress(Player p, String type, int amount) {
        if (!dataConfig.contains("quests")) return;
        for (String questId : dataConfig.getConfigurationSection("quests").getKeys(false)) {
            if (!dataConfig.getBoolean("quests." + questId + ".active", true)) continue;
            String questType = dataConfig.getString("quests." + questId + ".type", "");
            if (!questType.equalsIgnoreCase(type)) continue;
            if (dataConfig.getBoolean("quest_completed." + p.getUniqueId() + "." + questId, false)) continue;
            int goal = dataConfig.getInt("quests." + questId + ".goal", 1);
            int current = dataConfig.getInt("quest_progress." + p.getUniqueId() + "." + questId, 0);
            current += amount;
            dataConfig.set("quest_progress." + p.getUniqueId() + "." + questId, current);
            if (current >= goal) {
                p.sendMessage(ChatColor.GOLD + "🎯 Quest " + ChatColor.YELLOW + dataConfig.getString("quests." + questId + ".name", questId) + ChatColor.GOLD + " complete! Use /quest to claim your reward.");
            }
            saveDataFile();
        }
    }

    private void claimQuestReward(Player p, String questName) {
        if (!dataConfig.contains("quests")) return;
        for (String questId : dataConfig.getConfigurationSection("quests").getKeys(false)) {
            String name = dataConfig.getString("quests." + questId + ".name", questId);
            if (!name.equals(questName)) continue;
            if (dataConfig.getBoolean("quest_completed." + p.getUniqueId() + "." + questId, false)) {
                p.sendMessage(ChatColor.RED + "Already claimed.");
                return;
            }
            int goal = dataConfig.getInt("quests." + questId + ".goal", 1);
            int progress = dataConfig.getInt("quest_progress." + p.getUniqueId() + "." + questId, 0);
            if (progress < goal) { p.sendMessage(ChatColor.RED + "Not completed yet."); return; }

            dataConfig.set("quest_completed." + p.getUniqueId() + "." + questId, true);
            int reward = dataConfig.getInt("quests." + questId + ".reward", 0);
            if (reward > 0) p.setLevel(p.getLevel() + reward);
            String rewardKit = dataConfig.getString("quests." + questId + ".reward_kit", "");
            if (!rewardKit.isEmpty()) claimKit(p, rewardKit);
            saveDataFile();
            p.sendMessage(ChatColor.GREEN + "✅ Quest reward claimed!");
            logAction(p.getName(), "claimed_quest", questName);
            return;
        }
    }

    // ========== ACTIVE POLLS DISPLAY ==========
    private void openPollList(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_POLL_LIST);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        if (dataConfig.contains("polls")) {
            for (String pollId : dataConfig.getConfigurationSection("polls").getKeys(false)) {
                if (!dataConfig.getBoolean("polls." + pollId + ".active", false)) continue;
                String question = dataConfig.getString("polls." + pollId + ".question", "");
                List<String> options = dataConfig.getStringList("polls." + pollId + ".options");
                List<String> voters = dataConfig.getStringList("polls." + pollId + ".voters");
                boolean hasVoted = voters.contains(p.getUniqueId().toString());
                ItemStack item = new ItemStack(Material.PAPER);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName((hasVoted ? ChatColor.GRAY : ChatColor.YELLOW) + question);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.DARK_GRAY + "ID: " + pollId);
                lore.add("");
                int totalVotes = 0;
                for (int i = 0; i < options.size(); i++) totalVotes += dataConfig.getInt("polls." + pollId + ".votes." + (i + 1), 0);
                for (int i = 0; i < options.size(); i++) {
                    int votes = dataConfig.getInt("polls." + pollId + ".votes." + (i + 1), 0);
                    int pct = totalVotes > 0 ? Math.round((float) votes / totalVotes * 100) : 0;
                    lore.add(ChatColor.AQUA + "  " + (i + 1) + ". " + options.get(i) + ChatColor.GRAY + " (" + votes + " votes, " + pct + "%)");
                }
                lore.add("");
                lore.add(hasVoted ? ChatColor.RED + "Already voted" : ChatColor.GREEN + "Click to vote!");
                meta.setLore(lore);
                item.setItemMeta(meta);
                int slot = getNextGridSlot();
                if (slot != -1) gui.setItem(slot, item);
            }
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    private void openPollVote(Player p, String pollId) {
        if (!dataConfig.contains("polls." + pollId)) { p.sendMessage(ChatColor.RED + "Poll not found."); return; }
        if (!dataConfig.getBoolean("polls." + pollId + ".active", false)) { p.sendMessage(ChatColor.RED + "This poll has ended."); return; }
        List<String> voters = dataConfig.getStringList("polls." + pollId + ".voters");
        if (voters.contains(p.getUniqueId().toString())) { p.sendMessage(ChatColor.RED + "You already voted on this poll."); return; }
        String question = dataConfig.getString("polls." + pollId + ".question", "");
        List<String> options = dataConfig.getStringList("polls." + pollId + ".options");
        int size = Math.max(9, (int) Math.ceil((options.size() + 1) / 9.0) * 9);
        if (size > 54) size = 54;
        Inventory gui = Bukkit.createInventory(null, size, GUI_POLL_VOTE + question);
        for (int i = 0; i < options.size(); i++) {
            ItemStack item = new ItemStack(Material.LIME_CONCRETE);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "" + (i + 1) + ". " + options.get(i));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + "ID: " + pollId);
            lore.add(ChatColor.DARK_GRAY + "Option: " + (i + 1));
            int votes = dataConfig.getInt("polls." + pollId + ".votes." + (i + 1), 0);
            lore.add("");
            lore.add(ChatColor.GRAY + "Current votes: " + ChatColor.YELLOW + votes);
            lore.add(ChatColor.AQUA + "Click to vote!");
            meta.setLore(lore);
            item.setItemMeta(meta);
            gui.setItem(i, item);
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(size - 1, back);
        p.openInventory(gui);
    }

    private void showActivePolls(Player p) {
        boolean found = false;
        if (dataConfig.contains("polls")) {
            for (String pollId : dataConfig.getConfigurationSection("polls").getKeys(false)) {
                if (!dataConfig.getBoolean("polls." + pollId + ".active", false)) continue;
                found = true;
                String question = dataConfig.getString("polls." + pollId + ".question", "");
                List<String> options = dataConfig.getStringList("polls." + pollId + ".options");
                p.sendMessage(ChatColor.GOLD + "--- Poll #" + pollId + " ---");
                p.sendMessage(ChatColor.YELLOW + question);
                for (int i = 0; i < options.size(); i++) {
                    int votes = dataConfig.getInt("polls." + pollId + ".votes." + (i + 1), 0);
                    p.sendMessage(ChatColor.AQUA + "  " + (i + 1) + ". " + options.get(i) + ChatColor.GRAY + " (" + votes + " votes)");
                }
                p.sendMessage(ChatColor.GREEN + "Vote: /vote " + pollId + " <option#>");
            }
        }
        if (!found) p.sendMessage(ChatColor.YELLOW + "No active polls right now.");
    }

    // ========== AUTO-MOD HELPERS ==========
    public void loadChatFilter() {
        chatFilterWords.clear();
        List<String> words = dataConfig.getStringList("automod.filter_words");
        chatFilterWords.addAll(words);
    }

    // ========== PLAYTIME REWARDS ==========
    private void startPlaytimeRewardsChecker() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!dataConfig.contains("playtime_rewards")) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                long minutes = dataConfig.getLong("playtime." + p.getUniqueId(), 0);
                if (dataConfig.contains("playtime_rewards")) {
                    for (String rewardId : dataConfig.getConfigurationSection("playtime_rewards").getKeys(false)) {
                        int reqMinutes = dataConfig.getInt("playtime_rewards." + rewardId + ".minutes", 0);
                        if (minutes >= reqMinutes) {
                            String claimedKey = "playtime_claimed." + p.getUniqueId() + "." + rewardId;
                            if (!dataConfig.getBoolean(claimedKey, false)) {
                                dataConfig.set(claimedKey, true);
                                int xpReward = dataConfig.getInt("playtime_rewards." + rewardId + ".xp", 0);
                                String kit = dataConfig.getString("playtime_rewards." + rewardId + ".kit", "");
                                if (xpReward > 0) p.setLevel(p.getLevel() + xpReward);
                                if (!kit.isEmpty()) claimKit(p, kit);
                                String name = dataConfig.getString("playtime_rewards." + rewardId + ".name", "Playtime Reward");
                                p.sendMessage(ChatColor.GOLD + "🏆 Playtime Reward Unlocked: " + ChatColor.YELLOW + name + ChatColor.GOLD + "!");
                                logAction("System", "playtime_reward", p.getName() + " -> " + name);
                                saveDataFile();
                            }
                        }
                    }
                }
            }
        }, 6000L, 6000L); // Every 5 minutes
    }

    // ========== CUSTOM ENCHANTMENTS ==========
    @EventHandler
    public void onBlockBreakEnchant(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || !held.hasItemMeta() || !held.getItemMeta().hasLore()) return;

        List<String> lore = held.getItemMeta().getLore();
        for (String line : lore) {
            String stripped = ChatColor.stripColor(line).trim();
            // Timber enchant - break logs in column
            if (stripped.equalsIgnoreCase("Timber") && e.getBlock().getType().name().contains("LOG")) {
                Location loc = e.getBlock().getLocation();
                for (int y = 1; y <= 20; y++) {
                    Location above = loc.clone().add(0, y, 0);
                    if (above.getBlock().getType().name().contains("LOG")) {
                        above.getBlock().breakNaturally(held);
                    } else break;
                }
            }
            // Vein Miner enchant - break connected ores
            if (stripped.equalsIgnoreCase("Vein Miner") && e.getBlock().getType().name().contains("ORE")) {
                Material oreType = e.getBlock().getType();
                Set<Location> toBreak = new HashSet<>();
                findConnectedOres(e.getBlock().getLocation(), oreType, toBreak, 16);
                for (Location bl : toBreak) {
                    if (!bl.equals(e.getBlock().getLocation())) bl.getBlock().breakNaturally(held);
                }
            }
            // Smelting Touch - auto-smelt
            if (stripped.equalsIgnoreCase("Smelting Touch")) {
                Material blockType = e.getBlock().getType();
                Material smelted = getSmeltedResult(blockType);
                if (smelted != null) {
                    e.setDropItems(false);
                    e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), new ItemStack(smelted, 1));
                }
            }
            // Telepathy - drops go to inventory
            if (stripped.equalsIgnoreCase("Telepathy")) {
                e.setDropItems(false);
                for (ItemStack drop : e.getBlock().getDrops(held)) {
                    HashMap<Integer, ItemStack> overflow = p.getInventory().addItem(drop);
                    for (ItemStack o : overflow.values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
                }
            }
        }
    }

    private void findConnectedOres(Location loc, Material oreType, Set<Location> found, int max) {
        if (found.size() >= max) return;
        if (found.contains(loc)) return;
        if (loc.getBlock().getType() != oreType) return;
        found.add(loc);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0)
                        findConnectedOres(loc.clone().add(dx, dy, dz), oreType, found, max);
    }

    private Material getSmeltedResult(Material block) {
        switch (block) {
            case IRON_ORE: case DEEPSLATE_IRON_ORE: return Material.IRON_INGOT;
            case GOLD_ORE: case DEEPSLATE_GOLD_ORE: return Material.GOLD_INGOT;
            case COPPER_ORE: case DEEPSLATE_COPPER_ORE: return Material.COPPER_INGOT;
            case ANCIENT_DEBRIS: return Material.NETHERITE_SCRAP;
            case SAND: return Material.GLASS;
            case COBBLESTONE: return Material.STONE;
            default: return null;
        }
    }

    // Apply custom enchantment to item
    public boolean applyCustomEnchant(ItemStack item, String enchantName) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        // Check if already has it
        for (String line : lore) {
            if (ChatColor.stripColor(line).trim().equalsIgnoreCase(enchantName)) return false;
        }
        lore.add(ChatColor.LIGHT_PURPLE + enchantName);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return true;
    }

    // ========== MOTD HANDLER ==========
    @EventHandler
    public void onServerPing(ServerListPingEvent e) {
        String line1 = dataConfig.getString("motd.line1", "");
        String line2 = dataConfig.getString("motd.line2", "");
        if (!line1.isEmpty() || !line2.isEmpty()) {
            String motd = ChatColor.translateAlternateColorCodes('&', line1);
            if (!line2.isEmpty()) motd += "\n" + ChatColor.translateAlternateColorCodes('&', line2);
            e.setMotd(motd);
        }
        int maxPlayers = dataConfig.getInt("motd.maxPlayers", 0);
        if (maxPlayers > 0) e.setMaxPlayers(maxPlayers);
    }

    // ========== AUCTION HOUSE GUI ==========
    private void openAuctionHouseGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_AUCTION_HOUSE);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        if (dataConfig.contains("auctions")) {
            for (String aId : dataConfig.getConfigurationSection("auctions").getKeys(false)) {
                String aPath = "auctions." + aId;
                long endTime = dataConfig.getLong(aPath + ".endTime", 0);
                if (System.currentTimeMillis() > endTime) continue; // expired
                String itemName = dataConfig.getString(aPath + ".item", "DIRT");
                int amount = dataConfig.getInt(aPath + ".amount", 1);
                String seller = dataConfig.getString(aPath + ".sellerName", "Unknown");
                int currentBid = dataConfig.getInt(aPath + ".currentBid", 0);
                String highBidder = dataConfig.getString(aPath + ".highBidderName", "None");
                long remaining = (endTime - System.currentTimeMillis()) / 60000;
                Material mat;
                try { mat = Material.valueOf(itemName.toUpperCase()); } catch (Exception e) { mat = Material.DIRT; }
                ItemStack display = new ItemStack(mat, amount);
                ItemMeta meta = display.getItemMeta();
                meta.setDisplayName(ChatColor.GOLD + "" + amount + "x " + mat.name().replace("_", " "));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Seller: " + seller);
                lore.add(ChatColor.YELLOW + "Current Bid: " + currentBid + " XP");
                lore.add(ChatColor.AQUA + "Highest: " + highBidder);
                lore.add(ChatColor.GREEN + "Time Left: " + remaining + " min");
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click to bid!");
                lore.add(ChatColor.DARK_GRAY + "ID:" + aId);
                meta.setLore(lore);
                display.setItemMeta(meta);
                gui.setItem(getNextGridSlot(), display);
            }
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    // ========== PLAYER WARP LIST GUI ==========
    private void openPwarpListGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PWARP_LIST);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        if (dataConfig.contains("pwarps")) {
            for (String wId : dataConfig.getConfigurationSection("pwarps").getKeys(false)) {
                String wPath = "pwarps." + wId;
                String name = dataConfig.getString(wPath + ".name", wId);
                String owner = dataConfig.getString(wPath + ".ownerName", "Unknown");
                int visits = dataConfig.getInt(wPath + ".visits", 0);
                ItemStack display = new ItemStack(Material.ENDER_PEARL);
                ItemMeta meta = display.getItemMeta();
                meta.setDisplayName(ChatColor.GREEN + name);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Owner: " + owner);
                lore.add(ChatColor.YELLOW + "Visits: " + visits);
                lore.add("");
                lore.add(ChatColor.AQUA + "Click to warp!");
                lore.add(ChatColor.DARK_GRAY + "ID:" + wId);
                meta.setLore(lore);
                display.setItemMeta(meta);
                gui.setItem(getNextGridSlot(), display);
            }
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    // ========== ACHIEVEMENTS GUI ==========
    private void openAchievementsGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_ACHIEVEMENTS);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();
        if (dataConfig.contains("achievement_defs")) {
            for (String key : dataConfig.getConfigurationSection("achievement_defs").getKeys(false)) {
                String aPath = "achievement_defs." + key;
                String name = dataConfig.getString(aPath + ".name", key);
                String desc = dataConfig.getString(aPath + ".description", "");
                String title = dataConfig.getString(aPath + ".title", "");
                boolean unlocked = dataConfig.getBoolean("achievements." + p.getUniqueId() + "." + key, false);
                ItemStack display = new ItemStack(unlocked ? Material.DIAMOND : Material.COAL);
                ItemMeta meta = display.getItemMeta();
                meta.setDisplayName((unlocked ? ChatColor.GREEN + "✔ " : ChatColor.RED + "✘ ") + ChatColor.GOLD + name);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + desc);
                if (!title.isEmpty()) lore.add(ChatColor.LIGHT_PURPLE + "Title: " + title);
                lore.add(unlocked ? ChatColor.GREEN + "UNLOCKED" : ChatColor.RED + "LOCKED");
                meta.setLore(lore);
                display.setItemMeta(meta);
                gui.setItem(getNextGridSlot(), display);
            }
        }
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    // ========== ACHIEVEMENT CHECK HELPER ==========
    private void checkAchievement(Player p, String achievementId, boolean condition) {
        if (!condition) return;
        if (dataConfig.getBoolean("achievements." + p.getUniqueId() + "." + achievementId, false)) return;
        if (!dataConfig.contains("achievement_defs." + achievementId)) return;
        dataConfig.set("achievements." + p.getUniqueId() + "." + achievementId, true);
        saveDataFile();
        String name = dataConfig.getString("achievement_defs." + achievementId + ".name", achievementId);
        String title = dataConfig.getString("achievement_defs." + achievementId + ".title", "");
        int xp = dataConfig.getInt("achievement_defs." + achievementId + ".xp_reward", 0);
        p.sendMessage(ChatColor.GOLD + "🏆 Achievement Unlocked: " + ChatColor.GREEN + name + (xp > 0 ? ChatColor.YELLOW + " (+" + xp + " XP)" : ""));
        if (xp > 0) p.giveExp(xp);
        if (!title.isEmpty()) {
            dataConfig.set("chat_tags." + p.getUniqueId(), title);
            saveDataFile();
            p.sendMessage(ChatColor.LIGHT_PURPLE + "New title unlocked: " + ChatColor.translateAlternateColorCodes('&', title));
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "🏆 " + ChatColor.YELLOW + p.getName() + ChatColor.GOLD + " unlocked: " + ChatColor.GREEN + name);
        logAction(p.getName(), "achievement_unlocked", name);
    }

    // ========== SCHEDULED ANNOUNCEMENTS ==========
    private void startScheduledAnnouncements() {
        int intervalTicks = dataConfig.getInt("announcements.interval_minutes", 5) * 20 * 60;
        if (intervalTicks <= 0) intervalTicks = 6000;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!dataConfig.getBoolean("announcements.enabled", false)) return;
            List<String> messages = dataConfig.getStringList("announcements.messages");
            if (messages.isEmpty()) return;
            int index = dataConfig.getInt("announcements.current_index", 0);
            if (index >= messages.size()) index = 0;
            String msg = ChatColor.translateAlternateColorCodes('&', messages.get(index));
            String prefix = ChatColor.translateAlternateColorCodes('&', dataConfig.getString("announcements.prefix", "&6[&eAnnouncement&6]&r "));
            Bukkit.broadcastMessage(prefix + msg);
            dataConfig.set("announcements.current_index", index + 1);
        }, intervalTicks, intervalTicks);

        // One-time scheduled announcements checker (every 30 seconds = 600 ticks)
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            List<Map<?, ?>> raw = (List<Map<?, ?>>) dataConfig.getList("announcements.scheduled", new ArrayList<>());
            if (raw.isEmpty()) return;
            boolean changed = false;
            long now = System.currentTimeMillis();
            String prefix = ChatColor.translateAlternateColorCodes('&', dataConfig.getString("announcements.prefix", "&6[&eAnnouncement&6]&r "));
            for (Map<?, ?> entry : raw) {
                Object sentObj = entry.get("sent");
                boolean sent = sentObj instanceof Boolean && (Boolean) sentObj;
                if (sent) continue;
                String timeStr = String.valueOf(entry.get("time"));
                try {
                    // Parse ISO local datetime (yyyy-MM-ddTHH:mm)
                    long targetMs = java.time.LocalDateTime.parse(timeStr).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (now >= targetMs) {
                        String message = ChatColor.translateAlternateColorCodes('&', String.valueOf(entry.get("message")));
                        Bukkit.broadcastMessage(prefix + message);
                        ((Map) entry).put("sent", true);
                        changed = true;
                    }
                } catch (Exception ignored) {}
            }
            if (changed) {
                dataConfig.set("announcements.scheduled", raw);
                saveDataFile();
            }
        }, 600L, 600L);
    }

    // ========== EVENT EFFECTS ==========
    private static final Set<org.bukkit.block.Biome> HOT_BIOMES = Set.of(
        org.bukkit.block.Biome.DESERT,
        org.bukkit.block.Biome.BADLANDS,
        org.bukkit.block.Biome.ERODED_BADLANDS,
        org.bukkit.block.Biome.WOODED_BADLANDS,
        org.bukkit.block.Biome.SAVANNA,
        org.bukkit.block.Biome.SAVANNA_PLATEAU,
        org.bukkit.block.Biome.WINDSWEPT_SAVANNA,
        org.bukkit.block.Biome.WARM_OCEAN,
        org.bukkit.block.Biome.LUKEWARM_OCEAN,
        org.bukkit.block.Biome.DEEP_LUKEWARM_OCEAN,
        org.bukkit.block.Biome.JUNGLE,
        org.bukkit.block.Biome.BAMBOO_JUNGLE,
        org.bukkit.block.Biome.SPARSE_JUNGLE,
        org.bukkit.block.Biome.MANGROVE_SWAMP
    );

    private static final Set<org.bukkit.block.Biome> COLD_BIOMES = Set.of(
        org.bukkit.block.Biome.SNOWY_PLAINS,
        org.bukkit.block.Biome.SNOWY_TAIGA,
        org.bukkit.block.Biome.SNOWY_BEACH,
        org.bukkit.block.Biome.SNOWY_SLOPES,
        org.bukkit.block.Biome.FROZEN_OCEAN,
        org.bukkit.block.Biome.DEEP_FROZEN_OCEAN,
        org.bukkit.block.Biome.FROZEN_RIVER,
        org.bukkit.block.Biome.FROZEN_PEAKS,
        org.bukkit.block.Biome.JAGGED_PEAKS,
        org.bukkit.block.Biome.ICE_SPIKES,
        org.bukkit.block.Biome.GROVE
    );

    // --- Christmas: Snowflakes falling (skip hot biomes) ---
    public void startChristmasSnow() {
        if (christmasSnowTaskId != -1) return;
        christmasSnowTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                org.bukkit.block.Biome biome = p.getLocation().getBlock().getBiome();
                if (HOT_BIOMES.contains(biome)) continue;
                Location loc = p.getLocation();
                for (int i = 0; i < 15; i++) {
                    double offsetX = (Math.random() - 0.5) * 30;
                    double offsetY = 5 + Math.random() * 15;
                    double offsetZ = (Math.random() - 0.5) * 30;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    p.spawnParticle(org.bukkit.Particle.SNOWFLAKE, particleLoc, 1, 0.5, 2.0, 0.5, 0.02);
                }
            }
        }, 0L, 5L);
        getLogger().info("Christmas snow effect started!");
    }

    public void stopChristmasSnow() {
        if (christmasSnowTaskId != -1) {
            Bukkit.getScheduler().cancelTask(christmasSnowTaskId);
            christmasSnowTaskId = -1;
            getLogger().info("Christmas snow effect stopped!");
        }
    }

    // --- Halloween: Smoke, witch sparkles, soul fire (all biomes) ---
    public void startHalloweenEffect() {
        if (halloweenTaskId != -1) return;
        halloweenTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location loc = p.getLocation();
                for (int i = 0; i < 8; i++) {
                    double offsetX = (Math.random() - 0.5) * 25;
                    double offsetY = Math.random() * 8;
                    double offsetZ = (Math.random() - 0.5) * 25;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    // Dark smoke rising from the ground
                    p.spawnParticle(org.bukkit.Particle.SMOKE, particleLoc, 1, 0.3, 0.5, 0.3, 0.01);
                }
                for (int i = 0; i < 5; i++) {
                    double offsetX = (Math.random() - 0.5) * 20;
                    double offsetY = 1 + Math.random() * 5;
                    double offsetZ = (Math.random() - 0.5) * 20;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    // Witch purple sparkles
                    p.spawnParticle(org.bukkit.Particle.WITCH, particleLoc, 1, 0.2, 0.3, 0.2, 0.01);
                }
                for (int i = 0; i < 3; i++) {
                    double offsetX = (Math.random() - 0.5) * 15;
                    double offsetY = Math.random() * 3;
                    double offsetZ = (Math.random() - 0.5) * 15;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    // Eerie soul flames flickering near ground
                    p.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME, particleLoc, 1, 0.1, 0.2, 0.1, 0.005);
                }
            }
        }, 0L, 8L);
        getLogger().info("Halloween effect started!");
    }

    public void stopHalloweenEffect() {
        if (halloweenTaskId != -1) {
            Bukkit.getScheduler().cancelTask(halloweenTaskId);
            halloweenTaskId = -1;
            getLogger().info("Halloween effect stopped!");
        }
    }

    // --- New Year: Firework sparks shooting upward ---
    public void startNewYearEffect() {
        if (newYearTaskId != -1) return;
        newYearTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location loc = p.getLocation();
                
                // Spawn actual fireworks
                if (Math.random() < 0.4) {
                    double offsetX = (Math.random() - 0.5) * 30;
                    double offsetZ = (Math.random() - 0.5) * 30;
                    Location fwLoc = loc.clone().add(offsetX, 0, offsetZ);
                    
                    try {
                        org.bukkit.entity.Firework fw = (org.bukkit.entity.Firework) fwLoc.getWorld().spawnEntity(fwLoc, org.bukkit.entity.EntityType.FIREWORK_ROCKET);
                        FireworkMeta fwm = fw.getFireworkMeta();
                        
                        Color[] colors = {Color.RED, Color.BLUE, Color.LIME, Color.YELLOW, Color.ORANGE, Color.PURPLE, Color.WHITE, Color.AQUA};
                        Color c1 = colors[new Random().nextInt(colors.length)];
                        Color c2 = colors[new Random().nextInt(colors.length)];
                        
                        FireworkEffect.Type[] types = {FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.BURST, FireworkEffect.Type.STAR, FireworkEffect.Type.CREEPER};
                        FireworkEffect.Type type = types[new Random().nextInt(types.length)];
                        
                        fwm.addEffect(FireworkEffect.builder().flicker(Math.random() < 0.5).trail(Math.random() < 0.5).with(type).withColor(c1).withFade(c2).build());
                        fwm.setPower(1 + new Random().nextInt(2));
                        fw.setFireworkMeta(fwm);
                    } catch (Exception ignored) {}
                }

                for (int i = 0; i < 5; i++) {
                    double offsetX = (Math.random() - 0.5) * 30;
                    double offsetY = 3 + Math.random() * 20;
                    double offsetZ = (Math.random() - 0.5) * 30;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    // Firework sparks bursting in the sky
                    p.spawnParticle(org.bukkit.Particle.FIREWORK, particleLoc, 1, 1.5, 1.0, 1.5, 0.08);
                }
            }
        }, 0L, 20L);
        getLogger().info("New Year effect started!");
    }

    public void stopNewYearEffect() {
        if (newYearTaskId != -1) {
            Bukkit.getScheduler().cancelTask(newYearTaskId);
            newYearTaskId = -1;
            getLogger().info("New Year effect stopped!");
        }
    }

    // --- Valentine: Floating hearts ---
    public void startValentineEffect() {
        if (valentineTaskId != -1) return;
        valentineTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location loc = p.getLocation();
                for (int i = 0; i < 6; i++) {
                    double offsetX = (Math.random() - 0.5) * 20;
                    double offsetY = 1 + Math.random() * 10;
                    double offsetZ = (Math.random() - 0.5) * 20;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    // Floating hearts drifting upward
                    p.spawnParticle(org.bukkit.Particle.HEART, particleLoc, 1, 0.3, 0.5, 0.3, 0.0);
                }
                // Pink dust accents
                for (int i = 0; i < 5; i++) {
                    double offsetX = (Math.random() - 0.5) * 18;
                    double offsetY = Math.random() * 8;
                    double offsetZ = (Math.random() - 0.5) * 18;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    p.spawnParticle(org.bukkit.Particle.CHERRY_LEAVES, particleLoc, 1, 0.5, 0.3, 0.5, 0.01);
                }
            }
        }, 0L, 10L);
        getLogger().info("Valentine effect started!");
    }

    public void stopValentineEffect() {
        if (valentineTaskId != -1) {
            Bukkit.getScheduler().cancelTask(valentineTaskId);
            valentineTaskId = -1;
            getLogger().info("Valentine effect stopped!");
        }
    }

    // --- Spring: Cherry blossoms and green nature sparkles (skip cold biomes) ---
    public void startSpringEffect() {
        if (springTaskId != -1) return;
        springTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                org.bukkit.block.Biome biome = p.getLocation().getBlock().getBiome();
                if (COLD_BIOMES.contains(biome)) continue;
                Location loc = p.getLocation();
                // Cherry blossom petals drifting down
                for (int i = 0; i < 10; i++) {
                    double offsetX = (Math.random() - 0.5) * 25;
                    double offsetY = 4 + Math.random() * 12;
                    double offsetZ = (Math.random() - 0.5) * 25;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    p.spawnParticle(org.bukkit.Particle.CHERRY_LEAVES, particleLoc, 1, 0.8, 1.5, 0.8, 0.02);
                }
                // Green nature sparkles near the ground
                for (int i = 0; i < 5; i++) {
                    double offsetX = (Math.random() - 0.5) * 20;
                    double offsetY = Math.random() * 4;
                    double offsetZ = (Math.random() - 0.5) * 20;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    p.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, particleLoc, 1, 0.3, 0.3, 0.3, 0.0);
                }
            }
        }, 0L, 6L);
        getLogger().info("Spring effect started!");
    }

    public void stopSpringEffect() {
        if (springTaskId != -1) {
            Bukkit.getScheduler().cancelTask(springTaskId);
            springTaskId = -1;
            getLogger().info("Spring effect stopped!");
        }
    }

    // --- Summer: Warm shimmering flames and dripping water (skip cold biomes) ---
    public void startSummerEffect() {
        if (summerTaskId != -1) return;
        summerTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                org.bukkit.block.Biome biome = p.getLocation().getBlock().getBiome();
                if (COLD_BIOMES.contains(biome)) continue;
                Location loc = p.getLocation();
                // Heat shimmer / warm flame particles floating upward
                for (int i = 0; i < 6; i++) {
                    double offsetX = (Math.random() - 0.5) * 25;
                    double offsetY = Math.random() * 5;
                    double offsetZ = (Math.random() - 0.5) * 25;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    p.spawnParticle(org.bukkit.Particle.FLAME, particleLoc, 1, 0.2, 0.5, 0.2, 0.003);
                }
                // Water drips for a hot-day splash feel
                for (int i = 0; i < 4; i++) {
                    double offsetX = (Math.random() - 0.5) * 20;
                    double offsetY = 3 + Math.random() * 8;
                    double offsetZ = (Math.random() - 0.5) * 20;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    p.spawnParticle(org.bukkit.Particle.DRIPPING_WATER, particleLoc, 1, 0.3, 0.5, 0.3, 0.0);
                }
                // Occasional sun sparkle
                if (Math.random() < 0.3) {
                    double offsetX = (Math.random() - 0.5) * 15;
                    double offsetY = 5 + Math.random() * 10;
                    double offsetZ = (Math.random() - 0.5) * 15;
                    Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);
                    p.spawnParticle(org.bukkit.Particle.END_ROD, particleLoc, 1, 0.5, 0.5, 0.5, 0.01);
                }
            }
        }, 0L, 8L);
        getLogger().info("Summer effect started!");
    }

    public void stopSummerEffect() {
        if (summerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(summerTaskId);
            summerTaskId = -1;
            getLogger().info("Summer effect stopped!");
        }
    }

    /** Start the effect for the given event name. Called from WebServer. */
    public void startEventEffect(String eventName) {
        switch (eventName.toLowerCase()) {
            case "christmas" -> startChristmasSnow();
            case "halloween" -> startHalloweenEffect();
            case "newyear" -> startNewYearEffect();
            case "valentine" -> startValentineEffect();
            case "spring" -> startSpringEffect();
            case "summer" -> startSummerEffect();
        }
    }

    /** Stop the effect for the given event name. Called from WebServer. */
    public void stopEventEffect(String eventName) {
        switch (eventName.toLowerCase()) {
            case "christmas" -> stopChristmasSnow();
            case "halloween" -> stopHalloweenEffect();
            case "newyear" -> stopNewYearEffect();
            case "valentine" -> stopValentineEffect();
            case "spring" -> stopSpringEffect();
            case "summer" -> stopSummerEffect();
        }
    }

}

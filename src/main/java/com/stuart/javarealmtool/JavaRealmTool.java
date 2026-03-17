package com.stuart.javarealmtool;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class JavaRealmTool extends JavaPlugin implements Listener, TabCompleter {

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
    private final Map<UUID, String> menuOrigin = new HashMap<>();
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
    private final String HOLOGRAM_WAND_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "Hologram Wand";
    
    private final String GUI_MENU_SELECTOR = ChatColor.AQUA + "Menu Selection";
    private final String GUI_HELPER_MENU = ChatColor.GREEN + "Helper Menu";
    private final String GUI_MODERATOR_MENU = ChatColor.GOLD + "Moderator Menu";
    private final String GUI_PLAYER_MENU = ChatColor.GREEN + "Player Menu";
    private final String GUI_PLAYER_TICKET_MENU = ChatColor.AQUA + "Tickets";
    private final String GUI_PLAYER_LIST_TPA = ChatColor.GREEN + "Players (TPA)";
    private final String GUI_REPORT_PLAYER = ChatColor.RED + "Report Player";
    private final String GUI_EVENT_LIST = ChatColor.LIGHT_PURPLE + "Event Manager";
    private final String GUI_ACTIVE_EVENT = ChatColor.LIGHT_PURPLE + "Active Event";
    private final String GUI_CUSTOM_ENCHANTS = ChatColor.LIGHT_PURPLE + "Custom Enchantments";
    
    private final String GUI_PLAYER_TICKETS = ChatColor.GOLD + "Your Tickets";
    private final String GUI_PLAYER_APPEALS = ChatColor.GOLD + "Your Appeals";
    private final String GUI_TICKET_CATEGORY = ChatColor.AQUA + "Select Ticket Category";
    private final String GUI_APPEAL_CATEGORY = ChatColor.AQUA + "Select Appeal Category";
    private final String GUI_MY_TICKET_OPTIONS = ChatColor.GOLD + "Ticket Options: ";
    private final String GUI_MY_APPEAL_OPTIONS = ChatColor.GOLD + "Appeal Options: ";
    private final String GUI_WARP_MANAGEMENT = ChatColor.BLUE + "Manage Warp: ";
    private final String GUI_CLAIMS = ChatColor.BLUE + "Chunk Claims";
    private final String GUI_CLAIM_CONFIRM = ChatColor.YELLOW + "Confirm Claim";
    private final String GUI_UNCLAIM_CONFIRM = ChatColor.YELLOW + "Confirm Unclaim";
    private final String GUI_TRUST_PLAYER = ChatColor.BLUE + "Trust Player";
    private final String GUI_UNTRUST_PLAYER = ChatColor.BLUE + "Remove Trusted";
    private final String GUI_WORLD_UTILITIES = ChatColor.AQUA + "World Utilities";
    private final String GUI_WORLD_LIST = ChatColor.GREEN + "Worlds";
    private final String GUI_WORLD_OPTIONS = ChatColor.YELLOW + "World: ";
    private final String GUI_WORLD_SETTINGS = ChatColor.AQUA + "World Settings";
    private final String GUI_NPC_SHOP = ChatColor.GOLD + "NPC Shop";
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
    private int antiLagWarningTaskId = -1;
    private int antiLagWarningTaskId2 = -1;
    private int antiLagClearTaskId = -1;

    // Scheduled announcements task IDs (used for reloading config without restart)
    private int scheduledAnnouncementsTaskId = -1;
    private int scheduledAnnouncementsOneTimeCheckTaskId = -1;
    private int scheduledCommandsTaskId = -1;

    private final Map<UUID, Long> reportCooldowns = new HashMap<>();
    private final Set<UUID> toolRespawnQueue = new HashSet<>();

    // Simple anti-xray tracking
    private final Map<UUID, List<Long>> oreBreakTimestamps = new HashMap<>();
    private final Map<UUID, Long> xrayLockUntil = new HashMap<>();
    private static final long XRAY_WINDOW_MS = 10_000L; // 10 seconds
    private static final int XRAY_THRESHOLD = 12; // ore breaks in window to flag
    private static final long XRAY_PENALTY_MS = 15_000L; // lock mining for 15 seconds

    // Rank management
    private static final String RANKS_PATH = "ranks";
    private static final String PLAYER_RANK_PATH = "player_rank";

    private enum ActionType { KICK, BAN, WARN, ANNOUNCE, ADD_NOTE, SET_WARP, WORLD_CREATE_NAME, TICKET_RESPOND, TICKET_RESOLVE, TICKET_CREATE, APPEAL_CREATE, REPORT, ENCHANT, HOLOGRAM, SUMMON_NPC }
    private static class PunishmentContext {
        String targetName;
        ActionType type;
        int expectedLines = 0;
        int currentLine = 0;
        List<String> lines;

        PunishmentContext(String n, ActionType t) { this.targetName = n; this.type = t; }
    }

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            setupConfig();
            createDataFile();
            ensureDefaultNpcLibrary();
            ensureDefaultEnchantQuests();
            setupPunishTeam();

            Bukkit.getPluginManager().registerEvents(this, this);
            registerCitizensClickListener();

            // Load any worlds that exist on disk but were not automatically loaded by Spigot at server start.
            // This ensures worlds created in-game (e.g., hub worlds) persist after a restart.
            loadPersistedWorlds();

            if (getCommand("dmt") != null) {
                getCommand("dmt").setExecutor(this);
                getCommand("dmt").setTabCompleter(this);
            }
            if (getCommand("ticket") != null) { getCommand("ticket").setExecutor(this); getCommand("ticket").setTabCompleter(this); }
            if (getCommand("tpa") != null) { getCommand("tpa").setExecutor(this); getCommand("tpa").setTabCompleter(this); }
            if (getCommand("kit") != null) { getCommand("kit").setExecutor(this); getCommand("kit").setTabCompleter(this); }
            if (getCommand("bounty") != null) { getCommand("bounty").setExecutor(this); getCommand("bounty").setTabCompleter(this); }
            if (getCommand("shop") != null) { getCommand("shop").setExecutor(this); getCommand("shop").setTabCompleter(this); }
            if (getCommand("quest") != null) { getCommand("quest").setExecutor(this); getCommand("quest").setTabCompleter(this); }
            if (getCommand("apply") != null) { getCommand("apply").setExecutor(this); getCommand("apply").setTabCompleter(this); }
            if (getCommand("vote") != null) { getCommand("vote").setExecutor(this); getCommand("vote").setTabCompleter(this); }
            if (getCommand("crate") != null) { getCommand("crate").setExecutor(this); getCommand("crate").setTabCompleter(this); }
            if (getCommand("nick") != null) { getCommand("nick").setExecutor(this); getCommand("nick").setTabCompleter(this); }
            if (getCommand("rules") != null) { getCommand("rules").setExecutor(this); getCommand("rules").setTabCompleter(this); }
            if (getCommand("duel") != null) { getCommand("duel").setExecutor(this); getCommand("duel").setTabCompleter(this); }
            if (getCommand("pwarp") != null) { getCommand("pwarp").setExecutor(this); getCommand("pwarp").setTabCompleter(this); }
            if (getCommand("achievements") != null) { getCommand("achievements").setExecutor(this); getCommand("achievements").setTabCompleter(this); }
            if (getCommand("stats") != null) { getCommand("stats").setExecutor(this); getCommand("stats").setTabCompleter(this); }
            if (getCommand("report") != null) { getCommand("report").setExecutor(this); getCommand("report").setTabCompleter(this); }
            if (getCommand("balance") != null) { getCommand("balance").setExecutor(this); getCommand("balance").setTabCompleter(this); }
            if (getCommand("economy") != null) { getCommand("economy").setExecutor(this); getCommand("economy").setTabCompleter(this); }

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

        // Start Maintenance mode checker (every 10 seconds = 200 ticks)
        startMaintenanceChecker();

        // Resume event effects if events were active before restart
        ConfigurationSection activeEvents = dataConfig.getConfigurationSection("events.active");
        if (activeEvents != null) {
            for (String eventName : activeEvents.getKeys(false)) {
                startEventEffect(eventName);
            }
        }

        ensureDefaultEnchantQuests();

        // Apply ranks + permissions for online players in case of reload
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyRankToPlayer(p);
            applyPermissionGroup(p);
        }

        // Start anti-lag ground item cleanup (configurable)
        startAntiLagCleanup();

        getLogger().info("Drowsy Management Tool Fully Loaded!");
        } catch (Throwable t) {
            getLogger().severe("Failed to enable DrowsyManagementTool: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
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
            getLogger().log(java.util.logging.Level.SEVERE, "Could not save data to " + dataFile, e);
        }
    }

    // ========== CUSTOM ENCHANTMENTS GUI ==========
    private void openCustomEnchantGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_CUSTOM_ENCHANTS);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();

        List<String> enchants = dataConfig.getStringList("enchants.unlocked." + p.getUniqueId());
        for (String ench : enchants) {
            if (ench == null || ench.isEmpty()) continue;
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta meta = book.getItemMeta();
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + ench);
            book.setItemMeta(meta);
            int slot = getNextGridSlot();
            if (slot != -1) gui.setItem(slot, book);
        }

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(gui.getSize() - 1, back);

        p.openInventory(gui);
    }

    // --- Permission Groups ---

    public void applyPermissionGroup(Player player) {
        UUID uuid = player.getUniqueId();
        // Remove old attachment if exists
        removePermissionAttachment(player);

        PermissionAttachment attachment = player.addAttachment(this);
        boolean addedAny = false;

        // Apply group permissions (if used)
        String groupName = getPlayerGroup(uuid);
        if (groupName != null) {
            List<String> perms = dataConfig.getStringList("groups." + groupName + ".permissions");
            for (String perm : perms) {
                if (perm == null) continue;
                perm = perm.trim();
                if (perm.isEmpty()) continue;
                if (perm.startsWith("-")) {
                    attachment.setPermission(perm.substring(1).trim(), false);
                } else {
                    attachment.setPermission(perm, true);
                }
                addedAny = true;
            }
        }

        // Apply rank permissions
        String rank = getPlayerRank(uuid);
        if (rank != null) {
            List<String> perms = dataConfig.getStringList(RANKS_PATH + "." + rank + ".permissions");
            for (String perm : perms) {
                if (perm == null) continue;
                perm = perm.trim();
                if (perm.isEmpty()) continue;
                if (perm.startsWith("-")) {
                    attachment.setPermission(perm.substring(1).trim(), false);
                } else {
                    attachment.setPermission(perm, true);
                }
                addedAny = true;
            }
        }

        if (addedAny) {
            permissionAttachments.put(uuid, attachment);
            player.recalculatePermissions();
        } else {
            try {
                player.removeAttachment(attachment);
            } catch (Exception ignored) {}
        }

        // Apply rank prefix/team
        applyRankToPlayer(player);
    }

    private boolean hasTag(Player p, String tag) {
        if (tag == null) return false;
        for (String t : p.getScoreboardTags()) {
            if (t.equalsIgnoreCase(tag)) return true;
        }
        return false;
    }

    private boolean isHelper(Player p) {
        return hasTag(p, "Helper");
    }

    private boolean isModerator(Player p) {
        return hasTag(p, "Moderator");
    }

    private boolean isAdminTag(Player p) {
        return hasTag(p, "Admin");
    }

    private boolean isManagerTag(Player p) {
        return hasTag(p, "Manager");
    }

    private boolean isOwnerTag(Player p) {
        return hasTag(p, "Owner");
    }

    private boolean isHeadAdminTag(Player p) {
        return hasTag(p, "Head_Admin");
    }

    private boolean isStaffTagged(Player p) {
        return isHelper(p) || isModerator(p) || isAdminTag(p) || isManagerTag(p) || isOwnerTag(p) || isHeadAdminTag(p);
    }

    private void setMenuOrigin(Player p, String origin) {
        if (origin == null) {
            menuOrigin.remove(p.getUniqueId());
        } else {
            menuOrigin.put(p.getUniqueId(), origin);
        }
    }

    private void clearMenuOrigin(Player p) {
        menuOrigin.remove(p.getUniqueId());
    }

    private void returnToPreviousMenu(Player p) {
        String origin = menuOrigin.get(p.getUniqueId());
        if ("helper".equals(origin)) {
            openHelperMenu(p);
        } else if ("moderator".equals(origin)) {
            openModeratorMenu(p);
        } else {
            openMenuSelector(p);
        }
        clearMenuOrigin(p);
    }

    private boolean canBan(Player p) {
        // Only operators or those with dmt.admin can ban
        return p.isOp() || p.hasPermission("dmt.admin");
    }

    private boolean hasDmtCommandPermission(Player p, String command) {
        if (p.isOp() || p.hasPermission("dmt.admin")) return true;
        if (command == null || command.trim().isEmpty()) return false;
        // Moderators should be able to run anti-lag commands
        if (command.equalsIgnoreCase("antlag") && isModerator(p)) return true;
        // Helpers and moderators should be able to open the menu
        if (command.equalsIgnoreCase("menu") && (isHelper(p) || isModerator(p))) return true;
        return p.hasPermission("dmt.command." + command.toLowerCase());
    }

    public void removePermissionAttachment(Player player) {
        PermissionAttachment old = permissionAttachments.remove(player.getUniqueId());
        if (old != null) {
            try { player.removeAttachment(old); } catch (Exception ignored) {}
            player.recalculatePermissions();
        }
    }

    public String getPlayerGroup(UUID uuid) {
        ConfigurationSection groupsSection = dataConfig.getConfigurationSection("groups");
        if (groupsSection == null) return null;
        for (String groupName : groupsSection.getKeys(false)) {
            List<String> members = dataConfig.getStringList("groups." + groupName + ".members");
            if (members.contains(uuid.toString())) return groupName;
        }
        return null;
    }

    /**
     * Handles /dmt rank ... commands.
     */
    private void handleRankCommand(Player p, String[] args) {
        if (args.length == 0) {
            p.sendMessage(ChatColor.RED + "Usage: /dmt rank <create|remove|list|info|add|addprefix|addperm> ...");
            return;
        }

        // Support both:
        //  - /dmt rank addperm <rank> <permission>
        //  - /dmt rank <rank> addperm <permission>
        // and similarly for add/addprefix.
        String sub = args[0].toLowerCase();
        String rank = null;
        String action = null;
        int actionIndex = 1;

        // If the first argument is a known rank, treat it as the rank and parse the action after it.
        String maybeRankKey = RANKS_PATH + "." + args[0];
        if (args.length >= 2 && dataConfig.contains(maybeRankKey)) {
            rank = args[0];
            action = args[1].toLowerCase();
            actionIndex = 2;
        } else {
            action = sub;
        }

        switch (action) {
            case "create":
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "Usage: /dmt rank create <rank>");
                    return;
                }
                createRank(p, args[1]);
                break;
            case "remove":
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "Usage: /dmt rank remove <rank>");
                    return;
                }
                removeRank(p, args[1]);
                break;
            case "list":
                showRankList(p);
                break;
            case "info":
                // support both /dmt rank info <rank> and /dmt rank <rank> info
                if (rank == null) {
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt rank info <rank>");
                        return;
                    }
                    rank = args[1];
                    actionIndex = 2;
                }
                showRankInfo(p, rank);
                break;
            case "add":
                // support both /dmt rank add <rank> <player> and /dmt rank <rank> add <player>
                if (rank == null) {
                    if (args.length < 3) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt rank add <rank> <player>");
                        return;
                    }
                    rank = args[1];
                    actionIndex = 2;
                }
                if (args.length <= actionIndex) {
                    p.sendMessage(ChatColor.RED + "Usage: /dmt rank " + rank + " add <player>");
                    return;
                }
                addPlayerToRank(p, rank, args[actionIndex]);
                break;
            case "addprefix":
                // support both /dmt rank addprefix <rank> <prefix> and /dmt rank <rank> addprefix <prefix>
                if (rank == null) {
                    if (args.length < 3) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt rank addprefix <rank> <prefix>");
                        return;
                    }
                    rank = args[1];
                    actionIndex = 2;
                }
                if (args.length <= actionIndex) {
                    p.sendMessage(ChatColor.RED + "Usage: /dmt rank " + rank + " addprefix <prefix>");
                    return;
                }
                addRankPrefix(p, rank, String.join(" ", Arrays.copyOfRange(args, actionIndex, args.length)));
                break;
            case "addperm":
                // support both /dmt rank addperm <rank> <permission> and /dmt rank <rank> addperm <permission>
                if (rank == null) {
                    if (args.length < 3) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt rank addperm <rank> <permission>");
                        return;
                    }
                    rank = args[1];
                    actionIndex = 2;
                }
                if (args.length <= actionIndex) {
                    p.sendMessage(ChatColor.RED + "Usage: /dmt rank " + rank + " addperm <permission>");
                    return;
                }
                addRankPermission(p, rank, args[actionIndex]);
                break;
            default:
                p.sendMessage(ChatColor.RED + "Unknown rank subcommand.");
        }
    }

    private void createRank(Player p, String rank) {
        if (rank == null || rank.trim().isEmpty()) {
            p.sendMessage(ChatColor.RED + "Invalid rank name.");
            return;
        }
        String key = RANKS_PATH + "." + rank;
        if (dataConfig.contains(key)) {
            p.sendMessage(ChatColor.YELLOW + "Rank already exists: " + rank);
            return;
        }
        dataConfig.set(key + ".prefix", "&7[" + rank + "] ");
        dataConfig.set(key + ".permissions", new ArrayList<>());
        dataConfig.set(key + ".members", new ArrayList<>());
        saveDataFile();
        p.sendMessage(ChatColor.GREEN + "Rank created: " + rank);
    }

    private void removeRank(Player p, String rank) {
        String key = RANKS_PATH + "." + rank;
        if (!dataConfig.contains(key)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }
        dataConfig.set(key, null);
        // remove this rank assignment from any players
        if (dataConfig.contains(PLAYER_RANK_PATH)) {
            for (String uuidKey : dataConfig.getConfigurationSection(PLAYER_RANK_PATH).getKeys(false)) {
                String assigned = dataConfig.getString(PLAYER_RANK_PATH + "." + uuidKey);
                if (assigned != null && assigned.equals(rank)) {
                    dataConfig.set(PLAYER_RANK_PATH + "." + uuidKey, null);
                }
            }
        }
        saveDataFile();
        p.sendMessage(ChatColor.GREEN + "Rank removed: " + rank);
    }

    private void addPlayerToRank(Player p, String rank, String playerName) {
        String key = RANKS_PATH + "." + rank;
        if (!dataConfig.contains(key)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null || target.getUniqueId() == null) {
            p.sendMessage(ChatColor.RED + "Player not found: " + playerName);
            return;
        }
        setPlayerRank(target.getUniqueId(), rank);
        Player online = target.getPlayer();
        if (online != null) {
            applyPermissionGroup(online);
        }
        p.sendMessage(ChatColor.GREEN + "Added " + playerName + " to rank " + rank);
    }

    private void addRankPrefix(Player p, String rank, String prefix) {
        String key = RANKS_PATH + "." + rank;
        if (!dataConfig.contains(key)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }
        dataConfig.set(key + ".prefix", prefix);
        saveDataFile();
        p.sendMessage(ChatColor.GREEN + "Set prefix for " + rank + " to: " + prefix);
    }

    private void addRankPermission(Player p, String rank, String perm) {
        String key = RANKS_PATH + "." + rank + ".permissions";
        if (!dataConfig.contains(RANKS_PATH + "." + rank)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }
        List<String> perms = new ArrayList<>(dataConfig.getStringList(key));
        if (perms.contains(perm)) {
            p.sendMessage(ChatColor.YELLOW + "Rank already has permission: " + perm);
            return;
        }
        perms.add(perm);
        dataConfig.set(key, perms);
        saveDataFile();
        p.sendMessage(ChatColor.GREEN + "Added permission " + perm + " to rank " + rank);
    }

    private void showRankList(Player p) {
        if (!dataConfig.contains(RANKS_PATH)) {
            p.sendMessage(ChatColor.YELLOW + "No ranks defined yet.");
            return;
        }

        p.sendMessage(ChatColor.GOLD + "--- Defined Ranks ---");
        for (String rank : dataConfig.getConfigurationSection(RANKS_PATH).getKeys(false)) {
            String prefix = dataConfig.getString(RANKS_PATH + "." + rank + ".prefix", "");
            List<String> perms = dataConfig.getStringList(RANKS_PATH + "." + rank + ".permissions");
            List<String> members = dataConfig.getStringList(RANKS_PATH + "." + rank + ".members");

            p.sendMessage(ChatColor.AQUA + rank + ChatColor.GRAY + " " + prefix);
            p.sendMessage(ChatColor.GRAY + "  Permissions: " + (perms.isEmpty() ? "<none>" : String.join(", ", perms)));
            p.sendMessage(ChatColor.GRAY + "  Members: " + (members.isEmpty() ? "<none>" : String.join(", ", members)));
        }
    }

    private void showRankInfo(Player p, String rank) {
        String key = RANKS_PATH + "." + rank;
        if (!dataConfig.contains(key)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }

        String prefix = dataConfig.getString(key + ".prefix", "");
        List<String> perms = dataConfig.getStringList(key + ".permissions");
        List<String> members = dataConfig.getStringList(key + ".members");

        p.sendMessage(ChatColor.GOLD + "--- Rank: " + rank + " ---");
        p.sendMessage(ChatColor.AQUA + "Prefix: " + ChatColor.RESET + prefix);
        p.sendMessage(ChatColor.AQUA + "Permissions: " + ChatColor.RESET + (perms.isEmpty() ? "<none>" : String.join(", ", perms)));
        p.sendMessage(ChatColor.AQUA + "Members: " + ChatColor.RESET + (members.isEmpty() ? "<none>" : String.join(", ", members)));
    }

    public String getPlayerRank(UUID uuid) {
        if (dataConfig.contains(PLAYER_RANK_PATH + "." + uuid)) {
            return dataConfig.getString(PLAYER_RANK_PATH + "." + uuid);
        }
        // fallback: scan ranks membership
        if (dataConfig.contains(RANKS_PATH)) {
            for (String rank : dataConfig.getConfigurationSection(RANKS_PATH).getKeys(false)) {
                List<String> members = dataConfig.getStringList(RANKS_PATH + "." + rank + ".members");
                if (members.contains(uuid.toString())) return rank;
            }
        }
        return null;
    }

    public void setPlayerRank(UUID uuid, String rank) {
        // remove from existing ranks
        if (dataConfig.contains(RANKS_PATH)) {
            for (String existing : dataConfig.getConfigurationSection(RANKS_PATH).getKeys(false)) {
                List<String> members = new ArrayList<>(dataConfig.getStringList(RANKS_PATH + "." + existing + ".members"));
                if (members.remove(uuid.toString())) {
                    dataConfig.set(RANKS_PATH + "." + existing + ".members", members);
                }
            }
        }
        if (rank != null && !rank.isEmpty()) {
            List<String> members = new ArrayList<>(dataConfig.getStringList(RANKS_PATH + "." + rank + ".members"));
            if (!members.contains(uuid.toString())) {
                members.add(uuid.toString());
                dataConfig.set(RANKS_PATH + "." + rank + ".members", members);
            }
            dataConfig.set(PLAYER_RANK_PATH + "." + uuid, rank);
        } else {
            dataConfig.set(PLAYER_RANK_PATH + "." + uuid, null);
        }
        saveDataFile();
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
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        if (cmd.getName().equalsIgnoreCase("dmt")) {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelpMessage(p);
                return true;
            }

            String subcommand = args[0].toLowerCase();
            if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
                sendDmtSubcommandHelp(p, subcommand);
                return true;
            }

            // allow players to use /dmt as a wrapper for standard player commands
            switch (subcommand) {
                case "kit":
                case "crate":
                case "bounty":
                case "balance":
                case "shop":
                case "quest":
                case "vote":
                case "apply":
                case "ticket":
                case "tpa":
                case "nick":
                case "rules":
                case "duel":
                case "pwarp":
                case "achievements":
                case "stats":
                case "report":
                    String rest = args.length > 1 ? " " + String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
                    Bukkit.dispatchCommand(p, subcommand + rest);
                    return true;
            }

            boolean isAdminCmd = p.hasPermission("dmt.admin");
            boolean isModTag = isModerator(p);
            boolean isHelperTag = isHelper(p);
            boolean isAnyStaffTag = isStaffTagged(p);

            // Allow helpers/moderators to use their specific tools without full admin perms
            if (!isAdminCmd) {
                if (subcommand.equals("menu") && (isHelperTag || isModTag)) {
                    // allowed
                } else if (subcommand.equals("antlag") && isModTag) {
                    // allowed
                } else if (subcommand.equals("staff") && isAnyStaffTag) {
                    // allowed
                } else {
                    p.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
            }

            switch(subcommand) {
                case "setpunishloc":
                    if (!hasDmtCommandPermission(p, "setpunishloc")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    saveLoc("punishment_location", p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Punishment location set to your current location.");
                    break;
                case "setjailloc":
                    if (!hasDmtCommandPermission(p, "setjailloc")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    saveLoc("jail_location", p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Jail location set to your current location.");
                    break;
                case "tpjail":
                    if (!hasDmtCommandPermission(p, "tpjail")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    Location jailLoc = getLoc("jail_location");
                    if (jailLoc == null) {
                        p.sendMessage(ChatColor.RED + "Jail location not set yet. Use /dmt setjailloc");
                        return true;
                    }
                    p.teleport(jailLoc);
                    p.sendMessage(ChatColor.AQUA + "Teleported to jail.");
                    break;
                case "punish":
                    if (!hasDmtCommandPermission(p, "punish")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
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
                    logAction(p.getName(), "punished", targetName + " (" + durationStr + ")");
                    break;
                case "menu":
                    if (!hasDmtCommandPermission(p, "menu")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    try {
                        // Helpers/Moderators use the selector menu; full admins use the admin main menu.
                        if (!p.hasPermission("dmt.admin") && isStaffTagged(p)) {
                            openMenuSelector(p);
                        } else {
                            openMainMenu(p);
                        }
                    } catch (Exception ex) {
                        p.sendMessage(ChatColor.RED + "An unexpected error occurred while opening the menu.");
                        getLogger().log(java.util.logging.Level.SEVERE, "Failed to open admin menu: " + ex.getMessage(), ex);
                    }
                    break;
                case "staff":
                    // Staff list for players with any staff-related tag.
                    if (!isStaffTagged(p) && !p.hasPermission("dmt.admin")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2 || !args[1].equalsIgnoreCase("list")) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt staff list");
                        return true;
                    }

                    List<String> staffLines = new ArrayList<>();
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        List<String> tags = new ArrayList<>();
                        if (isHelper(online)) tags.add("Helper");
                        if (isModerator(online)) tags.add("Moderator");
                        if (isAdminTag(online)) tags.add("Admin");
                        if (isManagerTag(online)) tags.add("Manager");
                        if (isOwnerTag(online)) tags.add("Owner");
                        if (isHeadAdminTag(online)) tags.add("Head_Admin");
                        if (!tags.isEmpty()) {
                            staffLines.add(ChatColor.AQUA + online.getName() + ChatColor.GRAY + " [" + String.join(", ", tags) + "]");
                        }
                    }
                    p.sendMessage(ChatColor.GOLD + "===== " + ChatColor.AQUA + "Staff Online" + ChatColor.GOLD + " =====");
                    if (staffLines.isEmpty()) {
                        p.sendMessage(ChatColor.GRAY + "No staff members online.");
                    } else {
                        staffLines.forEach(p::sendMessage);
                    }
                    break;
                case "tp":
                    if (!hasDmtCommandPermission(p, "tp")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length >= 3 && args[1].equalsIgnoreCase("world")) {
                        String tpWorld = args[2];
                        World w = Bukkit.getWorld(tpWorld);
                        if (w == null) {
                            p.sendMessage(ChatColor.RED + "World '" + tpWorld + "' not found.");
                        } else {
                            // respect world lock
                            if (dataConfig.getBoolean("worldlocks." + tpWorld, false)) {
                                p.sendMessage(ChatColor.RED + "That world is locked.");
                            } else {
                                // store current location in current world before moving
                                Location current = p.getLocation();
                                if (current != null && current.getWorld() != null) {
                                    saveLoc("last_location." + p.getUniqueId() + "." + current.getWorld().getName(), current);
                                }
                                Location lastLocation = getLoc("last_location." + p.getUniqueId() + "." + tpWorld);
                                if (lastLocation != null) {
                                    p.teleport(lastLocation);
                                    p.sendMessage(ChatColor.GREEN + "Teleported to " + tpWorld + " at your last location.");
                                } else {
                                    p.teleport(w.getSpawnLocation());
                                    p.sendMessage(ChatColor.GREEN + "Teleported to world '" + tpWorld + "'.");
                                }
                            }
                        }
                    } else {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt tp world <name>");
                    }
                    break;
                case "world":
                    if (!hasDmtCommandPermission(p, "world")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length != 3) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt world <world> <lock|unlock>");
                        return true;
                    }
                    String worldNameArg = args[1];
                    String worldAction = args[2].toLowerCase();
                    if (!worldAction.equals("lock") && !worldAction.equals("unlock")) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt world <world> <lock|unlock>");
                        return true;
                    }
                    boolean lock = worldAction.equals("lock");
                    dataConfig.set("worldlocks." + worldNameArg, lock);
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "World '" + worldNameArg + "' is now " + (lock ? "locked" : "unlocked") + ".");
                    return true;
                case "summon":
                    if (!hasDmtCommandPermission(p, "summon")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt summon <name>");
                        return true;
                    }
                    spawnNpc(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    break;
                case "npc":
                    if (!hasDmtCommandPermission(p, "npc")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt npc <list|add|remove|summon> [username]");
                        break;
                    }
                    String npcSub = args[1].toLowerCase();
                    switch (npcSub) {
                        case "list":
                            listNpcSkins(p);
                            break;
                        case "add":
                            if (args.length < 3) {
                                p.sendMessage(ChatColor.RED + "Usage: /dmt npc add <username>");
                                break;
                            }
                            addNpcSkinFromSkinstealer(p, args[2]);
                            break;
                        case "remove":
                            if (args.length < 3) {
                                p.sendMessage(ChatColor.RED + "Usage: /dmt npc remove <username>");
                                break;
                            }
                            removeNpcSkin(p, args[2]);
                            break;
                        case "summon":
                        case "spawn":
                            if (args.length < 3) {
                                p.sendMessage(ChatColor.RED + "Usage: /dmt npc summon <username>");
                                break;
                            }
                            String libraryName = args[2];
                            List<String> skins = dataConfig.getStringList("npcLibrary");
                            if (!skins.contains(libraryName)) {
                                p.sendMessage(ChatColor.RED + "NPC library does not contain '" + libraryName + "'.");
                                p.sendMessage(ChatColor.GRAY + "Use /dmt npc add " + libraryName + " to add it.");
                                break;
                            }
                            spawnNpc(p, libraryName);
                            break;
                        default:
                            p.sendMessage(ChatColor.RED + "Unknown npc subcommand. Use /dmt npc list|add|remove|summon");
                    }
                    break;
                case "list":
                    if (args.length >= 2 && args[1].equalsIgnoreCase("npcs")) {
                        listNpcSkins(p);
                    } else {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt list npcs");
                    }
                    break;
                case "sethub":
                    if (!hasDmtCommandPermission(p, "sethub")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    saveLoc("hub_location", p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Hub location set to your current position.");
                    break;
                case "unsethub":
                    if (!hasDmtCommandPermission(p, "unsethub")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    dataConfig.set("hub_location", null);
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Hub location has been removed.");
                    break;
                case "setserverspawn":
                    if (!hasDmtCommandPermission(p, "setserverspawn")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    saveLoc("server_spawn", p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Server spawn location set to your current position.");
                    break;
                case "clearserverspawn":
                    if (!hasDmtCommandPermission(p, "clearserverspawn")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    dataConfig.set("server_spawn", null);
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Server spawn location cleared.");
                    break;
                case "spawnlast":
                    if (!hasDmtCommandPermission(p, "spawnlast")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length == 1) {
                        String prefix = "last_location." + p.getUniqueId() + ".";
                        if (!dataConfig.contains(prefix)) {
                            p.sendMessage(ChatColor.GREEN + "No stored last locations.");
                            return true;
                        }
                        Set<String> worlds = dataConfig.getConfigurationSection(prefix).getKeys(false);
                        if (worlds.isEmpty()) {
                            p.sendMessage(ChatColor.GREEN + "No stored last locations.");
                        } else {
                            p.sendMessage(ChatColor.GREEN + "Stored last locations: " + ChatColor.WHITE + String.join(", ", worlds));
                            p.sendMessage(ChatColor.GRAY + "Use /dmt spawnlast tp <world> to teleport to a saved location.");
                        }
                        return true;
                    }
                    if (args.length >= 3 && args[1].equalsIgnoreCase("tp")) {
                        String spawnLastWorld = args[2];
                        Location loc = getLoc("last_location." + p.getUniqueId() + "." + spawnLastWorld);
                        if (loc == null) {
                            p.sendMessage(ChatColor.RED + "No stored location for world '" + spawnLastWorld + "'.");
                            return true;
                        }
                        p.teleport(loc);
                        p.sendMessage(ChatColor.GREEN + "Teleported to your last location in " + spawnLastWorld + ".");
                        return true;
                    }
                    p.sendMessage(ChatColor.RED + "Usage: /dmt spawnlast [tp <world>]");
                    break;
                case "documentation":
                case "docs":
                    if (!hasDmtCommandPermission(p, "documentation")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    try {
                        generateDocumentationPdf();
                        p.sendMessage(ChatColor.AQUA + "Download the documentation PDF: " + getDocumentationUrl());
                    } catch (IOException ex) {
                        p.sendMessage(ChatColor.RED + "Failed to generate documentation PDF.");
                        ex.printStackTrace();
                    }
                    break;
                case "antlag":
                    if (!hasDmtCommandPermission(p, "antlag")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt antlag <on|off|now>");
                        break;
                    }
                    String opt = args[1].toLowerCase();
                    if (opt.equals("on") || opt.equals("enable")) {
                        dataConfig.set("anti_lag.enabled", true);
                        saveDataFile();
                        startAntiLagCleanup();
                        p.sendMessage(ChatColor.GREEN + "Anti-lag cleanup enabled.");
                    } else if (opt.equals("off") || opt.equals("disable")) {
                        dataConfig.set("anti_lag.enabled", false);
                        saveDataFile();
                        stopAntiLagCleanup();
                        p.sendMessage(ChatColor.RED + "Anti-lag cleanup disabled.");
                    } else if (opt.equals("now") || opt.equals("run")) {
                        clearGroundItems();
                        Bukkit.broadcastMessage(ChatColor.BLUE + "Drowsy Anti Lag: Drops/Items have been Cleared!");
                        p.sendMessage(ChatColor.GREEN + "Anti-lag cleanup executed immediately.");
                    } else {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt antlag <on|off|now>");
                    }
                    break;
                case "rank":
                    if (!hasDmtCommandPermission(p, "rank")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        break;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt rank <create|remove|add|addprefix|addperm> ...");
                        break;
                    }
                    handleRankCommand(p, Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "gencloud":
                    if (!hasDmtCommandPermission(p, "gencloud")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 4) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt gencloud <width> <length> <depth>");
                        return true;
                    }
                    int width, length, depth;
                    try {
                        width = Integer.parseInt(args[1]);
                        length = Integer.parseInt(args[2]);
                        depth = Integer.parseInt(args[3]);
                    } catch (NumberFormatException ignored) {
                        p.sendMessage(ChatColor.RED + "Invalid dimensions. Usage: /dmt gencloud <width> <length> <depth>");
                        return true;
                    }
                    if (width <= 0 || length <= 0 || depth <= 0) {
                        p.sendMessage(ChatColor.RED + "Width, length and depth must be positive numbers.");
                        return true;
                    }
                    int cloudY = p.getEyeLocation().getBlockY();
                    generateCloudLayer(p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockZ(), width, length, depth, cloudY);
                    p.sendMessage(ChatColor.GREEN + "Generated cloud around you (" + width + "x" + length + "x" + depth + ") with bottom at your head height (Y=" + cloudY + ").");
                    break;
                case "spawn":
                    if (!hasDmtCommandPermission(p, "spawn")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt spawn <view|list|mob1[,mob2,...]> <true|false>");
                        return true;
                    }
                    String action = args[1].toLowerCase();
                    if (action.equals("view") || action.equals("list")) {
                        if (!dataConfig.contains("disable_spawns")) {
                            p.sendMessage(ChatColor.GREEN + "No spawns are currently disabled.");
                            return true;
                        }
                        Set<String> disabled = dataConfig.getConfigurationSection("disable_spawns").getKeys(false);
                        if (disabled.isEmpty()) {
                            p.sendMessage(ChatColor.GREEN + "No spawns are currently disabled.");
                        } else {
                            p.sendMessage(ChatColor.GREEN + "Disabled spawns: " + ChatColor.WHITE + String.join(", ", disabled));
                        }
                        return true;
                    }
                    // Allow /dmt spawn reset or /dmt spawn <mob> reset
                    if ((args.length == 2 && args[1].equalsIgnoreCase("reset")) ||
                        (args.length == 3 && args[2].equalsIgnoreCase("reset"))) {
                        dataConfig.set("disable_spawns", null);
                        saveDataFile();
                        p.sendMessage(ChatColor.GREEN + "All spawn restrictions have been reset.");
                        return true;
                    }

                    if (args.length < 3) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt spawn <mob1[,mob2,...]> <true|false>");
                        return true;
                    }
                    boolean enable;
                    try {
                        enable = Boolean.parseBoolean(args[2]);
                    } catch (Exception ex) {
                        p.sendMessage(ChatColor.RED + "Invalid value. Use true or false.");
                        return true;
                    }
                    String[] mobNames = args[1].split(",");
                    List<String> updated = new ArrayList<>();
                    List<String> invalid = new ArrayList<>();
                    List<String> ambiguous = new ArrayList<>();
                    for (String raw : mobNames) {
                        String trimmed = raw.trim();
                        if (trimmed.isEmpty()) continue;
                        String key = trimmed.toLowerCase().replace(' ', '_');
                        EntityType match = null;
                        // Exact match first
                        try {
                            match = EntityType.valueOf(key.toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                            // try partial match
                            List<EntityType> candidates = new ArrayList<>();
                            for (EntityType t : EntityType.values()) {
                                if (t.name().toLowerCase().startsWith(key)) {
                                    candidates.add(t);
                                }
                            }
                            if (candidates.size() == 1) {
                                match = candidates.get(0);
                            } else if (candidates.size() > 1) {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < candidates.size(); i++) {
                                    if (i > 0) sb.append(", ");
                                    sb.append(candidates.get(i).name());
                                }
                                ambiguous.add(trimmed + " (" + sb + ")");
                            }
                        }
                        if (match != null) {
                            setSpawnDisabled(match, !enable);
                            updated.add(match.name().toLowerCase());
                        } else if (!ambiguous.contains(trimmed)) {
                            invalid.add(trimmed);
                        }
                    }
                    if (!updated.isEmpty()) {
                        p.sendMessage(ChatColor.GREEN + "Updated spawns: " + ChatColor.WHITE + String.join(", ", updated) + ChatColor.GREEN + " -> " + (enable ? "enabled" : "disabled"));
                    }
                    if (!ambiguous.isEmpty()) {
                        p.sendMessage(ChatColor.RED + "Ambiguous mob names: " + String.join(", ", ambiguous));
                    }
                    if (!invalid.isEmpty()) {
                        p.sendMessage(ChatColor.RED + "Unknown mobs: " + String.join(", ", invalid));
                    }
                    if (updated.isEmpty()) return true;
                    saveDataFile();
                    return true;
                case "killall":
                    if (!hasDmtCommandPermission(p, "killall")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2 || !args[1].equalsIgnoreCase("hostile")) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt killall hostile");
                        return true;
                    }
                    int count = 0;
                    for (World w : Bukkit.getWorlds()) {
                        for (Entity e : w.getEntities()) {
                            if (e instanceof Monster) {
                                e.remove();
                                count++;
                            }
                        }
                    }
                    p.sendMessage(ChatColor.GREEN + "Killed " + count + " hostile mobs.");
                    return true;
                default:
                    p.sendMessage(ChatColor.RED + "Unknown subcommand. Use /dmt help");
            }
            return true;
        }

        // additional commands handled outside of /dmt
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
            World hubWorld = hubLoc.getWorld();
            boolean hubLocked = hubWorld != null && dataConfig.getBoolean("worldlocks." + hubWorld.getName(), false);
            if (hubLocked && !p.hasPermission("dmt.admin")) {
                p.sendMessage(ChatColor.RED + "The hub world is currently locked.");
                return true;
            }
            p.teleport(hubLoc);
            p.sendMessage(ChatColor.AQUA + "Teleported to hub.");
            return true;
        }



        if (cmd.getName().equalsIgnoreCase("balance")) {
            if (args.length == 0) {
                long coins = getCoins(p.getUniqueId());
                int xp = p.getLevel();
                p.sendMessage(ChatColor.GOLD + "Your balance: " + ChatColor.GREEN + coins + " Drowsy coins " + ChatColor.AQUA + "| XP level: " + xp);
                return true;
            }

            if (!p.hasPermission("dmt.admin")) {
                p.sendMessage(ChatColor.RED + "You do not have permission to manage other players' balance.");
                return true;
            }

            String targetName = args[0];
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            if (target == null || target.getUniqueId() == null) {
                p.sendMessage(ChatColor.RED + "Player not found: " + targetName);
                return true;
            }

            if (args.length == 1) {
                long coins = getCoins(target.getUniqueId());
                p.sendMessage(ChatColor.GOLD + "Balance for " + ChatColor.AQUA + target.getName() + ChatColor.GOLD + ": " + ChatColor.GREEN + coins + " Drowsy coins");
                return true;
            }

            String action = args[1].toLowerCase();
            if (action.equals("reset")) {
                setCoins(target.getUniqueId(), 0);
                p.sendMessage(ChatColor.GREEN + "Reset " + target.getName() + "'s balance.");
                return true;
            }

            if (args.length < 3) {
                p.sendMessage(ChatColor.RED + "Usage: /balance <player> <add|remove> <amount> or /balance <player> reset");
                return true;
            }

            long amount;
            try {
                amount = Long.parseLong(args[2]);
            } catch (NumberFormatException ex) {
                p.sendMessage(ChatColor.RED + "Invalid amount.");
                return true;
            }
            if (amount < 0) {
                p.sendMessage(ChatColor.RED + "Amount must be positive.");
                return true;
            }

            if (action.equals("add")) {
                addCoins(target.getUniqueId(), amount);
                p.sendMessage(ChatColor.GREEN + "Added " + amount + " coins to " + target.getName() + "'s balance.");
            } else if (action.equals("remove")) {
                addCoins(target.getUniqueId(), -amount);
                p.sendMessage(ChatColor.GREEN + "Removed " + amount + " coins from " + target.getName() + "'s balance.");
            } else {
                p.sendMessage(ChatColor.RED + "Unknown action. Use add/remove/reset.");
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("economy")) {
            if (!p.hasPermission("dmt.admin")) {
                p.sendMessage(ChatColor.RED + "You do not have permission to manage the economy.");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
                dataConfig.set("coins", null);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Economy reset: all player balances cleared.");
                return true;
            }
            p.sendMessage(ChatColor.RED + "Usage: /economy reset");
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
            // command also supports creating a shop listing
            if (args.length >= 4 && args[0].equalsIgnoreCase("sell")) {
                String item = args[1];
                int amt;
                long price;
                try { amt = Integer.parseInt(args[2]); price = Long.parseLong(args[3]); }
                catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Usage: /shop sell <item> <amount> <price>"); return true; }
                String id = String.valueOf(System.currentTimeMillis());
                String path = "shops." + id;
                dataConfig.set(path + ".item", item.toUpperCase());
                dataConfig.set(path + ".amount", amt);
                dataConfig.set(path + ".price", price);
                dataConfig.set(path + ".owner", p.getUniqueId().toString());
                dataConfig.set(path + ".ownerName", p.getName());
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Shop listing created! " + amt + "x " + item + " for " + price + " coins.");
                return true;
            }
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
                Set<String> validCategories = new HashSet<>(Arrays.asList("bug", "griefing", "chat", "item_loss", "pvp", "other"));
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
            Player targetPlayer = Bukkit.getPlayer(args[0]);
            if (targetPlayer == null) { p.sendMessage(ChatColor.RED + "Player not found."); return true; }
            if (targetPlayer.equals(p)) { p.sendMessage(ChatColor.RED + "Can't duel yourself."); return true; }
            int wager = 0;
            if (args.length > 1) {
                try { wager = Integer.parseInt(args[1]); } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Invalid wager amount."); return true; }
                if (wager < 0) wager = 0;
                if (wager > 0 && p.getLevel() < wager) { p.sendMessage(ChatColor.RED + "Not enough XP for wager."); return true; }
            }
            duelRequests.put(p.getUniqueId(), targetPlayer.getUniqueId());
            if (wager > 0) duelWagers.put(p.getUniqueId(), wager);
            p.sendMessage(ChatColor.GREEN + "⚔ Duel request sent to " + targetPlayer.getName() + (wager > 0 ? " with " + wager + " XP wager" : ""));
            targetPlayer.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.YELLOW + p.getName() + ChatColor.GOLD + " challenged you to a duel!" + (wager > 0 ? " Wager: " + ChatColor.AQUA + wager + " XP" : ""));
            targetPlayer.sendMessage(ChatColor.GREEN + "/duel accept" + ChatColor.WHITE + " or " + ChatColor.RED + "/duel deny");
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
        p.sendMessage(ChatColor.GREEN + "/balance" + ChatColor.WHITE + " - Show XP level & coin balance");
        p.sendMessage(ChatColor.GREEN + "/shop" + ChatColor.WHITE + " - Browse player shops");
        p.sendMessage(ChatColor.GREEN + "/shop sell <item> <amount> <price>" + ChatColor.WHITE + " - Sell an item (coins)");
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
        p.sendMessage(ChatColor.GRAY + "Use the player menu to access your custom enchantments (unlocked via quests)");

        if (p.hasPermission("dmt.admin")) {
            p.sendMessage("");
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Admin Commands:");
            p.sendMessage(ChatColor.AQUA + "/dmt menu" + ChatColor.WHITE + " - Opens the management GUI");
            p.sendMessage(ChatColor.AQUA + "/dmt punish <player> <duration>" + ChatColor.WHITE + " - Punish a player");
            p.sendMessage(ChatColor.AQUA + "/dmt setpunishloc" + ChatColor.WHITE + " - Set punishment location");
            p.sendMessage(ChatColor.AQUA + "/dmt setjailloc" + ChatColor.WHITE + " - Set jail location");
            p.sendMessage(ChatColor.AQUA + "/dmt tpjail" + ChatColor.WHITE + " - Teleport to jail");
            p.sendMessage(ChatColor.AQUA + "/dmt tp world <name>" + ChatColor.WHITE + " - Teleport to another world");
            p.sendMessage(ChatColor.AQUA + "/dmt summon <name>" + ChatColor.WHITE + " - Spawn a configurable NPC (shop/teleport)");
            p.sendMessage(ChatColor.AQUA + "/dmt list npcs" + ChatColor.WHITE + " - List available NPC skins (from minecraft.tools)");
            p.sendMessage(ChatColor.AQUA + "/dmt npc add <username>" + ChatColor.WHITE + " - Add a skin to the library (uses minecraft.tools)");
            p.sendMessage(ChatColor.AQUA + "/dmt npc remove <username>" + ChatColor.WHITE + " - Remove a skin from the library");
            p.sendMessage(ChatColor.AQUA + "/dmt npc summon <username>" + ChatColor.WHITE + " - Spawn an NPC from the library");
            p.sendMessage(ChatColor.AQUA + "/dmt sethub" + ChatColor.WHITE + " - Set current location as hub");
            p.sendMessage(ChatColor.AQUA + "/hub" + ChatColor.WHITE + " - Teleport to hub world");
            p.sendMessage(ChatColor.AQUA + "/dmt setserverspawn" + ChatColor.WHITE + " - Set server spawn (joins will teleport here)");
            p.sendMessage(ChatColor.AQUA + "/dmt clearserverspawn" + ChatColor.WHITE + " - Clear server spawn setting");
            p.sendMessage(ChatColor.AQUA + "/dmt spawnlast" + ChatColor.WHITE + " - List stored world locations");
            p.sendMessage(ChatColor.AQUA + "/dmt spawnlast tp <world>" + ChatColor.WHITE + " - Teleport to stored last location");
            p.sendMessage(ChatColor.AQUA + "/dmt world <world> <lock|unlock>" + ChatColor.WHITE + " - Lock or unlock a world");
            p.sendMessage(ChatColor.AQUA + "/dmt antlag <on|off|now>" + ChatColor.WHITE + " - Enable/disable or run anti-lag cleanup (drops)");
            p.sendMessage(ChatColor.AQUA + "/dmt spawn reset" + ChatColor.WHITE + " - Reset all spawn restrictions");
            p.sendMessage(ChatColor.AQUA + "/dmt spawn <view|list>" + ChatColor.WHITE + " - View disabled mob spawns");
            p.sendMessage(ChatColor.AQUA + "/dmt spawn <mob> <true|false>" + ChatColor.WHITE + " - Enable/disable mob spawning");
            p.sendMessage(ChatColor.AQUA + "/dmt killall hostile" + ChatColor.WHITE + " - Kill all hostile mobs");
            p.sendMessage(ChatColor.AQUA + "/dmt gencloud <width> <length> <depth>" + ChatColor.WHITE + " - Generate a nearby cloud layer");

            p.sendMessage(ChatColor.AQUA + "/balance <player> add <amount>" + ChatColor.WHITE + " - Add Drowsy coins to a player");
            p.sendMessage(ChatColor.AQUA + "/balance <player> remove <amount>" + ChatColor.WHITE + " - Remove Drowsy coins from a player");
            p.sendMessage(ChatColor.AQUA + "/balance <player> reset" + ChatColor.WHITE + " - Reset a player's Drowsy coins");
            p.sendMessage(ChatColor.AQUA + "/economy reset" + ChatColor.WHITE + " - Reset all player balances");
        }

        if (isStaffTagged(p) || p.hasPermission("dmt.admin")) {
            p.sendMessage("");
            p.sendMessage(ChatColor.AQUA + "/dmt staff list" + ChatColor.WHITE + " - List online staff (Helper/Moderator/Admin/Manager/Owner/Head_Admin)");
        }
    }

    private void sendDmtSubcommandHelp(Player p, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "rank":
                p.sendMessage(ChatColor.AQUA + "/dmt rank create <rank>" + ChatColor.WHITE + " - Create a rank");
                p.sendMessage(ChatColor.AQUA + "/dmt rank remove <rank>" + ChatColor.WHITE + " - Remove a rank");
                p.sendMessage(ChatColor.AQUA + "/dmt rank list" + ChatColor.WHITE + " - List ranks");
                p.sendMessage(ChatColor.AQUA + "/dmt rank info <rank>" + ChatColor.WHITE + " - Show rank details");
                p.sendMessage(ChatColor.AQUA + "/dmt rank <rank> add <player>" + ChatColor.WHITE + " - Add a player to a rank");
                p.sendMessage(ChatColor.AQUA + "/dmt rank <rank> addprefix <prefix>" + ChatColor.WHITE + " - Add a prefix to a rank");
                p.sendMessage(ChatColor.AQUA + "/dmt rank <rank> addperm <permission>" + ChatColor.WHITE + " - Add permission to a rank");
                break;
            case "world":
                p.sendMessage(ChatColor.AQUA + "/dmt world <world> lock" + ChatColor.WHITE + " - Lock a world (prevents /hub and /dmt tp world)");
                p.sendMessage(ChatColor.AQUA + "/dmt world <world> unlock" + ChatColor.WHITE + " - Unlock a world");
                break;
            case "tp":
                p.sendMessage(ChatColor.AQUA + "/dmt tp world <name>" + ChatColor.WHITE + " - Teleport to a world (saves last location)");
                break;
            case "spawnlast":
                p.sendMessage(ChatColor.AQUA + "/dmt spawnlast" + ChatColor.WHITE + " - List your saved last locations");
                p.sendMessage(ChatColor.AQUA + "/dmt spawnlast tp <world>" + ChatColor.WHITE + " - Teleport to a saved location in that world");
                break;
            case "spawn":
                p.sendMessage(ChatColor.AQUA + "/dmt spawn view" + ChatColor.WHITE + " - View disabled mob spawns");
                p.sendMessage(ChatColor.AQUA + "/dmt spawn list" + ChatColor.WHITE + " - Alias for view");
                p.sendMessage(ChatColor.AQUA + "/dmt spawn reset" + ChatColor.WHITE + " - Reset all spawn restrictions");
                p.sendMessage(ChatColor.AQUA + "/dmt spawn <mob> <true|false>" + ChatColor.WHITE + " - Enable/disable mob spawning");
                break;
            case "antlag":
                p.sendMessage(ChatColor.AQUA + "/dmt antlag on" + ChatColor.WHITE + " - Enable anti-lag cleanup");
                p.sendMessage(ChatColor.AQUA + "/dmt antlag off" + ChatColor.WHITE + " - Disable anti-lag cleanup");
                p.sendMessage(ChatColor.AQUA + "/dmt antlag now" + ChatColor.WHITE + " - Run cleanup immediately");
                break;
            case "killall":
                p.sendMessage(ChatColor.AQUA + "/dmt killall hostile" + ChatColor.WHITE + " - Kill all hostile mobs");
                break;
            case "gencloud":
                p.sendMessage(ChatColor.AQUA + "/dmt gencloud <width> <length> <depth>" + ChatColor.WHITE + " - Generate a nearby cloud layer");
                break;
            case "npc":
                p.sendMessage(ChatColor.AQUA + "/dmt npc list" + ChatColor.WHITE + " - List NPC skins");
                p.sendMessage(ChatColor.AQUA + "/dmt npc add <username>" + ChatColor.WHITE + " - Add an NPC skin");
                p.sendMessage(ChatColor.AQUA + "/dmt npc remove <username>" + ChatColor.WHITE + " - Remove an NPC skin");
                p.sendMessage(ChatColor.AQUA + "/dmt npc summon <username>" + ChatColor.WHITE + " - Spawn an NPC");
                break;
            case "summon":
                p.sendMessage(ChatColor.AQUA + "/dmt summon <name>" + ChatColor.WHITE + " - Spawn a configurable NPC");
                break;
            case "sethub":
                p.sendMessage(ChatColor.AQUA + "/dmt sethub" + ChatColor.WHITE + " - Set current location as hub");
                break;
            case "unsethub":
                p.sendMessage(ChatColor.AQUA + "/dmt unsethub" + ChatColor.WHITE + " - Remove the saved hub location");
                break;
            case "setserverspawn":
                p.sendMessage(ChatColor.AQUA + "/dmt setserverspawn" + ChatColor.WHITE + " - Set the server spawn location");
                break;
            case "clearserverspawn":
                p.sendMessage(ChatColor.AQUA + "/dmt clearserverspawn" + ChatColor.WHITE + " - Clear the server spawn location");
                break;
            default:
                p.sendMessage(ChatColor.RED + "Unknown subcommand. Use /dmt help for full list.");
        }
    }

    // tab completion support (registers itself as TabCompleter)
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("dmt")) {
            if (args.length == 1) {
                List<String> subs = Arrays.asList(
                    "help","setpunishloc","setjailloc","tpjail","punish","menu","tp","world","summon","list","npc","sethub","unsethub",
                    "setserverspawn","clearserverspawn","spawnlast","spawn","killall","gencloud","rank","antlag","documentation","docs"
                );
                String start = args[0].toLowerCase();
                List<String> out = new ArrayList<>();
                for (String s : subs) {
                    if (s.startsWith(start)) out.add(s);
                }
                return out;
            }

            // complete the second argument for multi-word subcommands
            if (args.length == 2) {
                String sub = args[0].toLowerCase();
                if (sub.equals("punish") || (sub.equals("tp") && args[1].isEmpty())) {
                    // suggest online player names (punish) or world (tp) when starting
                    List<String> names = new ArrayList<>();
                    for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
                    String start = args[1].toLowerCase();
                    names.removeIf(n -> !n.toLowerCase().startsWith(start));
                    return names;
                }
                if (sub.equals("tp")) {
                    if (args[1].isEmpty() || "world".startsWith(args[1].toLowerCase())) {
                        return Collections.singletonList("world");
                    }
                    return Collections.emptyList();
                }
                if (sub.equals("world")) {
                    List<String> worldNames = new ArrayList<>();
                    for (World w : Bukkit.getWorlds()) worldNames.add(w.getName());
                    String start = args[1].toLowerCase();
                    worldNames.removeIf(n -> !n.toLowerCase().startsWith(start));
                    return worldNames;
                }
                if (sub.equals("spawnlast")) {
                    List<String> out = new ArrayList<>(Arrays.asList("tp"));
                    String start = args[1].toLowerCase();
                    out.removeIf(s -> !s.startsWith(start));
                    return out;
                }
                if (sub.equals("npc")) {
                    List<String> subs = Arrays.asList("list", "add", "remove", "summon");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs) {
                        if (s.startsWith(start)) out.add(s);
                    }
                    return out;
                }
                if (sub.equals("spawn")) {
                    List<String> subs = Arrays.asList("view", "list", "reset");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs) {
                        if (s.startsWith(start)) out.add(s);
                    }
                    return out;
                }
                if (sub.equals("killall")) {
                    List<String> subs = Arrays.asList("hostile");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs) {
                        if (s.startsWith(start)) out.add(s);
                    }
                    return out;
                }
                if (sub.equals("antlag")) {
                    List<String> subs = Arrays.asList("on", "off", "now");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs) {
                        if (s.startsWith(start)) out.add(s);
                    }
                    return out;
                }
            }

            // complete third argument where applicable
            if (args.length == 3) {
                String sub = args[0].toLowerCase();
                if (sub.equals("tp") && args[1].equalsIgnoreCase("world")) {
                    List<String> worldNames = new ArrayList<>();
                    for (World w : Bukkit.getWorlds()) worldNames.add(w.getName());
                    String start = args[2].toLowerCase();
                    worldNames.removeIf(n -> !n.toLowerCase().startsWith(start));
                    return worldNames;
                }
                if (sub.equals("world")) {
                    List<String> out = Arrays.asList("lock", "unlock");
                    String start = args[2].toLowerCase();
                    List<String> filtered = new ArrayList<>();
                    for (String s : out) {
                        if (s.startsWith(start)) filtered.add(s);
                    }
                    return filtered;
                }
                if (sub.equals("spawnlast") && args[1].equalsIgnoreCase("tp")) {
                    String prefix = "last_location." + ((sender instanceof Player) ? ((Player) sender).getUniqueId() : "") + ".";
                    if (!dataConfig.contains(prefix)) return Collections.emptyList();
                    List<String> worlds = new ArrayList<>(dataConfig.getConfigurationSection(prefix).getKeys(false));
                    String start = args[2].toLowerCase();
                    worlds.removeIf(w -> !w.toLowerCase().startsWith(start));
                    return worlds;
                }
                if (sub.equals("npc") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("summon"))) {
                    List<String> names = new ArrayList<>();
                    for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
                    String start = args[2].toLowerCase();
                    names.removeIf(n -> !n.toLowerCase().startsWith(start));
                    return names;
                }
                if (sub.equals("spawn")) {
                    List<String> out = Arrays.asList("true", "false");
                    String start = args[2].toLowerCase();
                    List<String> filtered = new ArrayList<>();
                    for (String s : out) {
                        if (s.startsWith(start)) filtered.add(s);
                    }
                    return filtered;
                }
            }
        }

        if (cmd.getName().equalsIgnoreCase("balance")) {
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
                String start = args[0].toLowerCase();
                names.removeIf(n -> !n.toLowerCase().startsWith(start));
                return names;
            }
            if (args.length == 2) {
                return Arrays.asList("add", "remove", "reset");
            }
        }

        if (cmd.getName().equalsIgnoreCase("tpa") || cmd.getName().equalsIgnoreCase("duel")) {
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
                String start = args[0].toLowerCase();
                names.removeIf(n -> !n.toLowerCase().startsWith(start));
                return names;
            }
        }

        return Collections.emptyList();
    }

    // --- CHAT LISTENER (Broadcasts, Notes, Reasons) ---
    @EventHandler
    public void onChatReason(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!pendingActions.containsKey(p.getUniqueId())) return;

        e.setCancelled(true);
        PunishmentContext ctx = pendingActions.get(p.getUniqueId());
        if (ctx == null) return;
        String reason = e.getMessage();
        Player target = ctx.targetName != null ? Bukkit.getPlayer(ctx.targetName) : null;

        Bukkit.getScheduler().runTask(this, () -> {
            switch (ctx.type) {
                case ANNOUNCE:
                    Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[Announcement] " + ChatColor.RESET + ChatColor.YELLOW + reason);
                    p.sendMessage(ChatColor.AQUA + "Broadcast sent!");
                    pendingActions.remove(p.getUniqueId());
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
                    pendingActions.remove(p.getUniqueId());
                    break;
                case ENCHANT:
                    // reason contains the slot number
                    try {
                        int entered = Integer.parseInt(reason.trim());
                        // convert to zero-based index used by Bukkit
                        int slot = entered - 1;
                        if (slot < 0 || slot > 8) throw new NumberFormatException();
                        ItemStack item = p.getInventory().getItem(slot);
                        if (item == null || item.getType() == Material.AIR) {
                            p.sendMessage(ChatColor.RED + "No item in that hotbar slot.");
                        } else {
                            if (applyCustomEnchant(item, ctx.targetName)) {
                                p.sendMessage(ChatColor.GREEN + "Enchantment " + ctx.targetName + " applied to slot " + entered + "!");
                            } else {
                                p.sendMessage(ChatColor.YELLOW + "Could not apply enchant (maybe already present).");
                            }
                        }
                    } catch (NumberFormatException ex) {
                        p.sendMessage(ChatColor.RED + "Invalid slot number. Enter a value 1-9.");
                    }
                    pendingActions.remove(p.getUniqueId());
                    break;
                case SET_WARP:
                    String warpName = reason.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
                    if (warpName.isEmpty()) {
                        p.sendMessage(ChatColor.RED + "Invalid warp name. Use letters, numbers, underscore, or dash.");
                        break;
                    }
                    dataConfig.set("warps." + warpName + ".world", p.getWorld().getName());
                    dataConfig.set("warps." + warpName + ".x", p.getLocation().getX());
                    dataConfig.set("warps." + warpName + ".y", p.getLocation().getY());
                    dataConfig.set("warps." + warpName + ".z", p.getLocation().getZ());
                    dataConfig.set("warps." + warpName + ".yaw", p.getLocation().getYaw());
                    dataConfig.set("warps." + warpName + ".pitch", p.getLocation().getPitch());
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Warp '" + warpName + "' set! Use the Warps menu to teleport.");
                    pendingActions.remove(p.getUniqueId());
                    break;
                case HOLOGRAM:
                    if (ctx.expectedLines <= 0) {
                        int lines;
                        try {
                            lines = Integer.parseInt(reason.trim());
                        } catch (NumberFormatException ex) {
                            p.sendMessage(ChatColor.RED + "Please enter a valid number of lines (1-10).");
                            break;
                        }
                        if (lines < 1 || lines > 10) {
                            p.sendMessage(ChatColor.RED + "Please enter a number between 1 and 10.");
                            break;
                        }
                        ctx.expectedLines = lines;
                        ctx.currentLine = 0;
                        ctx.lines = new ArrayList<>();
                        p.sendMessage(ChatColor.AQUA + "Enter text for line 1 (use § for colors/formatting):");
                        break;
                    }
                    ctx.lines.add(reason);
                    ctx.currentLine++;
                    if (ctx.currentLine < ctx.expectedLines) {
                        p.sendMessage(ChatColor.AQUA + "Enter text for line " + (ctx.currentLine + 1) + " (use § for colors/formatting):");
                        break;
                    }
                    spawnHologram(p, ctx.lines);
                    p.sendMessage(ChatColor.GREEN + "Hologram created!");
                    pendingActions.remove(p.getUniqueId());
                    break;
                case SUMMON_NPC:
                    // Manage NPC configuration (shop / teleport)
                    String npcIdStr = ctx.targetName;
                    if (npcIdStr == null) {
                        pendingActions.remove(p.getUniqueId());
                        break;
                    }
                    String base = "summons." + npcIdStr;
                    String mode = dataConfig.getString(base + ".type", "");
                    String input = reason.trim();

                    // Initial choice: shop or teleport
                    if (mode.isEmpty()) {
                        if (input.equalsIgnoreCase("shop") || input.equalsIgnoreCase("admin shop")) {
                            dataConfig.set(base + ".type", "shop");
                            dataConfig.set(base + ".shop_items", new ArrayList<>());
                            saveDataFile();
                            ctx.lines = new ArrayList<>();
                            p.sendMessage(ChatColor.AQUA + "Enter item to sell in the format: material x<amount> = <price> (e.g. grass_block x64 = 24)");
                            p.sendMessage(ChatColor.AQUA + "Type 'done' when finished.");
                        } else if (input.equalsIgnoreCase("teleport")) {
                            dataConfig.set(base + ".type", "teleport");
                            saveDataFile();
                            p.sendMessage(ChatColor.AQUA + "Where should players be teleported? Format: <world> [x y z] (coordinates optional)");
                        } else {
                            p.sendMessage(ChatColor.RED + "Please type either 'shop' or 'teleport'.");
                        }
                        break;
                    }

                    // Shop configuration input
                    if (mode.equals("shop")) {
                        if (input.equalsIgnoreCase("done") || input.equalsIgnoreCase("no")) {
                            p.sendMessage(ChatColor.GREEN + "Shop configuration saved.");
                            pendingActions.remove(p.getUniqueId());
                            break;
                        }
                        String[] parts = input.split("=");
                        if (parts.length < 2) {
                            p.sendMessage(ChatColor.RED + "Invalid format. Use: material x<amount> = <price>");
                            break;
                        }
                        String left = parts[0].trim();
                        String right = parts[1].trim().split(" ")[0];
                        int price;
                        try {
                            price = Integer.parseInt(right);
                        } catch (Exception ex) {
                            p.sendMessage(ChatColor.RED + "Invalid price. Use a number.");
                            break;
                        }
                        String[] leftParts = left.split("\\s+");
                        if (leftParts.length == 0) {
                            p.sendMessage(ChatColor.RED + "Invalid item format.");
                            break;
                        }
                        String matName = leftParts[0].toUpperCase();
                        int amount = 1;
                        if (leftParts.length > 1) {
                            try { amount = Integer.parseInt(leftParts[1].replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                        }
                        Material mat;
                        try { mat = Material.valueOf(matName); } catch (Exception ex) {
                            p.sendMessage(ChatColor.RED + "Unknown material: " + matName);
                            break;
                        }
                        String entry = mat.name() + ":" + amount + ":" + price;
                        ctx.lines.add(entry);
                        dataConfig.set(base + ".shop_items", ctx.lines);
                        saveDataFile();
                        p.sendMessage(ChatColor.GREEN + "Added " + amount + "x " + mat.name() + " for " + price + " coins.");
                        p.sendMessage(ChatColor.AQUA + "Add another item or type 'done' to finish.");
                        break;
                    }

                    // Teleport configuration
                    if (mode.equals("teleport")) {
                        String[] parts = input.split("\\s+");
                        if (parts.length < 1) {
                            p.sendMessage(ChatColor.RED + "Please provide a world name.");
                            break;
                        }
                        String worldName = parts[0];
                        dataConfig.set(base + ".teleport.world", worldName);
                        if (parts.length >= 4) {
                            try {
                                double x = Double.parseDouble(parts[1]);
                                double y = Double.parseDouble(parts[2]);
                                double z = Double.parseDouble(parts[3]);
                                dataConfig.set(base + ".teleport.x", x);
                                dataConfig.set(base + ".teleport.y", y);
                                dataConfig.set(base + ".teleport.z", z);
                            } catch (Exception ex) {
                                p.sendMessage(ChatColor.RED + "Invalid coordinates. Use: <world> x y z");
                                break;
                            }
                        }
                        saveDataFile();
                        p.sendMessage(ChatColor.GREEN + "NPC teleport destination set. Interaction is now configured.");
                        pendingActions.remove(p.getUniqueId());
                        break;
                    }
                case WORLD_CREATE_NAME:
                    String type = ctx.targetName;
                    String worldName = reason.replaceAll("[^A-Za-z0-9_\\-]", "");
                    if (worldName.isEmpty()) {
                        p.sendMessage(ChatColor.RED + "Invalid world name.");
                        pendingActions.remove(p.getUniqueId());
                        break;
                    }
                    Bukkit.getScheduler().runTask(this, () -> {
                        WorldCreator wc = new WorldCreator(worldName);
                        if ("flat".equals(type)) {
                            wc.type(WorldType.FLAT);
                        } else if ("void".equals(type)) {
                            // Custom void world generator that creates a solid grass baseplate and a smooth cloud layer
                            wc.generator(new org.bukkit.generator.ChunkGenerator() {
                                private static final int BASE_Y = 64;
                                private static final int BASE_RADIUS = 40;
                                private static final int CLOUD_Y = 120;
                                private static final int CLOUD_RADIUS = 40;
                                private static final int CLOUD_CORE_RADIUS = 30;

                                @Override
                                public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, org.bukkit.generator.ChunkGenerator.BiomeGrid biome) {
                                    ChunkData data = createChunkData(world);
                                    int baseStartX = chunkX * 16;
                                    int baseStartZ = chunkZ * 16;

                                    // Precompute cloud puff centers for this chunk (deterministic by world + chunk coords)
                                    Random cloudRand = new Random(world.getSeed() ^ ((long)chunkX * 341873128712L) ^ ((long)chunkZ * 132897987541L));
                                    int puffCount = 3;
                                    int[][] puffs = new int[puffCount][3]; // centerX, centerZ, radius
                                    for (int i = 0; i < puffCount; i++) {
                                        double angle = cloudRand.nextDouble() * Math.PI * 2;
                                        double dist = cloudRand.nextDouble() * (CLOUD_RADIUS * 0.7);
                                        int centerX = (int) Math.round(Math.cos(angle) * dist);
                                        int centerZ = (int) Math.round(Math.sin(angle) * dist);
                                        int radius = 8 + cloudRand.nextInt(6);
                                        puffs[i][0] = centerX;
                                        puffs[i][1] = centerZ;
                                        puffs[i][2] = radius;
                                    }

                                    for (int dx = 0; dx < 16; dx++) {
                                        int wx = baseStartX + dx;
                                        for (int dz = 0; dz < 16; dz++) {
                                            int wz = baseStartZ + dz;

                                            // Grass baseplate with a white wool outline (no holes)
                                            if (Math.abs(wx) <= BASE_RADIUS && Math.abs(wz) <= BASE_RADIUS) {
                                                boolean onBorder = Math.abs(wx) == BASE_RADIUS || Math.abs(wz) == BASE_RADIUS;
                                                data.setBlock(dx, BASE_Y, dz, onBorder ? Material.WHITE_WOOL : Material.GRASS_BLOCK);
                                            }

                                            // Cloud layer: cartoony, solid puffs that fade at the edges (no holes)
                                            double bestDist = Double.MAX_VALUE;
                                            int bestRadius = 0;
                                            for (int[] puff : puffs) {
                                                int cx = puff[0];
                                                int cz = puff[1];
                                                int r = puff[2];
                                                double d = Math.sqrt((double)(wx - cx) * (wx - cx) + (double)(wz - cz) * (wz - cz));
                                                if (d < bestDist && d <= r) {
                                                    bestDist = d;
                                                    bestRadius = r;
                                                }
                                            }
                                            if (bestDist <= bestRadius) {
                                                Material cloudMat = bestDist <= (bestRadius - 2) ? Material.WHITE_WOOL : Material.WHITE_STAINED_GLASS;
                                                // make cloud thicker for a cartoony volume
                                                data.setBlock(dx, CLOUD_Y, dz, cloudMat);
                                                data.setBlock(dx, CLOUD_Y + 1, dz, Material.WHITE_WOOL);
                                            }
                                        }
                                    }
                                    return data;
                                }
                            });
                            wc.generateStructures(false);
                        }
                        World created = null;
                        try {
                            created = Bukkit.createWorld(wc);
                        } catch (Exception ex) {
                            p.sendMessage(ChatColor.RED + "Failed to create world: " + ex.getMessage());
                            getLogger().log(java.util.logging.Level.SEVERE, "Failed to create world", ex);
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
                    pendingActions.remove(p.getUniqueId());
                    break;
                case WARN: 
                    if (target != null) target.sendMessage(ChatColor.RED + "WARNING: " + ChatColor.YELLOW + reason); 
                    if (ctx.targetName != null) addWarning(Bukkit.getOfflinePlayer(ctx.targetName).getUniqueId(), reason);
                    logAction(p.getName(), "warned", ctx.targetName + " (" + reason + ")");
                    addChatLog("System", "[WARNING] " + ctx.targetName + ": " + reason);
                    fireDiscordEvent("warns", "Player Warned", "**" + ctx.targetName + "** was warned by **" + p.getName() + "**.\nReason: " + reason, 0xf1c40f, ctx.targetName);
                    pendingActions.remove(p.getUniqueId());
                    break;
                case KICK: 
                    if (target != null) target.kickPlayer(ChatColor.RED + "Kicked: " + reason); 
                    logAction(p.getName(), "kicked", ctx.targetName + " (" + reason + ")");
                    addChatLog("System", "[KICK] " + ctx.targetName + ": " + reason);
                    pendingActions.remove(p.getUniqueId());
                    break;
                case BAN: 
                    Bukkit.getBanList(BanList.Type.NAME).addBan(ctx.targetName, reason, null, p.getName());
                    if (target != null) target.kickPlayer(ChatColor.RED + "Banned: " + reason);
                    logAction(p.getName(), "banned", ctx.targetName + " (" + reason + ")");
                    addChatLog("System", "[BAN] " + ctx.targetName + ": " + reason);
                    fireDiscordEvent("bans", "Player Banned", "**" + ctx.targetName + "** was banned by **" + p.getName() + "**.\nReason: " + reason, 0xe74c3c, ctx.targetName);
                    pendingActions.remove(p.getUniqueId());
                    break;
                case TICKET_RESPOND:
                    addTicketResponse(Integer.parseInt(ctx.targetName), p.getName(), reason);
                    p.sendMessage(ChatColor.GREEN + "Response added to ticket #" + ctx.targetName + ".");
                    pendingActions.remove(p.getUniqueId());
                    break;
                case TICKET_RESOLVE:
                    resolveTicket(Integer.parseInt(ctx.targetName), reason);
                    p.sendMessage(ChatColor.GREEN + "Ticket #" + ctx.targetName + " resolved: " + reason);
                    pendingActions.remove(p.getUniqueId());
                    break;
                case TICKET_CREATE:
                    String cat = ctx.targetName != null ? ctx.targetName : "";
                    if (!cat.isEmpty()) {
                        createTicket(p, cat + " " + reason);
                    } else {
                        createTicket(p, reason);
                    }
                    pendingActions.remove(p.getUniqueId());
                    break;
                case APPEAL_CREATE:
                    String acat = ctx.targetName != null ? ctx.targetName : "";
                    if (!acat.isEmpty()) {
                        createAppeal(p, acat + " " + reason);
                    } else {
                        createAppeal(p, reason);
                    }
                    pendingActions.remove(p.getUniqueId());
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
                    pendingActions.remove(p.getUniqueId());
                    break;
            }
            if (ctx.targetName != null && ctx.type != ActionType.ADD_NOTE && ctx.type != ActionType.HOLOGRAM) {
                p.sendMessage(ChatColor.AQUA + "Action applied to " + ctx.targetName);
            }
        });
    }

    // Chat prefix handling (applies rank prefixes to chat messages)
    @EventHandler(priority = EventPriority.HIGH)
    public void onChatPrefix(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (pendingActions.containsKey(p.getUniqueId())) return; // keep existing flow

        String rank = getPlayerRank(p.getUniqueId());
        if (rank == null) return;

        String prefix = dataConfig.getString(RANKS_PATH + "." + rank + ".prefix", "");
        if (prefix == null || prefix.isEmpty()) return;

        prefix = ChatColor.translateAlternateColorCodes('&', prefix);
        // %1$s = player name, %2$s = message
        e.setFormat(prefix + "%1$s: %2$s");
    }

    // --- ticket helpers ---
    private String getWebPanelUrl() {
        return "http://" + (Bukkit.getServer().getIp().isEmpty() ? "localhost" : Bukkit.getServer().getIp()) + ":8091/tickets";
    }

    private String getDocumentationUrl() {
        return "http://" + (Bukkit.getServer().getIp().isEmpty() ? "localhost" : Bukkit.getServer().getIp()) + ":8091/docs.pdf";
    }

    private File generateDocumentationPdf() throws IOException {
        File out = new File(getDataFolder(), "docs.pdf");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("docs.html")) {
            if (in == null) throw new IOException("docs.html not found in resources");
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (OutputStream os = new FileOutputStream(out)) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.withHtmlContent(html, null);
                builder.toStream(os);
                builder.run();
            }
        }
        return out;
    }

    private String getActiveEvent() {
        ConfigurationSection section = dataConfig.getConfigurationSection("events.active");
        if (section == null) return null;
        for (String name : section.getKeys(false)) {
            return name; // return first active event
        }
        return null;
    }

    // --- coin economy helpers ---
    private long getCoins(UUID uuid) {
        String key = "coins." + uuid;
        long amount = dataConfig.getLong(key, Long.MIN_VALUE);
        if (amount != Long.MIN_VALUE) {
            return amount;
        }
        // Legacy support: some installs may store under drowsy_coins
        String legacyKey = "drowsy_coins." + uuid;
        amount = dataConfig.getLong(legacyKey, Long.MIN_VALUE);
        if (amount != Long.MIN_VALUE) {
            // migrate to new key
            dataConfig.set(key, amount);
            dataConfig.set(legacyKey, null);
            saveDataFile();
            return amount;
        }
        return 0;
    }
    private void setCoins(UUID uuid, long amount) {
        dataConfig.set("coins." + uuid, amount);
        // Keep legacy key in sync for older config readers
        dataConfig.set("drowsy_coins." + uuid, amount);
        saveDataFile();
    }
    private void addCoins(UUID uuid, long delta) {
        long cur = getCoins(uuid);
        setCoins(uuid, cur + delta);
    }

    private String eventKeyFromDisplay(String disp) {
        String d = disp.toLowerCase();
        String result;
        switch (d) {
            case "valentines":
                result = "valentine";
                break;
            case "christmas":
                result = "christmas";
                break;
            case "new year":
                result = "newyear";
                break;
            case "halloween":
                result = "halloween";
                break;
            default:
                result = d.replace(" ", "");
                break;
        }
        return result;
    }

    private void createTicket(Player p, String text) {
        long now = System.currentTimeMillis();
        Long lastTicket = ticketCooldowns.get(p.getUniqueId());
        if (lastTicket != null && (now - lastTicket) < 60000) {
            long remaining = (60000 - (now - lastTicket)) / 1000;
            p.sendMessage(ChatColor.RED + "Please wait " + remaining + "s before creating another ticket.");
            return;
        }
        Set<String> validCategories = new HashSet<>(Arrays.asList("bug", "griefing", "chat", "item_loss", "pvp", "other"));
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
        int id = dataConfig.getInt("tickets.next_id", 1);
        String path = "tickets." + id;
        dataConfig.set(path + ".player", p.getName());
        dataConfig.set(path + ".uuid", p.getUniqueId().toString());
        dataConfig.set(path + ".message", message);
        dataConfig.set(path + ".status", "open");
        dataConfig.set(path + ".priority", "medium");
        dataConfig.set(path + ".category", category);
        dataConfig.set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        dataConfig.set(path + ".world", p.getWorld().getName());
        dataConfig.set(path + ".x", p.getLocation().getBlockX());
        dataConfig.set(path + ".y", p.getLocation().getBlockY());
        dataConfig.set(path + ".z", p.getLocation().getBlockZ());
        dataConfig.set("tickets.next_id", id + 1);
        saveDataFile();
        ticketCooldowns.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.GREEN + "Ticket #" + id + " created (" + category + "). Staff will review it soon.");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("realmtool.admin")) {
                staff.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + p.getName() + " created ticket #" + id + ": " + ChatColor.GRAY + message);
            }
        }
        p.sendMessage(ChatColor.GREEN + "Your ticket has been submitted! A staff member will review it shortly.");
    }

    private void createAppeal(Player p, String text) {
        long now = System.currentTimeMillis();
        Long last = ticketCooldowns.get(p.getUniqueId()); // reuse same cooldown map
        if (last != null && (now - last) < 60000) {
            long remaining = (60000 - (now - last)) / 1000;
            p.sendMessage(ChatColor.RED + "Please wait " + remaining + "s before creating another appeal.");
            return;
        }
        Set<String> validCategories = new HashSet<>(Arrays.asList("bug", "griefing", "chat", "item_loss", "pvp", "other"));
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
        int id = dataConfig.getInt("appeals.next_id", 1);
        String path = "appeals." + id;
        dataConfig.set(path + ".player", p.getName());
        dataConfig.set(path + ".uuid", p.getUniqueId().toString());
        dataConfig.set(path + ".message", message);
        dataConfig.set(path + ".status", "open");
        dataConfig.set(path + ".priority", "medium");
        dataConfig.set(path + ".category", category);
        dataConfig.set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        dataConfig.set("appeals.next_id", id + 1);
        saveDataFile();
        ticketCooldowns.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.GREEN + "Appeal #" + id + " created (" + category + "). Staff will review it soon.");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("realmtool.admin")) {
                staff.sendMessage(ChatColor.GOLD + "[Appeals] " + ChatColor.YELLOW + p.getName() + " created appeal #" + id + ": " + ChatColor.GRAY + message);
            }
        }
        p.sendMessage(ChatColor.GREEN + "Your appeal has been submitted! A staff member will review it shortly.");
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

        // Helper Menu button
        if (isHelper(p)) {
            ItemStack helperMenu = new ItemStack(Material.BLUE_STAINED_GLASS);
            ItemMeta hmMeta = helperMenu.getItemMeta();
            hmMeta.setDisplayName(ChatColor.GREEN + "Helper Menu");
            hmMeta.setLore(Arrays.asList(ChatColor.GRAY + "Staff tools for Helpers"));
            helperMenu.setItemMeta(hmMeta);
            gui.setItem(13, helperMenu);
        }

        // Moderator Menu button
        if (isModerator(p)) {
            ItemStack modMenu = new ItemStack(Material.GOLD_BLOCK);
            ItemMeta mmMeta = modMenu.getItemMeta();
            mmMeta.setDisplayName(ChatColor.GOLD + "Moderator Menu");
            mmMeta.setLore(Arrays.asList(ChatColor.GRAY + "Staff tools for Moderators"));
            modMenu.setItemMeta(mmMeta);
            gui.setItem(15, modMenu);
        }

        // Admin Menu button
        if (p.isOp() || p.hasPermission("dmt.admin")) {
            ItemStack adminMenu = new ItemStack(Material.REDSTONE_BLOCK);
            ItemMeta amMeta = adminMenu.getItemMeta();
            amMeta.setDisplayName(ChatColor.RED + "Admin Menu");
            amMeta.setLore(Arrays.asList(ChatColor.GRAY + "Management tools"));
            adminMenu.setItemMeta(amMeta);
            gui.setItem(17, adminMenu);
        }
        
        // Close button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(26, back);
        
        p.openInventory(gui);
    }

    private void openHelperMenu(Player p) {
        setMenuOrigin(p, "helper");
        Inventory gui = Bukkit.createInventory(null, 27, GUI_HELPER_MENU);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);

        gui.setItem(10, createGuiItem(Material.PAPER, ChatColor.GOLD + "Tickets"));
        gui.setItem(11, createGuiItem(Material.CLOCK, ChatColor.AQUA + "Set Day"));
        gui.setItem(12, createGuiItem(Material.SUNFLOWER, ChatColor.YELLOW + "Clear Weather"));
        gui.setItem(13, createGuiItem(Material.GRASS_BLOCK, ChatColor.AQUA + "Survival Mode"));
        gui.setItem(14, createGuiItem(Material.ENDER_EYE, ChatColor.AQUA + "Spectator Mode"));
        gui.setItem(15, createGuiItem(Material.PLAYER_HEAD, ChatColor.AQUA + "Player Directory"));
        gui.setItem(16, createGuiItem(Material.IRON_BARS, ChatColor.RED + "Punished Players"));
        gui.setItem(17, createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME));
        gui.setItem(26, createGuiItem(Material.BARRIER, ChatColor.RED + "Back"));
        p.openInventory(gui);
    }

    private void openModeratorMenu(Player p) {
        setMenuOrigin(p, "moderator");
        Inventory gui = Bukkit.createInventory(null, 27, GUI_MODERATOR_MENU);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);

        gui.setItem(10, createGuiItem(Material.PAPER, ChatColor.GOLD + "Tickets"));
        gui.setItem(11, createGuiItem(Material.CLOCK, ChatColor.AQUA + "World Settings"));
        gui.setItem(12, createGuiItem(Material.GRASS_BLOCK, ChatColor.AQUA + "Survival Mode"));
        gui.setItem(13, createGuiItem(Material.ENDER_EYE, ChatColor.AQUA + "Spectator Mode"));
        gui.setItem(14, createGuiItem(Material.PLAYER_HEAD, ChatColor.AQUA + "Player Directory"));
        gui.setItem(15, createGuiItem(Material.IRON_BARS, ChatColor.RED + "Punished Players"));
        gui.setItem(16, createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME));
        gui.setItem(17, createGuiItem(Material.MAGMA_CREAM, ChatColor.RED + "Toggle Anti-Lag"));
        gui.setItem(18, createGuiItem(Material.TNT, ChatColor.GOLD + "Run Anti-Lag Now"));
        gui.setItem(26, createGuiItem(Material.BARRIER, ChatColor.RED + "Back"));
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
        
        // Tickets (open ticket menu)
        ItemStack tickets = new ItemStack(Material.PAPER);
        ItemMeta tiMeta = tickets.getItemMeta();
        tiMeta.setDisplayName(ChatColor.GOLD + "Tickets");
        tiMeta.setLore(Arrays.asList(ChatColor.GRAY + "Open ticket menu"));
        tickets.setItemMeta(tiMeta);
        gui.setItem(getNextGridSlot(), tickets);
        
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
        
        // Custom Enchantments (unlocks via quests)
        ItemStack ench = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta eMeta = ench.getItemMeta();
        eMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "Custom Enchantments");
        eMeta.setLore(Arrays.asList(ChatColor.GRAY + "View your unlocked enchants"));
        ench.setItemMeta(eMeta);
        gui.setItem(getNextGridSlot(), ench);
        
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
        
        // Row 1 (10-16): Stats (weather controls moved to World Settings)
        gui.setItem(10, createGuiItem(Material.EMERALD_BLOCK, ChatColor.AQUA + "Players: " + ChatColor.WHITE + nonPunished));
        gui.setItem(11, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + "Weather controls in World Settings"));
        gui.setItem(12, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + "Weather controls in World Settings"));
        gui.setItem(13, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + "Weather controls in World Settings"));
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
        gui.setItem(16, createAdminMenuItem(p, 16, Material.REDSTONE_BLOCK, ChatColor.RED + "Punished: " + ChatColor.WHITE + punished));

        // Category header: Player Tools
        gui.setItem(18, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "Player Tools"));

        // Row 2 (19-25): World/Player tools
        gui.setItem(19, createAdminMenuItem(p, 19, Material.CLOCK, ChatColor.AQUA + "World Settings"));
        gui.setItem(20, createAdminMenuItem(p, 20, Material.PLAYER_HEAD, ChatColor.AQUA + "Player Directory"));
        gui.setItem(21, createAdminMenuItem(p, 21, Material.GRASS_BLOCK, ChatColor.AQUA + "Creative Mode"));
        gui.setItem(22, createAdminMenuItem(p, 22, Material.BEEF, ChatColor.AQUA + "Survival Mode"));
        gui.setItem(23, createAdminMenuItem(p, 23, Material.ENDER_EYE, ChatColor.AQUA + "Spectator Mode"));
        gui.setItem(24, createAdminMenuItem(p, 24, Material.GOLDEN_APPLE, ChatColor.GOLD + "Heal & Feed All"));
        gui.setItem(25, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + ""));
        
        // Row 3 (28-34): Utilities
        gui.setItem(28, createAdminMenuItem(p, 28, Material.BLAZE_ROD, INSPECTOR_NAME));
        gui.setItem(29, createAdminMenuItem(p, 29, Material.WRITABLE_BOOK, ChatColor.GOLD + "Broadcast Message"));
        gui.setItem(30, createAdminMenuItem(p, 30, Material.PAPER, ChatColor.GOLD + "View Tickets"));
        gui.setItem(31, createAdminMenuItem(p, 31, Material.COMPASS, ChatColor.BLUE + "World Utilities"));
        gui.setItem(32, createAdminMenuItem(p, 32, Material.ENCHANTED_BOOK, ChatColor.LIGHT_PURPLE + "Events"));
        gui.setItem(33, createAdminMenuItem(p, 33, Material.PAPER, ChatColor.LIGHT_PURPLE + "Hologram Wand"));

        // Category header: Admin Tools
        gui.setItem(27, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "Admin Tools"));

        // Row 4 (43): Close button
        gui.setItem(43, createGuiItem(Material.REDSTONE, ChatColor.RED + "Close Menu"));

        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private ItemStack createAdminMenuItem(Player p, int slot, Material mat, String displayName) {
        // Operators see everything; non-ops only see buttons they have permission for.
        String neededPerm = getAdminMenuPermission(slot);
        if (neededPerm != null && !p.isOp() && !p.hasPermission(neededPerm)) {
            ItemStack disabled = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "No permission");
            ItemMeta meta = disabled.getItemMeta();
            if (meta != null) {
                meta.setLore(Arrays.asList(ChatColor.GRAY + "Requires: " + neededPerm));
                disabled.setItemMeta(meta);
            }
            return disabled;
        }

        return createGuiItem(mat, displayName);
    }

    private String getAdminMenuPermission(int slot) {
        switch (slot) {
            case 14:
                return "adminpanel.netherlock";
            case 15:
                return "adminpanel.endlock";
            case 19:
                return "adminpanel.worldsettings";
            case 20:
                return "adminpanel.playerdirectory";
            case 21:
                return "adminpanel.gamemode.creative";
            case 22:
                return "adminpanel.gamemode.survival";
            case 23:
                return "adminpanel.gamemode.spectator";
            case 24:
                return "adminpanel.heal";
            case 28:
                return "adminpanel.inspector";
            case 29:
                return "adminpanel.broadcast";
            case 30:
                return "adminpanel.tickets";
            case 31:
                return "adminpanel.worldutilities";
            case 32:
                return "adminpanel.events";
            case 33:
                return "adminpanel.hologram";
            case 16:
                return "adminpanel.punished";
            default:
                return null;
        }
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
        if (canBan(p)) {
            gui.setItem(21, createGuiItem(Material.BARRIER, ChatColor.RED + "Ban Player"));
        } else {
            gui.setItem(21, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "Ban Player (no permission)"));
        }
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

    private void openPlayerTicketMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 9, GUI_PLAYER_TICKET_MENU);
        gui.setItem(1, createGuiItem(Material.PAPER, ChatColor.GREEN + "Create Ticket"));
        gui.setItem(2, createGuiItem(Material.BOOK, ChatColor.GOLD + "View Your Tickets"));
        gui.setItem(6, createGuiItem(Material.PAPER, ChatColor.GREEN + "Create Appeal"));
        gui.setItem(7, createGuiItem(Material.BOOK, ChatColor.GOLD + "View Your Appeals"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openCategoryMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 9, GUI_TICKET_CATEGORY);
        gui.setItem(1, createGuiItem(Material.BOOK, ChatColor.YELLOW + "Bug"));
        gui.setItem(2, createGuiItem(Material.OAK_LOG, ChatColor.YELLOW + "Griefing"));
        gui.setItem(3, createGuiItem(Material.PINK_WOOL, ChatColor.YELLOW + "Chat"));
        gui.setItem(4, createGuiItem(Material.CHEST, ChatColor.YELLOW + "Item Loss"));
        gui.setItem(5, createGuiItem(Material.DIAMOND_SWORD, ChatColor.YELLOW + "PvP"));
        gui.setItem(6, createGuiItem(Material.MAP, ChatColor.YELLOW + "Other"));
        gui.setItem(8, createGuiItem(Material.BARRIER, ChatColor.RED + "Cancel"));
        p.openInventory(gui);
    }

    private void openAppealCategoryMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 9, GUI_APPEAL_CATEGORY);
        gui.setItem(1, createGuiItem(Material.BOOK, ChatColor.YELLOW + "Bug"));
        gui.setItem(2, createGuiItem(Material.OAK_LOG, ChatColor.YELLOW + "Griefing"));
        gui.setItem(3, createGuiItem(Material.PINK_WOOL, ChatColor.YELLOW + "Chat"));
        gui.setItem(4, createGuiItem(Material.CHEST, ChatColor.YELLOW + "Item Loss"));
        gui.setItem(5, createGuiItem(Material.DIAMOND_SWORD, ChatColor.YELLOW + "PvP"));
        gui.setItem(6, createGuiItem(Material.MAP, ChatColor.YELLOW + "Other"));
        gui.setItem(8, createGuiItem(Material.BARRIER, ChatColor.RED + "Cancel"));
        p.openInventory(gui);
    }

    private void openMyTicketsMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_TICKETS);
        resetGridSlots();
        if (dataConfig.contains("tickets")) {
            for (String key : dataConfig.getConfigurationSection("tickets").getKeys(false)) {
                if (key.equals("next_id")) continue;
                String player = dataConfig.getString("tickets." + key + ".player", "");
                if (!player.equalsIgnoreCase(p.getName())) continue;
                String priority = dataConfig.getString("tickets." + key + ".priority", "medium");
                Material mat;
                switch (priority) {
                    case "critical": mat = Material.REDSTONE_BLOCK; break;
                    case "high": mat = Material.ORANGE_WOOL; break;
                    case "low": mat = Material.LIME_WOOL; break;
                    default: mat = Material.YELLOW_WOOL; break;
                }
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.YELLOW + "Status: " + dataConfig.getString("tickets." + key + ".status", "open"));
                lore.add(ChatColor.GRAY + dataConfig.getString("tickets." + key + ".message", ""));
                ItemStack item = createGuiItem(mat, ChatColor.GOLD + "Ticket #" + key, lore);
                int slot = getNextGridSlot();
                if (slot != -1) gui.setItem(slot, item);
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openMyAppealsMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_APPEALS);
        resetGridSlots();
        if (dataConfig.contains("appeals")) {
            for (String key : dataConfig.getConfigurationSection("appeals").getKeys(false)) {
                if (key.equals("next_id")) continue;
                String player = dataConfig.getString("appeals." + key + ".player", "");
                if (!player.equalsIgnoreCase(p.getName())) continue;
                String priority = dataConfig.getString("appeals." + key + ".priority", "medium");
                Material mat;
                switch (priority) {
                    case "critical": mat = Material.REDSTONE_BLOCK; break;
                    case "high": mat = Material.ORANGE_WOOL; break;
                    case "low": mat = Material.LIME_WOOL; break;
                    default: mat = Material.YELLOW_WOOL; break;
                }
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.YELLOW + "Status: " + dataConfig.getString("appeals." + key + ".status", "open"));
                lore.add(ChatColor.GRAY + dataConfig.getString("appeals." + key + ".message", ""));
                ItemStack item = createGuiItem(mat, ChatColor.GOLD + "Appeal #" + key, lore);
                int slot = getNextGridSlot();
                if (slot != -1) gui.setItem(slot, item);
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openMyAppealDetailMenu(Player p, String appealId) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_MY_APPEAL_OPTIONS + appealId);
        gui.setItem(11, createGuiItem(Material.PAPER, ChatColor.GREEN + "View Appeal"));
        gui.setItem(13, createGuiItem(Material.REDSTONE_BLOCK, ChatColor.RED + "Delete Appeal"));
        ItemStack outcome = createGuiItem(Material.BOOK, ChatColor.AQUA + "View Appeal Outcome");
        ItemMeta om = outcome.getItemMeta();
        om.setLore(Arrays.asList(ChatColor.GRAY + "Submitted", ChatColor.GRAY + "Pending", ChatColor.GRAY + "Processed"));
        outcome.setItemMeta(om);
        gui.setItem(15, outcome);
        gui.setItem(26, createGuiItem(Material.BARRIER, ChatColor.RED + "Back"));
        p.openInventory(gui);
    }

    private void openEventListMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 9, GUI_EVENT_LIST);
        // events set
        gui.setItem(1, createGuiItem(Material.PINK_WOOL, ChatColor.LIGHT_PURPLE + "Valentines"));
        gui.setItem(2, createGuiItem(Material.SNOW_BLOCK, ChatColor.LIGHT_PURPLE + "Christmas"));
        gui.setItem(3, createGuiItem(Material.FIREWORK_ROCKET, ChatColor.LIGHT_PURPLE + "New Year"));
        gui.setItem(4, createGuiItem(Material.PUMPKIN, ChatColor.LIGHT_PURPLE + "Halloween"));
        gui.setItem(8, createGuiItem(Material.BARRIER, ChatColor.RED + "Back to Menu"));
        p.openInventory(gui);
    }

    private void openActiveEventMenu(Player p, String eventName) {
        Inventory gui = Bukkit.createInventory(null, 9, GUI_ACTIVE_EVENT);
        gui.setItem(3, createGuiItem(Material.COMPASS, ChatColor.AQUA + "Active: " + eventName));
        gui.setItem(5, createGuiItem(Material.REDSTONE_BLOCK, ChatColor.RED + "End Event"));
        gui.setItem(8, createGuiItem(Material.BARRIER, ChatColor.RED + "Back to Menu"));
        p.openInventory(gui);
    }

    private void openMyTicketDetailMenu(Player p, String ticketId) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_MY_TICKET_OPTIONS + ticketId);
        gui.setItem(11, createGuiItem(Material.PAPER, ChatColor.GREEN + "View Ticket"));
        gui.setItem(13, createGuiItem(Material.REDSTONE_BLOCK, ChatColor.RED + "Delete Ticket"));
        ItemStack outcome = createGuiItem(Material.BOOK, ChatColor.AQUA + "View Ticket Outcome");
        ItemMeta om = outcome.getItemMeta();
        om.setLore(Arrays.asList(ChatColor.GRAY + "Submitted", ChatColor.GRAY + "Pending", ChatColor.GRAY + "Processed"));
        outcome.setItemMeta(om);
        gui.setItem(15, outcome);
        gui.setItem(26, createGuiItem(Material.BARRIER, ChatColor.RED + "Back"));
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

        private void openWorldSettingsMenu(Player p) {
            Inventory gui = Bukkit.createInventory(null, 27, GUI_WORLD_SETTINGS);
            fillGUIBorders(gui);
            fillGUIEmpty(gui);
            gui.setItem(10, createGuiItem(Material.CLOCK, ChatColor.AQUA + "Set Day"));
            gui.setItem(11, createGuiItem(Material.COAL, ChatColor.DARK_AQUA + "Set Night"));
            gui.setItem(12, createGuiItem(Material.WATER_BUCKET, ChatColor.AQUA + "Set Rain"));
            gui.setItem(13, createGuiItem(Material.BEACON, ChatColor.DARK_GRAY + "Set Thunder"));
            gui.setItem(14, createGuiItem(Material.SUNFLOWER, ChatColor.YELLOW + "Clear Weather"));
            gui.setItem(26, createGuiItem(Material.BARRIER, ChatColor.RED + "Back to Main Menu"));
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

    private void loadPersistedWorlds() {
        File worldContainer = getServer().getWorldContainer();
        if (worldContainer == null || !worldContainer.isDirectory()) return;

        File[] dirs = worldContainer.listFiles(File::isDirectory);
        if (dirs == null) return;

        for (File dir : dirs) {
            String name = dir.getName();
            // ignore known non-world folders
            if (name.equalsIgnoreCase("plugins")
                    || name.equalsIgnoreCase("logs")
                    || name.equalsIgnoreCase("crash-reports")
                    || name.equalsIgnoreCase("cache")
                    || name.equalsIgnoreCase("resourcepacks")
                    || name.equalsIgnoreCase("libraries")) {
                continue;
            }

            // If world is already loaded, skip
            if (Bukkit.getWorld(name) != null) continue;

            // Only load folders that look like valid worlds
            File levelDat = new File(dir, "level.dat");
            if (!levelDat.exists()) continue;

            getLogger().info("Loading persisted world: " + name);
            try {
                Bukkit.createWorld(new WorldCreator(name));
            } catch (Exception ex) {
                getLogger().warning("Failed to load world '" + name + "': " + ex.getMessage());
            }
        }
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
        String strippedTitle = ChatColor.stripColor(title);
        boolean relevant = strippedTitle.equals(ChatColor.stripColor(GUI_MAIN))
            || strippedTitle.equals(ChatColor.stripColor(GUI_PLAYER_LIST))
            || strippedTitle.equals(ChatColor.stripColor(GUI_TICKET_LIST))
            || strippedTitle.equals(ChatColor.stripColor(GUI_PLAYER_TICKET_MENU))
            || strippedTitle.equals(ChatColor.stripColor(GUI_PLAYER_TICKETS))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_MY_TICKET_OPTIONS))
            // Category menus may get truncated by the client; match by contains to be resilient
            || strippedTitle.contains("Select Ticket Category")
            || strippedTitle.contains("Select Appeal Category")
            || strippedTitle.equals(ChatColor.stripColor(GUI_TICKET_CATEGORY))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_TICKET_DETAIL))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_PLAYER_ACTION))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_NOTES_VIEW))
            || strippedTitle.equals("Punished Players")
            || strippedTitle.startsWith("Note:")
            || strippedTitle.equals(ChatColor.stripColor(GUI_MENU_SELECTOR))
            || strippedTitle.equals(ChatColor.stripColor(GUI_HELPER_MENU))
            || strippedTitle.equals(ChatColor.stripColor(GUI_MODERATOR_MENU))
            || strippedTitle.equals(ChatColor.stripColor(GUI_PLAYER_MENU))
            || strippedTitle.equals(ChatColor.stripColor(GUI_PLAYER_LIST_TPA))
            || strippedTitle.equals(ChatColor.stripColor(GUI_REPORT_PLAYER))
            || strippedTitle.equals(ChatColor.stripColor(GUI_PLAYER_APPEALS))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_MY_APPEAL_OPTIONS))
            || strippedTitle.equals("Warps")
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_WARP_MANAGEMENT))
            || strippedTitle.startsWith("Delete:")
            || strippedTitle.equals(ChatColor.stripColor(GUI_CLAIMS))
            || strippedTitle.equals(ChatColor.stripColor(GUI_CLAIM_CONFIRM))
            || strippedTitle.equals(ChatColor.stripColor(GUI_UNCLAIM_CONFIRM))
            || strippedTitle.equals(ChatColor.stripColor(GUI_TRUST_PLAYER))
            || strippedTitle.equals(ChatColor.stripColor(GUI_UNTRUST_PLAYER))
            || strippedTitle.equals(ChatColor.stripColor(GUI_WORLD_UTILITIES))
            || strippedTitle.equals(ChatColor.stripColor(GUI_WORLD_SETTINGS))
            || strippedTitle.equals(ChatColor.stripColor(GUI_WORLD_LIST))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_WORLD_OPTIONS))
            || strippedTitle.equals(ChatColor.stripColor(GUI_CREATE_TYPE))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_DELETE_CONFIRM))
            || strippedTitle.equals(ChatColor.stripColor(GUI_KIT_LIST))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_KIT_PREVIEW))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_KIT_CONFIRM))
            || strippedTitle.equals(ChatColor.stripColor(GUI_CRATE_LIST))
            || strippedTitle.equals(ChatColor.stripColor(GUI_BOUNTY_LIST))
            || strippedTitle.equals(ChatColor.stripColor(GUI_SHOP_LIST))
            || strippedTitle.equals(ChatColor.stripColor(GUI_QUEST_LIST))
            || strippedTitle.equals(ChatColor.stripColor(GUI_AUCTION_HOUSE))
            || strippedTitle.equals(ChatColor.stripColor(GUI_PWARP_LIST))
            || strippedTitle.equals(ChatColor.stripColor(GUI_ACHIEVEMENTS))
            || strippedTitle.equals(ChatColor.stripColor(GUI_POLL_LIST))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_POLL_VOTE))
            || strippedTitle.equals(ChatColor.stripColor(GUI_EVENT_LIST))      // admin event manager
            || strippedTitle.equals(ChatColor.stripColor(GUI_ACTIVE_EVENT))    // active event options
            || strippedTitle.equals(ChatColor.stripColor(GUI_CUSTOM_ENCHANTS))
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_NPC_SHOP)); // NPC shop menu
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
            } else if (type == Material.BLUE_STAINED_GLASS) {
                openHelperMenu(p);
            } else if (type == Material.GOLD_BLOCK) {
                openModeratorMenu(p);
            } else if (type == Material.REDSTONE_BLOCK) {
                openMainMenu(p);
            } else if (type == Material.BARRIER) {
                p.closeInventory();
            }
            return;
        }

        // Helper Menu
        if (title.equals(GUI_HELPER_MENU)) {
            if (itemName.equals("Tickets")) {
                p.closeInventory();
                openPlayerTicketMenu(p);
            } else if (itemName.equals("Set Day")) {
                p.getWorld().setTime(1000);
                p.sendMessage(ChatColor.GREEN + "Time set to day.");
            } else if (itemName.equals("Clear Weather")) {
                p.getWorld().setStorm(false);
                p.getWorld().setThundering(false);
                p.sendMessage(ChatColor.GREEN + "Weather cleared.");
            } else if (itemName.equals("Survival Mode")) {
                p.setGameMode(GameMode.SURVIVAL);
            } else if (itemName.equals("Spectator Mode")) {
                p.setGameMode(GameMode.SPECTATOR);
            } else if (itemName.equals("Player Directory")) {
                openPlayerListMenu(p);
            } else if (itemName.equals("Punished Players")) {
                openPunishedPlayersMenu(p);
            } else if (itemName.equals(ChatColor.stripColor(INSPECTOR_NAME))) {
                p.getInventory().addItem(createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME));
                p.sendMessage(ChatColor.GREEN + "Inspector wand added to your inventory.");
            } else if (itemName.equals("Back")) {
                clearMenuOrigin(p);
                openMenuSelector(p);
            }
            return;
        }

        // Moderator Menu
        if (title.equals(GUI_MODERATOR_MENU)) {
            if (itemName.equals("Tickets")) {
                p.closeInventory();
                openPlayerTicketMenu(p);
            } else if (itemName.equals("World Settings")) {
                openWorldSettingsMenu(p);
            } else if (itemName.equals("Survival Mode")) {
                p.setGameMode(GameMode.SURVIVAL);
            } else if (itemName.equals("Spectator Mode")) {
                p.setGameMode(GameMode.SPECTATOR);
            } else if (itemName.equals("Player Directory")) {
                openPlayerListMenu(p);
            } else if (itemName.equals("Punished Players")) {
                openPunishedPlayersMenu(p);
            } else if (itemName.equals(ChatColor.stripColor(INSPECTOR_NAME))) {
                p.getInventory().addItem(createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME));
                p.sendMessage(ChatColor.GREEN + "Inspector wand added to your inventory.");
            } else if (itemName.equals("Toggle Anti-Lag")) {
                boolean enabled = dataConfig.getBoolean("anti_lag.enabled", true);
                if (enabled) {
                    stopAntiLagCleanup();
                    dataConfig.set("anti_lag.enabled", false);
                    saveDataFile();
                    p.sendMessage(ChatColor.RED + "Anti-lag cleanup disabled.");
                } else {
                    dataConfig.set("anti_lag.enabled", true);
                    saveDataFile();
                    startAntiLagCleanup();
                    p.sendMessage(ChatColor.GREEN + "Anti-lag cleanup enabled.");
                }
            } else if (itemName.equals("Run Anti-Lag Now")) {
                clearGroundItems();
                Bukkit.broadcastMessage(ChatColor.BLUE + "Drowsy Anti Lag: Drops/Items have been Cleared!");
                p.sendMessage(ChatColor.GREEN + "Anti-lag cleanup executed immediately.");
            } else if (itemName.equals("Back")) {
                clearMenuOrigin(p);
                openMenuSelector(p);
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
            } else if (itemName.equals("Custom Enchantments")) {
                openCustomEnchantGUI(p);
            } else if (itemName.equals("Tickets")) {
                p.closeInventory();
                openPlayerTicketMenu(p);
            } else if (type == Material.BARRIER) {
                p.closeInventory();
            }
            return;
        }

        // Player ticket menu
        if (title.equals(GUI_PLAYER_TICKET_MENU)) {
            if (itemName.equals("Create Ticket")) {
                p.closeInventory();
                openCategoryMenu(p);
            } else if (itemName.equals("View Your Tickets")) {
                p.closeInventory();
                openMyTicketsMenu(p);
            } else if (itemName.equals("Create Appeal")) {
                p.closeInventory();
                openAppealCategoryMenu(p);
            } else if (itemName.equals("View Your Appeals")) {
                p.closeInventory();
                openMyAppealsMenu(p);
            } else if (type == Material.BARRIER) {
                p.closeInventory();
            }
            return;
        }

        // Category selection menu (tickets or appeals)
        if (strippedTitle.contains("Select Ticket Category") || strippedTitle.contains("Select Appeal Category")
            || strippedTitle.equals(ChatColor.stripColor(GUI_TICKET_CATEGORY))
            || strippedTitle.equals(ChatColor.stripColor(GUI_APPEAL_CATEGORY))) {
            if (itemName.equalsIgnoreCase("Cancel")) {
                openPlayerTicketMenu(p);
            } else if (!itemName.isEmpty()) {
                String category = itemName.toLowerCase().replace(" ", "_");
                p.closeInventory();
                ActionType act = strippedTitle.contains("Select Appeal Category")
                        || strippedTitle.equals(ChatColor.stripColor(GUI_APPEAL_CATEGORY))
                        ? ActionType.APPEAL_CREATE
                        : ActionType.TICKET_CREATE;
                pendingActions.put(p.getUniqueId(), new PunishmentContext(category, act));
                p.sendMessage(ChatColor.GOLD + "Type your " + (act == ActionType.APPEAL_CREATE ? "appeal" : "ticket") + " message:");
            }
            return;
        }

        // Event list menu
        if (title.equals(GUI_EVENT_LIST)) {
            if (type == Material.BARRIER) {
                openMainMenu(p);
            } else {
                String eventName = itemName;
                String key = eventKeyFromDisplay(eventName);
                // start the event effect
                startEventEffect(key);
                dataConfig.set("events.active." + key + ".startTime", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                dataConfig.set("events.active." + key + ".admin", p.getName());
                saveDataFile();
                p.closeInventory();
                openActiveEventMenu(p, eventName);
            }
            return;
        }

        // Custom enchant menu
        if (title.equals(GUI_CUSTOM_ENCHANTS)) {
            if (type == Material.BARRIER) {
                openPlayerMenu(p);
            } else if (type == Material.ENCHANTED_BOOK && itemName != null && !itemName.trim().isEmpty()) {
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(itemName, ActionType.ENCHANT));
                p.sendMessage(ChatColor.AQUA + "Enter hotbar slot number (1-9) to enchant with " + itemName + ":");
            }
            return;
        }

        // Active event menu
        if (title.equals(GUI_ACTIVE_EVENT)) {
            if (itemName.equals("Back to Menu")) {
                openMainMenu(p);
            } else if (itemName.equals("End Event")) {
                String active = getActiveEvent();
                if (active != null) {
                    stopEventEffect(active);
                    dataConfig.set("events.active." + active, null);
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Event " + active + " ended.");
                }
                openMainMenu(p);
            }
            return;
        }

        // My tickets list
        if (title.equals(GUI_PLAYER_TICKETS)) {
            if (type == Material.REDSTONE) {
                openPlayerTicketMenu(p);
                return;
            }
            if (itemName.startsWith("Ticket #")) {
                String id = itemName.replace("Ticket #", "").trim();
                openMyTicketDetailMenu(p, id);
            }
            return;
        }

        // My ticket options detail menu
        if (title.startsWith(GUI_MY_TICKET_OPTIONS)) {
            String ticketId = title.replace(GUI_MY_TICKET_OPTIONS, "").trim();
            if (itemName.equals("View Ticket")) {
                p.closeInventory();
                openTicketDetailMenu(p, ticketId);
            } else if (itemName.equals("Delete Ticket")) {
                p.closeInventory();
                dataConfig.set("tickets." + ticketId, null);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " deleted.");
                openMyTicketsMenu(p);
            } else if (itemName.equals("View Ticket Outcome")) {
                p.sendMessage(ChatColor.AQUA + "Status options: Submitted, Pending, Processed");
            } else if (type == Material.BARRIER) {
                openMyTicketsMenu(p);
            }
            return;
        }

        // My appeals list
        if (title.equals(GUI_PLAYER_APPEALS)) {
            if (type == Material.REDSTONE) {
                openPlayerTicketMenu(p);
                return;
            }
            if (itemName.startsWith("Appeal #")) {
                String id = itemName.replace("Appeal #", "").trim();
                openMyAppealDetailMenu(p, id);
            }
            return;
        }

        // My appeal options detail menu
        if (title.startsWith(GUI_MY_APPEAL_OPTIONS)) {
            String appealId = title.replace(GUI_MY_APPEAL_OPTIONS, "").trim();
            if (itemName.equals("View Appeal")) {
                String message = dataConfig.getString("appeals." + appealId + ".message", "");
                p.sendMessage(ChatColor.AQUA + "Appeal #" + appealId + ": " + ChatColor.WHITE + message);
            } else if (itemName.equals("Delete Appeal")) {
                p.closeInventory();
                dataConfig.set("appeals." + appealId, null);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Appeal #" + appealId + " deleted.");
                openMyAppealsMenu(p);
            } else if (itemName.equals("View Appeal Outcome")) {
                p.sendMessage(ChatColor.AQUA + "Status options: Submitted, Pending, Processed");
            } else if (type == Material.BARRIER) {
                openMyAppealsMenu(p);
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
                            long price = dataConfig.getLong("shops." + shopId + ".price", 0);
                            if (getCoins(p.getUniqueId()) < price) {
                                p.sendMessage(ChatColor.RED + "Not enough Drowsy coins! Need " + price + " Drowsy coins.");
                                return;
                            }
                            addCoins(p.getUniqueId(), -price);
                            String ownerId = dataConfig.getString("shops." + shopId + ".owner", "");
                            if (!ownerId.isEmpty()) {
                                try { addCoins(UUID.fromString(ownerId), price); } catch (Exception ignored) {}
                            }
                            Material mat = Material.valueOf(sItem.toUpperCase());
                            ItemStack bought = new ItemStack(mat, sAmt);
                            HashMap<Integer, ItemStack> overflow = p.getInventory().addItem(bought);
                            for (ItemStack drop : overflow.values()) p.getWorld().dropItemNaturally(p.getLocation(), drop);
                            p.sendMessage(ChatColor.GREEN + "Purchased " + sAmt + "x " + mat.name().replace("_", " ") + " for " + price + " Drowsy coins!");
                            String owner = dataConfig.getString("shops." + shopId + ".ownerName", "");
                            String ownerUUID = dataConfig.getString("shops." + shopId + ".owner", "");
                            p.closeInventory();
                            // Notify seller if online
                            Player seller = Bukkit.getPlayer(owner);
                            if (seller != null) {
                                int xpToAdd = price > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) price;
                                seller.setLevel(seller.getLevel() + xpToAdd);
                                seller.sendMessage(ChatColor.GREEN + p.getName() + " bought " + sAmt + "x " + mat.name().replace("_", " ") + " from your shop! (+" + price + " XP)");
                            } else if (ownerUUID != null && !ownerUUID.isEmpty()) {
                                int pending = dataConfig.getInt("pending_xp." + ownerUUID, 0);
                                dataConfig.set("pending_xp." + ownerUUID, pending + price);
                            }
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
                    if (getCoins(p.getUniqueId()) < nextBid) { p.sendMessage(ChatColor.RED + "Not enough coins to bid (" + nextBid + " needed)."); return; }
                    String seller = dataConfig.getString("auctions." + auctionId + ".seller", "");
                    if (seller.equals(p.getUniqueId().toString())) { p.sendMessage(ChatColor.RED + "Can't bid on your own auction."); return; }
                    dataConfig.set("auctions." + auctionId + ".currentBid", nextBid);
                    dataConfig.set("auctions." + auctionId + ".highBidder", p.getUniqueId().toString());
                    dataConfig.set("auctions." + auctionId + ".highBidderName", p.getName());
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Bid placed: " + nextBid + " coins!");
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
                openPlayerMenu(p);
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
                    catch (Exception ex) { 
                        p.sendMessage(ChatColor.RED + "Error opening world list"); 
                        getLogger().log(java.util.logging.Level.SEVERE, "Error opening world list", ex);
                    }
                }
                else if (type == Material.NETHER_STAR) {
                    try { openCreateWorldTypeMenu(p); }
                    catch (Exception ex) { 
                        p.sendMessage(ChatColor.RED + "Error opening world type chooser"); 
                        getLogger().log(java.util.logging.Level.SEVERE, "Error opening world type chooser", ex);
                    }
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
            } else if (type == Material.PAPER) {
                p.closeInventory();
                ItemStack wand = new ItemStack(Material.PAPER);
                ItemMeta wMeta = wand.getItemMeta();
                wMeta.setDisplayName(HOLOGRAM_WAND_NAME);
                wand.setItemMeta(wMeta);
                p.getInventory().addItem(wand);
                p.sendMessage(ChatColor.BLUE + "Hologram Wand added to your inventory!");
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
            String requiredPerm = getAdminMenuPermission(slot);
            if (requiredPerm != null && !p.hasPermission(requiredPerm)) {
                p.sendMessage(ChatColor.RED + "You do not have permission to use this option.");
                return;
            }

            switch(slot) {
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
                case 19: openWorldSettingsMenu(p); break;
                case 20: openPlayerListMenu(p); break;
                case 21: p.setGameMode(GameMode.CREATIVE); break;
                case 22: p.setGameMode(GameMode.SURVIVAL); break;
                case 23: p.setGameMode(GameMode.SPECTATOR); break;
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
                        getLogger().log(java.util.logging.Level.SEVERE, "Error opening world utilities menu", ex);
                    }
                    break;
                case 32:
                    String active = getActiveEvent();
                    if (active != null) {
                        openActiveEventMenu(p, active);
                    } else {
                        openEventListMenu(p);
                    }
                    break;
                case 33:
                    ItemStack wand = new ItemStack(Material.PAPER);
                    ItemMeta wMeta = wand.getItemMeta();
                    wMeta.setDisplayName(HOLOGRAM_WAND_NAME);
                    wand.setItemMeta(wMeta);
                    p.getInventory().addItem(wand);
                    p.sendMessage(ChatColor.BLUE + "Hologram Wand added to your inventory!");
                    break;
                case 16: openPunishedPlayersMenu(p); break;
                case 43: p.closeInventory(); break;
            }
        } else if (title.equals(GUI_WORLD_SETTINGS)) {
            int slot = e.getRawSlot();
            switch (slot) {
                case 10:
                    p.getWorld().setTime(1000);
                    p.sendMessage(ChatColor.GREEN + "Time set to day.");
                    break;
                case 11:
                    p.getWorld().setTime(13000);
                    p.sendMessage(ChatColor.GREEN + "Time set to night.");
                    break;
                case 12:
                    p.getWorld().setStorm(true);
                    p.getWorld().setThundering(false);
                    p.sendMessage(ChatColor.GREEN + "Weather set to rain.");
                    break;
                case 13:
                    p.getWorld().setStorm(true);
                    p.getWorld().setThundering(true);
                    p.sendMessage(ChatColor.GREEN + "Weather set to thunder.");
                    break;
                case 14:
                    p.getWorld().setStorm(false);
                    p.getWorld().setThundering(false);
                    p.sendMessage(ChatColor.GREEN + "Weather cleared.");
                    break;
                case 26:
                    returnToPreviousMenu(p);
                    break;
            }
        } else if (title.startsWith(GUI_NPC_SHOP)) {
            if (type == Material.BARRIER) { openMainMenu(p); return; }
            int slot = e.getRawSlot();
            int start = title.indexOf('(');
            int end = title.lastIndexOf(')');
            if (start == -1 || end == -1 || end <= start) return;
            String uuidStr = title.substring(start + 1, end);
            UUID npcId;
            try { npcId = UUID.fromString(uuidStr); } catch (Exception ex) { return; }
            List<String> items = dataConfig.getStringList("summons." + npcId + ".shop_items");
            if (slot < 0 || slot >= items.size()) return;
            String entry = items.get(slot);
            String[] parts = entry.split(":");
            if (parts.length < 3) return;
            Material mat;
            try { mat = Material.valueOf(parts[0]); } catch (Exception ex) { return; }
            int amount = Integer.parseInt(parts[1]);
            long price = Long.parseLong(parts[2]);
            if (getCoins(p.getUniqueId()) < price) {
                p.sendMessage(ChatColor.RED + "Not enough coins (" + price + " needed).");
                return;
            }
            addCoins(p.getUniqueId(), -price);
            p.getInventory().addItem(new ItemStack(mat, amount));
            p.sendMessage(ChatColor.GREEN + "Purchased " + amount + "x " + mat.name() + " for " + price + " coins.");
            return;
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
            else if (type == Material.REDSTONE) returnToPreviousMenu(p);
        } else if (title.equals(GUI_PLAYER_LIST)) {
            if (type == Material.PLAYER_HEAD) openPlayerActionMenu(p, itemName);
            else if (type == Material.REDSTONE) returnToPreviousMenu(p);
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
                    p.sendMessage(ChatColor.RED + "Punished " + targetName);
                    logAction(p.getName(), "punished", targetName + " (" + itemName.replace("Punish ", "") + ")");
                    break;
                case MILK_BUCKET: 
                    removePunishment(uuid); 
                    p.sendMessage(ChatColor.GREEN + "Unpunished " + targetName);
                    logAction(p.getName(), "unpunished", targetName);
                    break;
            }
        }
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Error handling GUI click (" + title + "): " + ex.getMessage(), ex);
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

        // Ensure the player receives the Drowsy Tool on respawn (if it was held when they died)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (e.getPlayer().isOnline()) {
                UUID uuid = e.getPlayer().getUniqueId();
                if (toolRespawnQueue.remove(uuid)) {
                    ensurePlayerHasTool(e.getPlayer());
                }
            }
        }, 1L);
    }
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        // store last location when moving between worlds (before the teleport happens)
        if (e.getFrom() != null && e.getTo() != null
                && e.getFrom().getWorld() != null && e.getTo().getWorld() != null
                && !e.getFrom().getWorld().equals(e.getTo().getWorld())) {
            saveLoc("last_location." + e.getPlayer().getUniqueId() + "." + e.getFrom().getWorld().getName(), e.getFrom());
        }

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
        if (e.getEntity() instanceof Player) {
            Player p = (Player) e.getEntity();
            if (isPunished(p.getUniqueId())) e.setCancelled(true);
        }
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
        Player p = e.getPlayer();
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());

        // Anti-xray: block excessive ore mining in a short time window
        if (checkXray(p, e.getBlock())) {
            e.setCancelled(true);
            return;
        }

        if (isPunished(p.getUniqueId())) {
            e.setCancelled(true);
        } else {
            // Check chunk claims
            String chunkKey = getChunkKey(e.getBlock().getLocation());
            if (isChunkClaimed(chunkKey) && !isTrustedInChunk(p, chunkKey)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You cannot break blocks in this claimed chunk!");
                return;
            }
            saveLog(e.getBlock().getLocation(), ChatColor.RED + "Broken by " + p.getName());
            // Quest tracking
            trackQuestProgress(p, "break_blocks", 1);
            trackQuestProgress(p, "mine_" + e.getBlock().getType().name().toLowerCase(), 1);
            Material m = e.getBlock().getType();
            if (m.name().contains("LOG")) trackQuestProgress(p, "break_logs", 1);
            if (m.name().contains("ORE")) trackQuestProgress(p, "break_ores", 1);
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
        if ((e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR) && e.getItem() != null && e.getItem().hasItemMeta()) {
            String wandName = e.getItem().getItemMeta().getDisplayName();
            
            // Inspector Wand
            if (wandName.equals(INSPECTOR_NAME)) {
                if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
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
                if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
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
            // Hologram Wand
            else if (wandName.equals(HOLOGRAM_WAND_NAME)) {
                if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
                e.setCancelled(true);
                Player p = e.getPlayer();
                p.sendMessage(ChatColor.AQUA + "How many lines should the hologram have? (1-10)");
                pendingActions.put(p.getUniqueId(), new PunishmentContext(null, ActionType.HOLOGRAM));
            }
        }
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractAtEntityEvent e) {
        if (!(e.getRightClicked() instanceof ArmorStand)) return;
        ArmorStand as = (ArmorStand) e.getRightClicked();
        handleNpcInteraction(e.getPlayer(), as.getUniqueId().toString());
    }

    // Citizens provides dedicated click events, but we can't compile against Citizens.
    // Instead, we register a listener via reflection at runtime if Citizens is present.
    private void registerCitizensClickListener() {
        try {
            Class<?> eventClass = Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent");
            // Register via Bukkit's event system without compile-time dependency
            Bukkit.getPluginManager().registerEvent(
                (Class<? extends Event>) eventClass,
                this,
                EventPriority.NORMAL,
                (listener, event) -> {
                    try {
                        Method getClicker = event.getClass().getMethod("getClicker");
                        Player p = (Player) getClicker.invoke(event);
                        Method getNPC = event.getClass().getMethod("getNPC");
                        Object npc = getNPC.invoke(event);
                        Method getId = npc.getClass().getMethod("getId");
                        Object id = getId.invoke(npc);
                        if (id != null) {
                            handleNpcInteraction(p, "citizens:" + id.toString());
                        }
                    } catch (Exception ignored) {
                        // ignore - just means the event is not usable
                    }
                },
                this
            );
        } catch (ClassNotFoundException ignored) {
            // Citizens not present; ignore.
        }
    }

    private void handleNpcInteraction(Player p, String npcId) {
        String base = "summons." + npcId;
        if (!dataConfig.contains(base + ".type")) return;
        String type = dataConfig.getString(base + ".type", "");
        if (type.equalsIgnoreCase("shop")) {
            openNpcShop(p, npcId);
        } else if (type.equalsIgnoreCase("teleport")) {
            String worldName = dataConfig.getString(base + ".teleport.world", "");
            if (worldName.isEmpty()) {
                p.sendMessage(ChatColor.RED + "Teleport destination not configured.");
                return;
            }
            World w = Bukkit.getWorld(worldName);
            if (w == null) {
                p.sendMessage(ChatColor.RED + "Teleport world not found: " + worldName);
                return;
            }
            if (dataConfig.contains(base + ".teleport.x")) {
                double x = dataConfig.getDouble(base + ".teleport.x");
                double y = dataConfig.getDouble(base + ".teleport.y");
                double z = dataConfig.getDouble(base + ".teleport.z");
                p.teleport(new Location(w, x, y, z));
            } else {
                // Priority teleport: if the player has a saved location in that world (from previous teleport), use it.
                Location saved = getLoc("last_location." + p.getUniqueId() + "." + worldName);
                if (saved != null) {
                    p.teleport(saved);
                } else {
                    p.teleport(w.getSpawnLocation());
                }
            }
            p.sendMessage(ChatColor.GREEN + "Teleported to " + worldName + ".");
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
        
        if (isHelper(p) || isModerator(p) || p.isOp() || p.hasPermission("dmt.admin")) {
            openMenuSelector(p);
        } else {
            openPlayerMenu(p);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().toLowerCase(Locale.ROOT).trim();
        if (!msg.startsWith("/")) return;

        // Prevent players from revealing the world seed via commands.
        // Admins are allowed to use these commands.
        if (!e.getPlayer().hasPermission("dmt.admin")) {
            // Block any command containing "seed" (covers /seed, /world seed, /gamerule seed, /help seed, etc.)
            if (msg.contains("seed")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot use that command.");
                return;
            }

            // Some plugins may expose seed via other terms; block typical patterns.
            if (msg.contains("worldseed") || msg.contains("world_seed") || msg.contains("world-seed")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot use that command.");
            }
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

        // Server spawn override (teleport to hub world on join)
        Location serverSpawn = getLoc("server_spawn");
        if (serverSpawn != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (e.getPlayer().isOnline()) {
                    e.getPlayer().teleport(serverSpawn);
                    e.getPlayer().sendMessage(ChatColor.AQUA + "You have been teleported to the server spawn.");
                }
            }, 1L);
        }
        
        // Track IPs, sessions and log join
        try {
            this.trackPlayerIP(uuid, e.getPlayer().getName(), e.getPlayer().getAddress().getAddress().getHostAddress());
        } catch (Exception ignored) {}
        this.trackSession(uuid, e.getPlayer().getName(), true);
        this.logAction("System", "player_joined", e.getPlayer().getName());

        // Pending Shop XP
        int pendingXp = dataConfig.getInt("pending_xp." + uuid, 0);
        if (pendingXp > 0) {
            e.getPlayer().setLevel(e.getPlayer().getLevel() + pendingXp);
            e.getPlayer().sendMessage(ChatColor.GREEN + "You earned " + pendingXp + " XP levels from your shop while offline!");
            dataConfig.set("pending_xp." + uuid, null);
            saveDataFile();
        }

        // Give admin tool if missing
        ensurePlayerHasTool(e.getPlayer());

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
                int coinsReward = dataConfig.getInt("daily_login_base_coins", 0) + (streak * dataConfig.getInt("daily_login_streak_coins", 0));
                if (xpReward > 0) e.getPlayer().giveExp(xpReward);
                if (coinsReward > 0) {
                    addCoins(e.getPlayer().getUniqueId(), coinsReward);
                    e.getPlayer().sendMessage(ChatColor.GOLD + "+" + coinsReward + " coins!");
                }
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

    private void ensurePlayerHasTool(Player p) {
        boolean hasTool = Arrays.stream(p.getInventory().getContents())
            .filter(Objects::nonNull)
            .anyMatch(i -> i.hasItemMeta() && TOOL_NAME.equals(i.getItemMeta().getDisplayName()));
        if (!hasTool) {
            ItemStack tool = new ItemStack(Material.DIAMOND);
            ItemMeta m = tool.getItemMeta();
            m.setDisplayName(TOOL_NAME);
            m.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            tool.setItemMeta(m);
            p.getInventory().addItem(tool);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        // Save last seen location per world so we can restore when teleporting back.
        Location lastLoc = e.getPlayer().getLocation();
        if (lastLoc != null && lastLoc.getWorld() != null) {
            saveLoc("last_location." + uuid + "." + lastLoc.getWorld().getName(), lastLoc);
        }

        lastActivity.remove(uuid);
        removePermissionAttachment(e.getPlayer());
        this.trackSession(uuid, e.getPlayer().getName(), false);
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
                    String worldName = s[0];
                    World w = Bukkit.getWorld(worldName);
                    if (w == null) {
                        // Attempt to load the world if the folder exists (so hub worlds persist between restarts).
                        w = Bukkit.createWorld(new org.bukkit.WorldCreator(worldName));
                        if (w == null) return null;
                    }
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
            return dataConfig.getLong("playtime." + u, 0L) / 60L;
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
        String base = id < 0 ? "appeals." + (-id) : "tickets." + id;
        if (!dataConfig.contains(base)) return m;
        m.put("id", Integer.toString(id));
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
        String base = id < 0 ? "appeals." + (-id) : "tickets." + id;
        String path = base + ".responses";
        List<String> responses = dataConfig.getStringList(path);
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + admin + " | " + message;
        responses.add(entry);
        dataConfig.set(path, responses);
        dataConfig.set(base + ".has_new_response", true);
        saveDataFile();
        // Notify player if online
        String playerName = dataConfig.getString(base + ".player", "");
        Player target = Bukkit.getPlayer(playerName);
        if (target != null && target.isOnline()) {
            target.playSound(target.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1F, 2F);
            target.sendMessage(ChatColor.GOLD + "[" + (id < 0 ? "Appeals" : "Tickets") + "] " + ChatColor.GREEN + admin + " responded to your " + (id < 0 ? "appeal" : "ticket") + " #" + Math.abs(id) + ": " + ChatColor.WHITE + message);
        }
    }

    public void updateTicketField(int id, String field, String value) {
        dataConfig.set((id < 0 ? "appeals." + (-id) : "tickets." + id) + "." + field, value);
        saveDataFile();
    }

    public void resolveTicket(int id, String reason) {
        String base = id < 0 ? "appeals." + (-id) : "tickets." + id;
        dataConfig.set(base + ".status", "resolved");
        dataConfig.set(base + ".resolution", reason);
        dataConfig.set(base + ".has_new_response", true);
        saveDataFile();
        // Notify player if online
        String playerName = dataConfig.getString(base + ".player", "");
        Player target = Bukkit.getPlayer(playerName);
        if (target != null && target.isOnline()) {
            target.sendMessage(ChatColor.GOLD + "[" + (id < 0 ? "Appeals" : "Tickets") + "] " + ChatColor.GREEN + "Your " + (id < 0 ? "appeal" : "ticket") + " #" + Math.abs(id) + " has been resolved: " + ChatColor.WHITE + reason);
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
        // admins should be able to bypass claim restrictions entirely
        if (p.hasPermission("dmt.admin") || p.hasPermission("claims.override")) {
            return true;
        }

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

    private void applyRankToPlayer(Player p) {
        String rank = getPlayerRank(p.getUniqueId());
        if (rank == null) {
            // remove any existing rank teams
            removePlayerFromRankTeams(p);
            // reset tab name
            p.setPlayerListName(p.getName());
            return;
        }
        String teamName = "rank_" + rank.toLowerCase(Locale.ROOT);
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            team.setCanSeeFriendlyInvisibles(true);
            team.setAllowFriendlyFire(true);
        }
        String prefix = dataConfig.getString(RANKS_PATH + "." + rank + ".prefix", "");
        String formatted = ChatColor.translateAlternateColorCodes('&', prefix);
        team.setPrefix(formatted);

        // Remove from other rank teams
        removePlayerFromRankTeams(p);
        team.addEntry(p.getName());

        // Also set the tab list name
        p.setPlayerListName(formatted + p.getName());
    }

    private void removePlayerFromRankTeams(Player p) {
        for (Team t : scoreboard.getTeams()) {
            if (t.getName().startsWith("rank_") && t.hasEntry(p.getName())) {
                t.removeEntry(p.getName());
            }
        }
    }

    private void startAntiLagCleanup() {
        if (!dataConfig.getBoolean("anti_lag.enabled", true)) return;

        stopAntiLagCleanup();

        // Announce 3 minutes before the cleanup (every 10 minutes)
        antiLagWarningTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            Bukkit.broadcastMessage(ChatColor.BLUE + "Drowsy Anti Lag: Clearing items in 3 minutes!");
        }, 7 * 60 * 20L, 10 * 60 * 20L); // first run after 7m, then every 10m

        // Announce 30 seconds before the cleanup (every 10 minutes)
        antiLagWarningTaskId2 = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            Bukkit.broadcastMessage(ChatColor.BLUE + "Drowsy Anti Lag: Clearing items in 30 seconds!");
        }, (9 * 60 + 30) * 20L, 10 * 60 * 20L); // first run after 9m30s, then every 10m

        // Actual clear every 10 minutes
        antiLagClearTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            clearGroundItems();
            Bukkit.broadcastMessage(ChatColor.BLUE + "Drowsy Anti Lag: Drops/Items have been Cleared!");
        }, 10 * 60 * 20L, 10 * 60 * 20L);
    }

    private void stopAntiLagCleanup() {
        if (antiLagWarningTaskId != -1) {
            Bukkit.getScheduler().cancelTask(antiLagWarningTaskId);
            antiLagWarningTaskId = -1;
        }
        if (antiLagWarningTaskId2 != -1) {
            Bukkit.getScheduler().cancelTask(antiLagWarningTaskId2);
            antiLagWarningTaskId2 = -1;
        }
        if (antiLagClearTaskId != -1) {
            Bukkit.getScheduler().cancelTask(antiLagClearTaskId);
            antiLagClearTaskId = -1;
        }
    }

    private void clearGroundItems() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                item.remove();
                removed++;
            }
        }
        logAction("System", "anti_lag", "Removed " + removed + " ground items");
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

    private boolean isSpawnDisabled(EntityType type) {
        return dataConfig.getBoolean("disable_spawns." + type.name().toLowerCase(), false);
    }

    private void setSpawnDisabled(EntityType type, boolean disabled) {
        String path = "disable_spawns." + type.name().toLowerCase();
        if (disabled) {
            dataConfig.set(path, true);
        } else {
            dataConfig.set(path, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (isSpawnDisabled(e.getEntityType())) {
            e.setCancelled(true);
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

    private void generateCloudLayer(World world, int centerX, int centerZ, int width, int length, int depth, int y) {
        int halfWidth = width / 2;
        int halfLength = length / 2;

        Random rand = new Random(world.getSeed() ^ ((long) centerX * 341873128712L) ^ ((long) centerZ * 132897987541L));
        double phase1 = rand.nextDouble() * Math.PI * 2;
        double phase2 = rand.nextDouble() * Math.PI * 2;

        Material[] cloudMaterials = new Material[] {
            Material.WHITE_WOOL,
            Material.WHITE_STAINED_GLASS,
            Material.WHITE_STAINED_GLASS_PANE,
            Material.LIGHT_GRAY_WOOL,
            Material.LIGHT_GRAY_STAINED_GLASS,
            Material.LIGHT_GRAY_STAINED_GLASS_PANE
        };
        int[] cloudWeights = new int[] {
            40, // white wool
            20, // white glass
            20, // white pane
            5,  // light gray wool
            5,  // light gray glass
            5   // light gray pane
        };

        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -halfLength; z <= halfLength; z++) {
                double nx = (double) x / Math.max(1, halfWidth);
                double nz = (double) z / Math.max(1, halfLength);

                double dist = Math.sqrt(nx * nx + nz * nz);
                if (dist == 0) dist = 0.001;

                double angle = Math.atan2(nz, nx);
                double noise = 0.85
                             + 0.10 * Math.sin(angle * 4 + phase1)
                             + 0.05 * Math.cos(angle * 7 + phase2);

                if (dist <= noise) {
                    double hFactor = Math.sqrt(1.0 - Math.pow(dist / noise, 2));
                    int cloudDepth = (int) Math.round(depth * hFactor);

                    if (cloudDepth > 0) {
                        int wx = centerX + x;
                        int wz = centerZ + z;
                        int topY = y + depth - 1;

                        // Use grass for the core, outline it with wool, and add some glass/panes for texture.
                        double grassRadius = noise * 0.60;
                        double outlineRadius = Math.min(noise, grassRadius + 0.12);

                        Material topMat;
                        if (dist <= grassRadius) {
                            topMat = Material.GRASS_BLOCK;
                        } else if (dist <= outlineRadius) {
                            topMat = Material.WHITE_WOOL;
                        } else {
                            topMat = pickWeightedMaterial(rand, cloudMaterials, cloudWeights);
                        }

                        world.getBlockAt(wx, topY, wz).setType(topMat, false);
                        for (int dy = 1; dy < cloudDepth; dy++) {
                            Material innerMat = pickWeightedMaterial(rand, cloudMaterials, cloudWeights);
                            world.getBlockAt(wx, topY - dy, wz).setType(innerMat, false);
                        }
                    }
                }
            }
        }
    }

    private Material pickWeightedMaterial(Random rand, Material[] materials, int[] weights) {
        int total = 0;
        for (int w : weights) total += w;
        int r = rand.nextInt(total);
        int running = 0;
        for (int i = 0; i < materials.length; i++) {
            running += weights[i];
            if (r < running) {
                return materials[i];
            }
        }
        return materials[0];
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
        UUID victimUUID = victim.getUniqueId();

        // Prevent losing the Drowsy Tool on death
        boolean hadTool = Arrays.stream(victim.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(i -> i.hasItemMeta() && TOOL_NAME.equals(i.getItemMeta().getDisplayName()));
        if (hadTool) {
            toolRespawnQueue.add(victimUUID);
            e.getDrops().removeIf(item -> item != null && item.hasItemMeta() && TOOL_NAME.equals(item.getItemMeta().getDisplayName()));
        }

        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        // --- PVP STATS TRACKING ---
        UUID killerUUID = killer.getUniqueId();
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

    private boolean isOre(Material m) {
        return switch (m) {
            // coal is excluded from the anti-xray counter
            case IRON_ORE, DEEPSLATE_IRON_ORE,
                 COPPER_ORE, DEEPSLATE_COPPER_ORE,
                 GOLD_ORE, DEEPSLATE_GOLD_ORE,
                 REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE,
                 LAPIS_ORE, DEEPSLATE_LAPIS_ORE,
                 DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                 EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                 NETHER_GOLD_ORE, NETHER_QUARTZ_ORE -> true;
            default -> false;
        };
    }

    private boolean checkXray(Player p, Block block) {
        if (!isOre(block.getType())) return false;

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();

        // If player is currently locked, prevent mining
        Long lockUntil = xrayLockUntil.get(uuid);
        if (lockUntil != null && now < lockUntil) {
            if ((now / 1000) % 5 == 0) { // occasional reminder
                p.sendMessage(ChatColor.RED + "You are mining too fast. Slow down.");
            }
            return true;
        }

        List<Long> times = oreBreakTimestamps.computeIfAbsent(uuid, k -> new ArrayList<>());
        times.add(now);
        // remove old entries
        times.removeIf(t -> t < now - XRAY_WINDOW_MS);

        if (times.size() >= XRAY_THRESHOLD) {
            // Flag: too many ores broken in a short time
            xrayLockUntil.put(uuid, now + XRAY_PENALTY_MS);
            times.clear();
            p.sendMessage(ChatColor.RED + "Mining too quickly? Slow down to avoid being flagged for x-ray.");
            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (admin.hasPermission("dmt.admin")) {
                    admin.sendMessage(ChatColor.RED + "[Anti-XRay] " + p.getName() + " is breaking ores too fast.");
                }
            }
            return true;
        }

        return false;
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
        List<String> order = Arrays.asList("enchant_timber","enchant_veinminer","enchant_smelting","enchant_telepathy");

        if (dataConfig.contains("quests")) {
            List<String> questIds = new ArrayList<>(dataConfig.getConfigurationSection("quests").getKeys(false));
            // Ensure enchant quests display in a consistent order
            questIds.sort(Comparator.comparingInt(q -> {
                int idx = order.indexOf(q);
                return idx >= 0 ? idx : order.size();
            }));
            for (String questId : questIds) {
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
                // special handling for smelting quest
                int breakProg = 0, smeltProg = 0;
                if (questId.equals("enchant_smelting")) {
                    breakProg = dataConfig.getInt("quest_progress." + p.getUniqueId() + "." + questId + ".break", 0);
                    smeltProg = dataConfig.getInt("quest_progress." + p.getUniqueId() + "." + questId + ".smelt", 0);
                }

                Material mat = completed ? Material.LIME_DYE : (progress > 0 ? Material.YELLOW_DYE : Material.GRAY_DYE);
                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName((completed ? ChatColor.GREEN + "✅ " : ChatColor.GOLD) + name);
                List<String> lore = new ArrayList<>();
                if (!desc.isEmpty()) lore.add(ChatColor.GRAY + desc);
                lore.add("");
                lore.add(ChatColor.YELLOW + "Type: " + type.replace("_", " "));
                if (questId.equals("enchant_smelting")) {
                    lore.add(ChatColor.AQUA + "Broken ore: " + Math.min(breakProg, 750) + "/750");
                    lore.add(ChatColor.AQUA + "Smelted ore: " + Math.min(smeltProg, 250) + "/250");
                } else {
                    lore.add(ChatColor.AQUA + "Progress: " + Math.min(progress, goal) + "/" + goal);
                }
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
            if (questType.equalsIgnoreCase("smelting_touch")) {
                // handle dual criteria
                int bgoal = 750;
                int sgoal = 250;
                int prevB = dataConfig.getInt("quest_progress." + p.getUniqueId() + "." + questId + ".break", 0);
                int prevS = dataConfig.getInt("quest_progress." + p.getUniqueId() + "." + questId + ".smelt", 0);
                int newB = prevB;
                int newS = prevS;
                if (type.equals("break_ores")) {
                    newB += amount;
                    dataConfig.set("quest_progress." + p.getUniqueId() + "." + questId + ".break", newB);
                }
                if (type.equals("smelt_ores")) {
                    newS += amount;
                    dataConfig.set("quest_progress." + p.getUniqueId() + "." + questId + ".smelt", newS);
                }
                saveDataFile();

                boolean wasIncomplete = !(prevB >= bgoal && prevS >= sgoal);
                boolean nowComplete = (newB >= bgoal && newS >= sgoal);
                if (!dataConfig.getBoolean("quest_completed." + p.getUniqueId() + "." + questId, false)
                    && wasIncomplete && nowComplete) {
                    p.sendMessage(ChatColor.GOLD + "🎯 Quest " + ChatColor.YELLOW + dataConfig.getString("quests." + questId + ".name", questId) + ChatColor.GOLD + " complete! Use /quest to claim your reward.");
                }
                continue;
            }
            if (!questType.equalsIgnoreCase(type)) continue;
            if (dataConfig.getBoolean("quest_completed." + p.getUniqueId() + "." + questId, false)) continue;
            int goal = dataConfig.getInt("quests." + questId + ".goal", 1);
            int previous = dataConfig.getInt("quest_progress." + p.getUniqueId() + "." + questId, 0);
            int current = previous + amount;
            dataConfig.set("quest_progress." + p.getUniqueId() + "." + questId, current);
            if (previous < goal && current >= goal) {
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
            int coins = dataConfig.getInt("quests." + questId + ".reward_coins", 0);
            String enchant = dataConfig.getString("quests." + questId + ".reward_enchant", "");
            if (reward > 0) p.setLevel(p.getLevel() + reward);
            if (coins > 0) {
                addCoins(p.getUniqueId(), coins);
                p.sendMessage(ChatColor.GOLD + "+" + coins + " coins awarded!");
            }
            if (!enchant.isEmpty()) {
                unlockEnchant(p, enchant);
                p.sendMessage(ChatColor.LIGHT_PURPLE + "✨ New custom enchant unlocked: " + enchant);
            }
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
    // enchantment unlock helpers
    private boolean hasUnlockedEnchant(Player p, String enchant) {
        List<String> list = dataConfig.getStringList("enchants.unlocked." + p.getUniqueId());
        return list.contains(enchant);
    }
    private void unlockEnchant(Player p, String enchant) {
        List<String> list = new ArrayList<>(dataConfig.getStringList("enchants.unlocked." + p.getUniqueId()));
        if (!list.contains(enchant)) {
            list.add(enchant);
            dataConfig.set("enchants.unlocked." + p.getUniqueId(), list);
            saveDataFile();
        }
    }

    private void ensureDefaultEnchantQuests() {
        // create global quest entries if missing
        if (!dataConfig.contains("quests.enchant_timber")) {
            dataConfig.set("quests.enchant_timber.name", "Timber Master");
            dataConfig.set("quests.enchant_timber.description", "Break 750 logs of any wood");
            dataConfig.set("quests.enchant_timber.type", "break_logs");
            dataConfig.set("quests.enchant_timber.goal", 750);
            dataConfig.set("quests.enchant_timber.reward_enchant", "Timber");
            dataConfig.set("quests.enchant_timber.active", true);
        }
        if (!dataConfig.contains("quests.enchant_veinminer")) {
            dataConfig.set("quests.enchant_veinminer.name", "Ore Miner");
            dataConfig.set("quests.enchant_veinminer.description", "Break 500 ore blocks");
            dataConfig.set("quests.enchant_veinminer.type", "break_ores");
            dataConfig.set("quests.enchant_veinminer.goal", 500);
            dataConfig.set("quests.enchant_veinminer.reward_enchant", "Vein Miner");
            dataConfig.set("quests.enchant_veinminer.active", true);
        }
        if (!dataConfig.contains("quests.enchant_smelting")) {
            dataConfig.set("quests.enchant_smelting.name", "Smelting Touch Quest");
            dataConfig.set("quests.enchant_smelting.description", "Break 750 ores and smelt 250 ores");
            dataConfig.set("quests.enchant_smelting.type", "smelting_touch");
            dataConfig.set("quests.enchant_smelting.goal", 0); // handled specially
            dataConfig.set("quests.enchant_smelting.reward_enchant", "Smelting Touch");
            dataConfig.set("quests.enchant_smelting.active", true);
        }
        if (!dataConfig.contains("quests.enchant_telepathy")) {
            dataConfig.set("quests.enchant_telepathy.name", "Collector");
            dataConfig.set("quests.enchant_telepathy.description", "Pick up 1500 items from the ground");
            dataConfig.set("quests.enchant_telepathy.type", "pickup_items");
            dataConfig.set("quests.enchant_telepathy.goal", 1500);
            dataConfig.set("quests.enchant_telepathy.reward_enchant", "Telepathy");
            dataConfig.set("quests.enchant_telepathy.active", true);
        }
        saveDataFile();
    }

    private void ensureDefaultNpcLibrary() {
        if (!dataConfig.contains("npcLibrary")) {
            List<String> defaults = Arrays.asList("Notch", "jeb_", "Dinnerbone", "Grumm", "Steve", "Alex");
            dataConfig.set("npcLibrary", defaults);
            saveDataFile();
        }
    }

    private void listNpcSkins(Player p) {
        List<String> skins = dataConfig.getStringList("npcLibrary");
        if (skins.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "No NPC skins configured in the library.");
            p.sendMessage(ChatColor.GRAY + "Use /dmt summon <playername> to spawn an NPC with a player skin.");
            return;
        }
        p.sendMessage(ChatColor.AQUA + "Available NPC skins:");
        for (int i = 0; i < skins.size(); i++) {
            p.sendMessage(ChatColor.GRAY + "[" + (i + 1) + "] " + ChatColor.WHITE + skins.get(i));
        }
    }

    private void spawnNpc(Player p, String npcName) {
        // Prefer Citizens NPCs when available (full player skins), otherwise fall back to armor stand with player head.
        String npcIdStr = trySpawnCitizensNpc(p, npcName);
        if (npcIdStr == null) {
            Location loc = p.getLocation().add(0, 1, 0);
            ArmorStand as = (ArmorStand) p.getWorld().spawnEntity(loc, org.bukkit.entity.EntityType.ARMOR_STAND);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setVisible(true);
            as.setCustomName(ChatColor.AQUA + npcName);
            as.setCustomNameVisible(true);
            as.setMarker(false);
            as.setAI(false);
            as.setCollidable(false);
            // Give the stand a player head with the target's skin
            OfflinePlayer skinPlayer = Bukkit.getOfflinePlayer(npcName);
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
            if (skullMeta != null) {
                skullMeta.setOwningPlayer(skinPlayer);
                skull.setItemMeta(skullMeta);
                if (as.getEquipment() != null) as.getEquipment().setHelmet(skull);
            }

            npcIdStr = as.getUniqueId().toString();
            p.sendMessage(ChatColor.GREEN + "NPC summoned (armor stand). Now tell me what it should do when interacted with (shop / teleport):");
        } else {
            p.sendMessage(ChatColor.GREEN + "NPC summoned (Citizens). Now tell me what it should do when interacted with (shop / teleport):");
        }

        dataConfig.set("summons." + npcIdStr + ".name", npcName);
        dataConfig.set("summons." + npcIdStr + ".owner", p.getUniqueId().toString());
        dataConfig.set("summons." + npcIdStr + ".type", "");
        saveDataFile();

        pendingActions.put(p.getUniqueId(), new PunishmentContext(npcIdStr, ActionType.SUMMON_NPC));
    }

    private void addNpcSkinFromSkinstealer(Player p, String username) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean exists = isSkinAvailableOnMinecraftTools(username);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!exists) {
                    p.sendMessage(ChatColor.RED + "Could not find a skin for '" + username + "' on minecraft.tools.");
                    p.sendMessage(ChatColor.GRAY + "Make sure the username is correct and try again.");
                    return;
                }
                List<String> skins = new ArrayList<>(dataConfig.getStringList("npcLibrary"));
                if (skins.contains(username)) {
                    p.sendMessage(ChatColor.YELLOW + "NPC library already contains '" + username + "'.");
                    return;
                }
                skins.add(username);
                dataConfig.set("npcLibrary", skins);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Added '" + username + "' to the NPC library (verified via minecraft.tools).");
            });
        });
    }

    private String trySpawnCitizensNpc(Player p, String npcName) {
        try {
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Method getRegistry = citizensApi.getMethod("getNPCRegistry");
            Object registry = getRegistry.invoke(null);
            Class<?> registryClass = Class.forName("net.citizensnpcs.api.npc.NPCRegistry");
            Method createNPC = registryClass.getMethod("createNPC", EntityType.class, String.class);
            Object npc = createNPC.invoke(registry, EntityType.PLAYER, npcName);

            // Try applying skin via Citizens SkinTrait if available
            try {
                Class<?> skinTraitClass = Class.forName("net.citizensnpcs.api.trait.trait.SkinTrait");
                Method getTrait = npc.getClass().getMethod("getTrait", Class.class);
                Object skinTrait = getTrait.invoke(npc, skinTraitClass);
                if (skinTrait != null) {
                    Method setSkinName = skinTraitClass.getMethod("setSkinName", String.class);
                    setSkinName.invoke(skinTrait, npcName);
                }
            } catch (ClassNotFoundException ignored) {
                // SkinTrait not available, ignore
            }

            Method spawn = npc.getClass().getMethod("spawn", Location.class);
            spawn.invoke(npc, p.getLocation().add(0, 1, 0));
            Method getId = npc.getClass().getMethod("getId");
            Object idObj = getId.invoke(npc);
            if (idObj != null) {
                return "citizens:" + idObj.toString();
            }
        } catch (Exception ignored) {
            // Citizens not installed or failed; fall back to armor stand
        }
        return null;
    }

    private void removeNpcSkin(Player p, String username) {
        List<String> skins = new ArrayList<>(dataConfig.getStringList("npcLibrary"));
        if (!skins.remove(username)) {
            p.sendMessage(ChatColor.RED + "NPC library does not contain '" + username + "'.");
            return;
        }
        dataConfig.set("npcLibrary", skins);
        saveDataFile();
        p.sendMessage(ChatColor.GREEN + "Removed '" + username + "' from the NPC library.");
    }

    private boolean isSkinAvailableOnMinecraftTools(String username) {
        try {
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
            // minecraft.tools provides skin previews via /en/skin.php?user=<username>
            URL url = new URL("https://minecraft.tools/en/skin.php?user=" + encoded);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

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
                                int coinReward = dataConfig.getInt("playtime_rewards." + rewardId + ".coins", 0);
                                String kit = dataConfig.getString("playtime_rewards." + rewardId + ".kit", "");
                                if (xpReward > 0) p.setLevel(p.getLevel() + xpReward);
                                if (coinReward > 0) {
                                    addCoins(p.getUniqueId(), coinReward);
                                    p.sendMessage(ChatColor.GOLD + "+" + coinReward + " coins awarded!");
                                }
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
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // determine first pending enchant quest and notify
        List<String> order = Arrays.asList("Timber", "Vein Miner", "Smelting Touch", "Telepathy");
        for (String ench : order) {
            if (!hasUnlockedEnchant(p, ench)) {
                p.sendMessage(ChatColor.AQUA + "Quest available: earn the " + ench + " enchant! Use /quest to view.");
                break;
            }
        }
    }

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
        lore.add(ChatColor.BLUE + enchantName); // color like normal enchantments
        meta.setLore(lore);
        // add a harmless real enchantment to make item glow and display an enchant line
        // use Luck of the Sea which is safe and exists in this server version
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        item.setItemMeta(meta);
        return true;
    }

    private void spawnHologram(Player p, List<String> lines) {
        Location base = p.getLocation().add(0, 1.6, 0);
        World world = p.getWorld();
        double spacing = 0.25;
        for (int i = 0; i < lines.size(); i++) {
            Location loc = base.clone().add(0, -i * spacing, 0);
            ArmorStand as = (ArmorStand) world.spawnEntity(loc, org.bukkit.entity.EntityType.ARMOR_STAND);
            as.setVisible(false);
            as.setGravity(false);
            as.setMarker(true);
            as.setInvulnerable(true);
            as.setCustomName(ChatColor.translateAlternateColorCodes('&', lines.get(i)));
            as.setCustomNameVisible(true);
        }
    }

    private void openNpcShop(Player p, String npcId) {
        String name = dataConfig.getString("summons." + npcId + ".name", "NPC");
        List<String> items = dataConfig.getStringList("summons." + npcId + ".shop_items");
        int size = Math.max(9, ((items.size() + 1 + 8) / 9) * 9);
        if (size > 54) size = 54;
        Inventory gui = Bukkit.createInventory(null, size, GUI_NPC_SHOP + " - " + name + " (" + npcId + ")");
        for (int i = 0; i < items.size() && i < size - 1; i++) {
            String entry = items.get(i);
            String[] parts = entry.split(":");
            if (parts.length < 3) continue;
            Material mat;
            try { mat = Material.valueOf(parts[0]); } catch (Exception ex) { continue; }
            int amount;
            long price;
            try { amount = Integer.parseInt(parts[1]); price = Long.parseLong(parts[2]); }
            catch (Exception ex) { continue; }
            ItemStack item = new ItemStack(mat, amount);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setLore(Arrays.asList(ChatColor.GREEN + "Price: " + price + " coins", ChatColor.GRAY + "Click to buy"));
                item.setItemMeta(meta);
            }
            gui.setItem(i, item);
        }
        gui.setItem(size - 1, createGuiItem(Material.BARRIER, ChatColor.RED + "Back"));
        p.openInventory(gui);
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent e) {
        Player p = e.getPlayer();
        ItemStack i = e.getItem().getItemStack();
        trackQuestProgress(p, "pickup_items", i.getAmount());
    }

    @EventHandler
    public void onFurnaceTake(InventoryClickEvent e) {
        if (e.getInventory().getType() == InventoryType.FURNACE && e.getRawSlot() == 2) {
            if (e.getWhoClicked() instanceof Player) {
                Player p2 = (Player) e.getWhoClicked();
                ItemStack result = e.getCurrentItem();
                if (result != null && result.getType() != Material.AIR) {
                    trackQuestProgress(p2, "smelt_ores", result.getAmount());
                }
            }
        }
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
                lore.add(ChatColor.YELLOW + "Current Bid: " + currentBid + " coins");
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
        // Ensure only one set of announcement tasks is running at a time
        stopScheduledAnnouncements();

        int intervalTicks = dataConfig.getInt("announcements.interval_minutes", 5) * 20 * 60;
        if (intervalTicks <= 0) intervalTicks = 6000;
        scheduledAnnouncementsTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!dataConfig.getBoolean("announcements.enabled", false)) return;
            List<String> messages = dataConfig.getStringList("announcements.messages");
            if (messages.isEmpty()) return;
            int index = dataConfig.getInt("announcements.current_index", 0);
            if (index >= messages.size()) index = 0;
            String msg = ChatColor.translateAlternateColorCodes('&', messages.get(index));
            String prefix = ChatColor.translateAlternateColorCodes('&', dataConfig.getString("announcements.prefix", "&6[&eAnnouncement&6]&r "));
            Bukkit.broadcastMessage(prefix + msg);
            dataConfig.set("announcements.current_index", index + 1);
        }, intervalTicks, intervalTicks).getTaskId();

        // One-time scheduled announcements checker (every 30 seconds = 600 ticks)
        scheduledAnnouncementsOneTimeCheckTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
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

        // Command scheduler checker (every 30 seconds = 600 ticks)
        scheduledCommandsTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            List<Map<?, ?>> raw = (List<Map<?, ?>>) dataConfig.getList("scheduler.commands", new ArrayList<>());
            if (raw.isEmpty()) return;
            boolean changed = false;
            long now = System.currentTimeMillis();
            for (Map<?, ?> entry : raw) {
                Object sentObj = entry.get("sent");
                boolean sent = sentObj instanceof Boolean && (Boolean) sentObj;
                if (sent) continue;
                String timeStr = String.valueOf(entry.get("time"));
                try {
                    long targetMs = java.time.LocalDateTime.parse(timeStr).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (now >= targetMs) {
                        String command = String.valueOf(entry.get("command"));
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                        ((Map) entry).put("sent", true);
                        changed = true;
                        logAction("System", "scheduled_cmd", command);
                    }
                } catch (Exception ignored) {}
            }
            if (changed) {
                dataConfig.set("scheduler.commands", raw);
                saveDataFile();
            }
        }, 600L, 600L);
    }

    private void stopScheduledAnnouncements() {
        if (scheduledAnnouncementsTaskId != -1) {
            Bukkit.getScheduler().cancelTask(scheduledAnnouncementsTaskId);
            scheduledAnnouncementsTaskId = -1;
        }
        if (scheduledAnnouncementsOneTimeCheckTaskId != -1) {
            Bukkit.getScheduler().cancelTask(scheduledAnnouncementsOneTimeCheckTaskId);
            scheduledAnnouncementsOneTimeCheckTaskId = -1;
        }
        if (scheduledCommandsTaskId != -1) {
            Bukkit.getScheduler().cancelTask(scheduledCommandsTaskId);
            scheduledCommandsTaskId = -1;
        }
    }

    public void restartScheduledAnnouncements() {
        // Safe to call from any thread
        Bukkit.getScheduler().runTask(this, this::startScheduledAnnouncements);
    }

    private void startMaintenanceChecker() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            boolean changed = false;
            boolean currentState = dataConfig.getBoolean("maintenance.enabled", false);
            long now = System.currentTimeMillis();

            String startStr = dataConfig.getString("maintenance.startTime", "");
            if (startStr != null && !startStr.isEmpty()) {
                try {
                    long startMs = java.time.LocalDateTime.parse(startStr).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (now >= startMs) {
                        dataConfig.set("maintenance.startTime", "");
                        changed = true;
                        if (!currentState) {
                            dataConfig.set("maintenance.enabled", true);
                            currentState = true;
                            String msg = dataConfig.getString("maintenance.message", "Server is under maintenance...");
                            Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "[Maintenance] " + ChatColor.RESET + ChatColor.RED + "Scheduled maintenance has started.");
                            List<String> whitelist = dataConfig.getStringList("maintenance.whitelist");
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                if (!whitelist.contains(p.getName())) p.kickPlayer(ChatColor.RED + msg);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            String endStr = dataConfig.getString("maintenance.endTime", "");
            if (endStr != null && !endStr.isEmpty()) {
                try {
                    long endMs = java.time.LocalDateTime.parse(endStr).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (now >= endMs) {
                        dataConfig.set("maintenance.endTime", "");
                        changed = true;
                        if (currentState) {
                            dataConfig.set("maintenance.enabled", false);
                            Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "[Maintenance] " + ChatColor.RESET + ChatColor.GREEN + "Maintenance mode has ended. The server is open!");
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (changed) saveDataFile();
        }, 200L, 200L);
    }

    // ========== EVENT EFFECTS ==========
    private static final Set<org.bukkit.block.Biome> HOT_BIOMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
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
    )));

    private static final Set<org.bukkit.block.Biome> COLD_BIOMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
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
    )));

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
            case "christmas":
                startChristmasSnow();
                break;
            case "halloween":
                startHalloweenEffect();
                break;
            case "newyear":
                startNewYearEffect();
                break;
            case "valentine":
                startValentineEffect();
                break;
            case "spring":
                startSpringEffect();
                break;
            case "summer":
                startSummerEffect();
                break;
            default:
                break;
        }
    }

    /** Stop the effect for the given event name. Called from WebServer. */
    public void stopEventEffect(String eventName) {
        switch (eventName.toLowerCase()) {
            case "christmas":
                stopChristmasSnow();
                break;
            case "halloween":
                stopHalloweenEffect();
                break;
            case "newyear":
                stopNewYearEffect();
                break;
            case "valentine":
                stopValentineEffect();
                break;
            case "spring":
                stopSpringEffect();
                break;
            case "summer":
                stopSummerEffect();
                break;
            default:
                break;
        }
    }

}

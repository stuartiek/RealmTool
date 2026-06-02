package com.stuart.javarealmtool;

import com.stuart.javarealmtool.commands.BalanceCommand;
import com.stuart.javarealmtool.commands.FactionCommand;
import com.stuart.javarealmtool.commands.MaintenanceCommand;
import com.stuart.javarealmtool.commands.TicketCommand;
import com.stuart.javarealmtool.services.EconomyService;
import com.stuart.javarealmtool.services.FactionService;
import com.stuart.javarealmtool.services.LocalNetworkModerationService;
import com.stuart.javarealmtool.services.LocalNetworkProfileService;
import com.stuart.javarealmtool.services.LocalNetworkTokenService;
import com.stuart.javarealmtool.services.NetworkModerationService;
import com.stuart.javarealmtool.services.NetworkPunishment;
import com.stuart.javarealmtool.services.NetworkProfileService;
import com.stuart.javarealmtool.services.RankService;
import com.stuart.javarealmtool.services.NetworkWarning;
import com.stuart.javarealmtool.services.NetworkTokenService;
import com.stuart.javarealmtool.services.SharedNetworkDatabase;
import com.stuart.javarealmtool.services.SharedNetworkModerationService;
import com.stuart.javarealmtool.services.SharedNetworkProfileService;
import com.stuart.javarealmtool.services.SharedNetworkTokenService;
import com.stuart.javarealmtool.services.TicketService;
import org.bukkit.*;
import org.bukkit.GameRule;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.*;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.*;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private File playersFile;
    private FileConfiguration playersConfig;
    private File economyFile;
    private FileConfiguration economyConfig;
    private File ticketFile;
    private FileConfiguration ticketConfig;
    private File rankFile;
    private FileConfiguration rankConfig;
    private Scoreboard scoreboard;
    private Team punishTeam;
    private WebServer webServer;
    private EconomyService economyService;
    private FactionService factionService;
    private SharedNetworkDatabase sharedNetworkDatabase;
    private NetworkModerationService networkModerationService;
    private NetworkProfileService networkProfileService;
    private NetworkTokenService networkTokenService;
    private String apiKey;
    private final Map<UUID, PunishmentContext> pendingActions = new HashMap<>();
    private final Map<String, Integer> pendingNoteEdit = new HashMap<>();
    private volatile boolean dataConfigDirty = false;
    private volatile boolean playersConfigDirty = false;
    private volatile boolean economyConfigDirty = false;
    private volatile boolean ticketConfigDirty = false;
    private volatile boolean rankConfigDirty = false;
    private int autoSaveTaskId = -1;
    private final Object saveLock = new Object();
    
    // pending world creation/chat actions are handled via pendingActions and a new enum value

    private static final int CLAIM_PROXIMITY_RADIUS = 2; // blocks cannot be claimed within this chunk distance from existing claims

    private final Map<UUID, UUID> tpaRequests = new HashMap<>();
    private final Map<UUID, String> pendingWarpDelete = new HashMap<>();
    private final Map<UUID, String> pendingPwarpAction = new HashMap<>();
    private final Map<UUID, String> pendingClaimAction = new HashMap<>();
    private final Map<UUID, String> pendingTrustAction = new HashMap<>();
    private final Map<UUID, Integer> menuPages = new HashMap<>();
    private final Map<UUID, String> menuOrigin = new HashMap<>();
    private final Map<UUID, String> selectedWorld = new HashMap<>();
    private final Map<UUID, String> selectedHologram = new HashMap<>();
    private final Map<UUID, String> currentChunk = new ConcurrentHashMap<>();
    private final Map<String, Material> chunksCornerBlocks = new HashMap<>();
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastToolMenuOpenAt = new ConcurrentHashMap<>();
    private final Map<UUID, CachedPunishmentState> punishmentCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activePunishmentExpiries = new ConcurrentHashMap<>();
    private volatile CachedWarningsSnapshot warningsSnapshotCache;
    private volatile CachedPunishmentRecordsSnapshot activePunishmentRecordsSnapshotCache;
    private long lastStaffHourPruneEpochHour = Long.MIN_VALUE;
    private final Map<UUID, PermissionAttachment> permissionAttachments = new HashMap<>();
    private final Map<String, UUID> claimOwnerIndex = new ConcurrentHashMap<>();
    private final Object claimOwnerIndexLock = new Object();
    private volatile boolean claimOwnerIndexDirty = true;
    private int gridSlotIndex = 0;
    private int gridRowIndex = 0;

    private static final String STAFF_HOUR_BUCKETS_PATH = "staff_hours.hourly";
    private static final String STAFF_HOUR_NAMES_PATH = "staff_hours.names";
    private static final int STAFF_HOUR_RETENTION_HOURS = 24 * 14;
    private static final long PUNISHMENT_CACHE_TTL_MS = 1000L;
    private static final long MODERATION_AGGREGATE_CACHE_TTL_MS = 5000L;
    private static final long AUTO_SAVE_INITIAL_DELAY_TICKS = 600L;
    private static final long AUTO_SAVE_INTERVAL_TICKS = 100L;
    private static final long TOOL_MENU_OPEN_DEBOUNCE_MS = 750L;
    private static final String MANAGED_WORLDS_PATH = "managed_worlds";

    private record CachedPunishmentState(NetworkPunishment punishment, long refreshUntil) {}
    private record CachedWarningsSnapshot(Map<UUID, List<NetworkWarning>> warnings, long expiresAt) {}
    private record CachedPunishmentRecordsSnapshot(Map<UUID, NetworkPunishment> punishments, long expiresAt) {}

    // Personal miner NPC helpers
    private static final Set<String> PERSONAL_CONTROL_USERS = Set.of("Will_Aetos", "Pokyopossum531");
    private static final List<Material> PERSONAL_ORES = Arrays.asList(
        Material.COAL_ORE,
        Material.IRON_ORE,
        Material.GOLD_ORE,
        Material.DIAMOND_ORE,
        Material.EMERALD_ORE,
        Material.REDSTONE_ORE,
        Material.LAPIS_ORE,
        Material.COPPER_ORE,
        Material.NETHER_QUARTZ_ORE,
        Material.ANCIENT_DEBRIS
    );

    private static final Map<Material, Material> ORE_TO_DROP = Map.ofEntries(
        Map.entry(Material.COAL_ORE, Material.COAL),
        Map.entry(Material.IRON_ORE, Material.RAW_IRON),
        Map.entry(Material.GOLD_ORE, Material.RAW_GOLD),
        Map.entry(Material.DIAMOND_ORE, Material.DIAMOND),
        Map.entry(Material.EMERALD_ORE, Material.EMERALD),
        Map.entry(Material.REDSTONE_ORE, Material.REDSTONE),
        Map.entry(Material.LAPIS_ORE, Material.LAPIS_LAZULI),
        Map.entry(Material.COPPER_ORE, Material.RAW_COPPER),
        Map.entry(Material.NETHER_QUARTZ_ORE, Material.QUARTZ),
        Map.entry(Material.ANCIENT_DEBRIS, Material.ANCIENT_DEBRIS)
    );

    private final Map<UUID, MinerState> personalMiners = new HashMap<>();

    private static class MinerState {
        ArmorStand minerEntity;
        Location oreLocation;
        Map<Material, Integer> inventory = new HashMap<>();
        int taskId;
        int forcedChunkX = Integer.MIN_VALUE;
        int forcedChunkZ = Integer.MIN_VALUE;
    }

    private void forceChunkForMiner(MinerState ms) {
        if (ms == null || ms.minerEntity == null || ms.minerEntity.isDead()) return;
        Chunk chunk = ms.minerEntity.getLocation().getChunk();
        if (chunk != null) {
            chunk.setForceLoaded(true);
            ms.forcedChunkX = chunk.getX();
            ms.forcedChunkZ = chunk.getZ();
        }
    }

    private void unforceChunkForMiner(MinerState ms) {
        if (ms == null) return;
        if (ms.forcedChunkX == Integer.MIN_VALUE || ms.forcedChunkZ == Integer.MIN_VALUE) return;
        World world = ms.minerEntity != null && !ms.minerEntity.isDead() ? ms.minerEntity.getWorld() : null;
        if (world == null) return;
        Chunk chunk = world.getChunkAt(ms.forcedChunkX, ms.forcedChunkZ);
        if (chunk != null && chunk.isForceLoaded()) {
            chunk.setForceLoaded(false);
        }
        ms.forcedChunkX = Integer.MIN_VALUE;
        ms.forcedChunkZ = Integer.MIN_VALUE;
    }

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
    private final String GUI_PWARP_MANAGE = ChatColor.AQUA + "Player Warp: ";
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
    private TicketService ticketService;
    private RankService rankService;
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
    private int leaderboardUpdaterTaskId = -1;

    // Scheduled announcements task IDs (used for reloading config without restart)
    private int scheduledAnnouncementsTaskId = -1;
    private int scheduledAnnouncementsOneTimeCheckTaskId = -1;
    private int scheduledCommandsTaskId = -1;

    private final Map<UUID, Long> reportCooldowns = new HashMap<>();
    private final Set<UUID> toolRespawnQueue = new HashSet<>();

    // Invisible staff monitoring state
    private final Set<UUID> invisiblePlayers = ConcurrentHashMap.newKeySet();

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

        // Hologram extra state
        String hologramMode; // "regular" or "teleport"

        PunishmentContext(String n, ActionType t) { this.targetName = n; this.type = t; }
    }

    private static class StaffHourSummary {
        private final UUID uuid;
        private final String name;
        private final long minutes24h;
        private final long minutes7d;
        private final long minutes14d;

        private StaffHourSummary(UUID uuid, String name, long minutes24h, long minutes7d, long minutes14d) {
            this.uuid = uuid;
            this.name = name;
            this.minutes24h = minutes24h;
            this.minutes7d = minutes7d;
            this.minutes14d = minutes14d;
        }
    }

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            setupConfig();
            setupDataFiles();
            ensureDefaultNpcLibrary();
            ensureDefaultEnchantQuests();
            setupPunishTeam();

            economyService = new EconomyService(this);
            ticketService = new TicketService(this);
            rankService = new RankService(this);
            factionService = new FactionService(this);
            initializeNetworkServices();
            factionService.ensureConfigDefaults();

            Bukkit.getPluginManager().registerEvents(this, this);
            registerCitizensClickListener();

            // Reload managed admin-created worlds first, then any remaining world folders on disk.
            loadPersistedWorlds();

            // --- MIGRATE EXISTING RANK COLORS ---
            boolean configMigrated = false;
            if (dataConfig.contains("ranks")) {
                for (String rName : dataConfig.getConfigurationSection("ranks").getKeys(false)) {
                    String path = "ranks." + rName;
                    String col = dataConfig.getString(path + ".color");
                    // If the color is missing or set to the default white/gray, force an update from the prefix
                    if (col == null || col.isEmpty() || col.equals("#ffffff") || col.equals("#aaaaaa")) {
                        String pref = dataConfig.getString(path + ".prefix", "");
                        String inferred = inferHexColorFromPrefix(pref);
                        if (!inferred.equals("#ffffff") && (col == null || !col.equals(inferred))) {
                            dataConfig.set(path + ".color", inferred);
                            configMigrated = true;
                        }
                    }
                }
            }
            if (dataConfig.contains("groups")) {
                for (String gName : dataConfig.getConfigurationSection("groups").getKeys(false)) {
                    String path = "groups." + gName;
                    String col = dataConfig.getString(path + ".color");
                    if (col == null || col.isEmpty() || col.equals("#ffffff") || col.equals("#aaaaaa")) {
                        String pref = dataConfig.getString(path + ".prefix", "");
                        String inferred = inferHexColorFromPrefix(pref);
                        if (!inferred.equals("#ffffff") && (col == null || !col.equals(inferred))) {
                            dataConfig.set(path + ".color", inferred);
                            configMigrated = true;
                        }
                    }
                }
            }
            if (configMigrated) {
                saveDataFile();
            }

            // Debug: verify migration data sources after startup
            getLogger().info("players.yml contains: " + playersConfig.getKeys(false));
            getLogger().info("data.yml contains: " + dataConfig.getKeys(false));
            getLogger().info("economy.yml contains coins? " + economyConfig.contains("coins"));
            getLogger().info("ranks.yml contains ranks? " + rankConfig.contains("ranks"));
            getLogger().info("tickets.yml contains tickets? " + ticketConfig.contains("tickets"));

            List<String> registeredCommands = new ArrayList<>();
            List<String> missingCommands = new ArrayList<>();
            TicketCommand ticketCmd = new TicketCommand(this);
            BalanceCommand balanceCmd = new BalanceCommand(this);
            FactionCommand factionCommand = new FactionCommand(factionService);
            MaintenanceCommand maintenanceCommand = new MaintenanceCommand(this);

            registerCommand("dmt", this, this, registeredCommands, missingCommands);
            registerCommand("ticket", ticketCmd, ticketCmd, registeredCommands, missingCommands);
            registerCommand("tpa", this, this, registeredCommands, missingCommands);
            registerCommand("kit", this, this, registeredCommands, missingCommands);
            registerCommand("bounty", this, this, registeredCommands, missingCommands);
            registerCommand("shop", this, this, registeredCommands, missingCommands);
            registerCommand("quest", this, this, registeredCommands, missingCommands);
            registerCommand("apply", this, this, registeredCommands, missingCommands);
            registerCommand("vote", this, this, registeredCommands, missingCommands);
            registerCommand("crate", this, this, registeredCommands, missingCommands);
            registerCommand("nick", this, this, registeredCommands, missingCommands);
            registerCommand("rules", this, this, registeredCommands, missingCommands);
            registerCommand("duel", this, this, registeredCommands, missingCommands);
            registerCommand("pwarp", this, this, registeredCommands, missingCommands);
            registerCommand("achievements", this, this, registeredCommands, missingCommands);
            registerCommand("stats", this, this, registeredCommands, missingCommands);
            registerCommand("report", this, this, registeredCommands, missingCommands);
            registerCommand("balance", balanceCmd, balanceCmd, registeredCommands, missingCommands);
            registerCommand("economy", this, this, registeredCommands, missingCommands);
            registerCommand("discord", this, this, registeredCommands, missingCommands);
            registerCommand("maintenance", maintenanceCommand, maintenanceCommand, registeredCommands, missingCommands);
            registerCommand("spawn", this, this, registeredCommands, missingCommands);
            registerCommand("factions", this, this, registeredCommands, missingCommands);
            registerCommand("f", factionCommand, factionCommand, registeredCommands, missingCommands);
            registerCommand("personal", this, this, registeredCommands, missingCommands);

            getLogger().info("Command registration summary: registered=" + registeredCommands + ", missing=" + missingCommands);

            // Hide /personal commands from console logs
            try {
                final Filter hidePersonal = record -> {
                    if (record == null || record.getMessage() == null) return true;
                    String msg = record.getMessage();
                    if (msg.contains("issued server command: /personal") || msg.contains("issued server command: /personal npc")) {
                        return false;
                    }
                    return true;
                };

                List<Logger> loggers = new ArrayList<>();
                loggers.add(Logger.getLogger(""));
                loggers.add(Bukkit.getLogger());
                Logger minecraftLogger = java.util.logging.LogManager.getLogManager().getLogger("Minecraft");
                if (minecraftLogger != null) loggers.add(minecraftLogger);
                Logger bukkitCraftLogger = java.util.logging.LogManager.getLogManager().getLogger("org.bukkit.craftbukkit");
                if (bukkitCraftLogger != null) loggers.add(bukkitCraftLogger);
                Logger bukkitLogger = java.util.logging.LogManager.getLogManager().getLogger("org.bukkit");
                if (bukkitLogger != null) loggers.add(bukkitLogger);

                for (Logger logger : loggers) {
                    if (logger == null) continue;
                    logger.setFilter(hidePersonal);
                    for (Handler handler : logger.getHandlers()) {
                        handler.setFilter(hidePersonal);
                    }
                }

                // Try Log4j root logger also (e.g., modern server logging path)
                try {
                    Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
                    Class<?> log4jLoggerClass = Class.forName("org.apache.logging.log4j.core.Logger");
                    Class<?> filterClass = Class.forName("org.apache.logging.log4j.core.Filter");
                    Class<?> resultClass = Class.forName("org.apache.logging.log4j.core.Filter$Result");

                    Object deny = Enum.valueOf((Class<Enum>) resultClass, "DENY");
                    Object neutral = Enum.valueOf((Class<Enum>) resultClass, "NEUTRAL");

                    InvocationHandler log4jHandler = (proxy, method, args2) -> {
                        if ("filter".equals(method.getName()) && args2 != null && args2.length == 1 && args2[0] != null) {
                            Object event = args2[0];
                            Method getMessage = event.getClass().getMethod("getMessage");
                            Object messageObj = getMessage.invoke(event);
                            if (messageObj != null) {
                                Method getFormattedMessage = messageObj.getClass().getMethod("getFormattedMessage");
                                String msg = (String) getFormattedMessage.invoke(messageObj);
                                if (msg != null && msg.contains("issued server command: /personal")) {
                                    return deny;
                                }
                            }
                        }
                        if (method.getReturnType().isAssignableFrom(resultClass)) {
                            return neutral;
                        }
                        return null;
                    };

                    Object log4jFilter = Proxy.newProxyInstance(log4jLoggerClass.getClassLoader(), new Class[]{filterClass}, log4jHandler);

                    Method getRootLogger = logManagerClass.getMethod("getRootLogger");
                    Object rootLog4j = getRootLogger.invoke(null);
                    Method addFilter = log4jLoggerClass.getMethod("addFilter", filterClass);
                    addFilter.invoke(rootLog4j, log4jFilter);

                    Method getLogger = logManagerClass.getMethod("getLogger", String.class);
                    Object minecraftLog4j = getLogger.invoke(null, "Minecraft");
                    if (minecraftLog4j != null) {
                        addFilter.invoke(minecraftLog4j, log4jFilter);
                    }
                } catch (ClassNotFoundException ignored2) {
                                    // Citizens not present; ignore.
                } catch (Exception ignored2) {
                    // ignore other reflection problems
                }

            } catch (Exception ignored) {}

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

        // Start Leaderboard updater (every 5 minutes = 6000 ticks)
        startLeaderboardUpdater();

        // Start Maintenance mode checker (every 10 seconds = 200 ticks)
        startMaintenanceChecker();

        // Resume event effects if events were active before restart
        ConfigurationSection activeEvents = dataConfig.getConfigurationSection("events.active");
        if (activeEvents != null) {
            for (String eventName : activeEvents.getKeys(false)) {
                startEventEffect(eventName);
            }
        }

        // Apply ranks + permissions for online players in case of reload
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyRankToPlayer(p);
            applyPermissionGroup(p);
        }

        // Start anti-lag ground item cleanup (configurable)
        startAntiLagCleanup();

        // Start auto-save shortly after startup rather than during the enable window.
        autoSaveTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (dataConfigDirty || playersConfigDirty || economyConfigDirty) {
                performDataSave();
            }
        }, AUTO_SAVE_INITIAL_DELAY_TICKS, AUTO_SAVE_INTERVAL_TICKS);

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

        closeSharedNetworkDatabase();

        if (autoSaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoSaveTaskId);
        }

        for (MinerState ms : personalMiners.values()) {
            if (ms.taskId != -1) Bukkit.getScheduler().cancelTask(ms.taskId);
            unforceChunkForMiner(ms);
            if (ms.minerEntity != null && !ms.minerEntity.isDead()) ms.minerEntity.remove();
            if (ms.oreLocation != null && ms.oreLocation.getBlock().getType() != Material.AIR) {
                ms.oreLocation.getBlock().setType(Material.AIR);
            }
        }
        personalMiners.clear();

        saveLoadedWorlds();

        saveDataFile();
        if (playersConfigDirty) {
            try {
                synchronized (saveLock) {
                    playersConfig.save(playersFile);
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not save players.yml on disable", e);
            }
        }
        if (economyConfigDirty) {
                try {
                    synchronized (saveLock) {
                    economyConfig.save(economyFile);
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not save economy.yml on disable", e);
            }
        }
        if (dataConfigDirty) {
            try {
                synchronized (saveLock) {
                    dataConfig.save(dataFile);
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not save data on disable", e);
            }
        }
        getLogger().info("DrowsyManagementTool has been disabled.");
    }

    private void initializeNetworkServices() {
        NetworkModerationService localModerationService = new LocalNetworkModerationService(this);
        NetworkProfileService localProfileService = new LocalNetworkProfileService(this);
        NetworkTokenService localTokenService = new LocalNetworkTokenService(this);
        networkModerationService = localModerationService;
        networkProfileService = localProfileService;
        networkTokenService = localTokenService;

        if (!isNetworkEnabled()) {
            return;
        }

        if (isNetworkSharedDatabaseEnabled()) {
            try {
                sharedNetworkDatabase = new SharedNetworkDatabase(this);
                networkModerationService = new SharedNetworkModerationService(this, sharedNetworkDatabase, localModerationService);
                networkProfileService = new SharedNetworkProfileService(this, sharedNetworkDatabase, localProfileService);
                networkTokenService = new SharedNetworkTokenService(this, sharedNetworkDatabase, localTokenService);
                getLogger().info("Network shared database backend enabled: " + networkProfileService.getBackendName());
            } catch (Exception exception) {
                closeSharedNetworkDatabase();
                networkModerationService = localModerationService;
                networkProfileService = localProfileService;
                networkTokenService = localTokenService;
                getLogger().log(Level.WARNING, "Failed to initialize the staged shared database backend. Falling back to local network services.", exception);
            }
        }

        if (isNetworkProxyEnabled()) {
            getLogger().info("Network proxy staging is configured, but live player transfer routing is still local-only.");
        }
    }

    private void closeSharedNetworkDatabase() {
        if (sharedNetworkDatabase == null) {
            return;
        }

        try {
            sharedNetworkDatabase.close();
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "Failed to close the staged shared database backend cleanly.", exception);
        } finally {
            sharedNetworkDatabase = null;
        }
    }

    public FileConfiguration getDataConfig() { return dataConfig; }
    public FileConfiguration getPlayersConfig() { return playersConfig; }
    public FileConfiguration getEconomyConfig() { return economyConfig; }
    public FactionService getFactionService() { return factionService; }
    public String fetchApiKey() { return apiKey; }
    public Map<UUID, Long> getTicketCooldowns() { return ticketService != null ? ticketService.getTicketCooldowns() : new HashMap<>(); }
    public boolean isStaffMember(Player player) { return hasAnyStaffRole(player); }

    public boolean isPunished(UUID u) {
        long expiry = getPunishmentExpiry(u);
        if (expiry <= 0L) return false;
        if (System.currentTimeMillis() > expiry) {
            removePunishment(u);
            return false;
        }
        return true;
    }

    private void setupConfig() {
        FileConfiguration config = getConfig();
        boolean changed = false;
        if (!config.contains("api-key")) {
            config.set("api-key", UUID.randomUUID().toString());
            changed = true;
        }
        if (!config.contains("web.host")) {
            config.set("web.host", "0.0.0.0");
            changed = true;
        }
        if (!config.contains("web.port")) {
            config.set("web.port", 8091);
            changed = true;
        }
        if (!config.contains("web.public_url")) {
            config.set("web.public_url", "");
            changed = true;
        }
        if (ensureNetworkConfig(config)) {
            changed = true;
        }
        if (!config.contains("factions_world.visible")) {
            config.set("factions_world.visible", true);
            changed = true;
        }
        if (!config.contains("factions_world.claims_allowed_worlds")) {
            config.set("factions_world.claims_allowed_worlds", new ArrayList<String>());
            changed = true;
        }
        if (!config.contains("factions_core.enabled")) {
            config.set("factions_core.enabled", true);
            config.set("factions_core.min_name_length", 3);
            config.set("factions_core.max_name_length", 12);
            config.set("factions_core.power.max_per_player", 10.0D);
            config.set("factions_core.power.death_penalty", 2.0D);
            config.set("factions_core.power.regen_per_hour", 1.0D);
            config.set("factions_core.power.min_power", 0.0D);
            config.set("factions_core.raid.require_overclaim_for_explosions", true);
            config.set("factions_core.raid.alert_cooldown_seconds", 300);
            config.set("factions_core.raid.web_push", true);
            config.set("factions_core.logs.max_entries", 30);
            config.set("factions_core.homes.allow_teleport", true);
            config.set("factions_core.homes.require_safe_claim", true);
            config.set("factions_core.discord.enabled", false);
            config.set("factions_core.discord.webhook_override", "");
            changed = true;
        }
        if (changed) {
            saveConfig();
        }
        this.apiKey = config.getString("api-key");
    }

    private boolean ensureNetworkConfig(FileConfiguration config) {
        boolean changed = false;

        if (!config.contains("network.enabled")) {
            config.set("network.enabled", false);
            changed = true;
        }
        if (!config.contains("network.staging_only")) {
            config.set("network.staging_only", true);
            changed = true;
        }
        if (!config.contains("network.proxy.enabled")) {
            config.set("network.proxy.enabled", false);
            changed = true;
        }
        if (!config.contains("network.proxy.type")) {
            config.set("network.proxy.type", "velocity");
            changed = true;
        }
        if (!config.contains("network.proxy.server_name")) {
            config.set("network.proxy.server_name", "survival");
            changed = true;
        }
        if (!config.contains("network.proxy.hub_server")) {
            config.set("network.proxy.hub_server", "hub");
            changed = true;
        }
        if (!config.contains("network.proxy.fallback_server")) {
            config.set("network.proxy.fallback_server", "survival");
            changed = true;
        }
        if (!config.contains("network.proxy.plugin_channel")) {
            config.set("network.proxy.plugin_channel", "drowsycraft:network");
            changed = true;
        }
        if (!config.contains("network.shared_database.enabled")) {
            config.set("network.shared_database.enabled", false);
            changed = true;
        }
        if (!config.contains("network.shared_database.provider")) {
            config.set("network.shared_database.provider", "postgresql");
            changed = true;
        }
        if (!config.contains("network.shared_database.host")) {
            config.set("network.shared_database.host", "127.0.0.1");
            changed = true;
        }
        if (!config.contains("network.shared_database.port")) {
            config.set("network.shared_database.port", 5432);
            changed = true;
        }
        if (!config.contains("network.shared_database.database")) {
            config.set("network.shared_database.database", "drowsycraft_staging");
            changed = true;
        }
        if (!config.contains("network.shared_database.username")) {
            config.set("network.shared_database.username", "");
            changed = true;
        }
        if (!config.contains("network.shared_database.password")) {
            config.set("network.shared_database.password", "");
            changed = true;
        }
        if (!config.contains("network.shared_database.ssl")) {
            config.set("network.shared_database.ssl", false);
            changed = true;
        }
        if (!config.contains("network.shared_database.table_prefix")) {
            config.set("network.shared_database.table_prefix", "drowsy_");
            changed = true;
        }
        if (!config.contains("network.shared_database.pool_max_size")) {
            config.set("network.shared_database.pool_max_size", 10);
            changed = true;
        }
        if (!config.contains("network.routing.live_player_transfers")) {
            config.set("network.routing.live_player_transfers", false);
            changed = true;
        }
        if (!config.contains("network.brand.display_name")) {
            config.set("network.brand.display_name", "DrowsyCraft Network");
            changed = true;
        }
        if (!config.contains("network.brand.primary_hub_name")) {
            config.set("network.brand.primary_hub_name", "Drowsy Hub");
            changed = true;
        }
        if (!config.contains("network.brand.mode_labels.survival")) {
            config.set("network.brand.mode_labels.survival", "Drowsy SMP");
            changed = true;
        }
        if (!config.contains("network.brand.mode_labels.factions")) {
            config.set("network.brand.mode_labels.factions", "Drowsy Factions");
            changed = true;
        }
        if (!config.contains("network.brand.mode_labels.arcade")) {
            config.set("network.brand.mode_labels.arcade", "Drowsy Arcade");
            changed = true;
        }
        if (!config.contains("network.brand.mode_labels.events")) {
            config.set("network.brand.mode_labels.events", "Drowsy Events");
            changed = true;
        }
        if (!config.contains("network.progression.shared_currency_name")) {
            config.set("network.progression.shared_currency_name", "Drowsy Tokens");
            changed = true;
        }
        if (!config.contains("network.progression.shared_profile_enabled")) {
            config.set("network.progression.shared_profile_enabled", true);
            changed = true;
        }
        if (!config.contains("network.progression.shared_cosmetics_enabled")) {
            config.set("network.progression.shared_cosmetics_enabled", true);
            changed = true;
        }
        if (!config.contains("network.progression.seasonal_pass_enabled")) {
            config.set("network.progression.seasonal_pass_enabled", false);
            changed = true;
        }
        if (!config.contains("network.matchmaking.arcade_queue_enabled")) {
            config.set("network.matchmaking.arcade_queue_enabled", true);
            changed = true;
        }
        if (!config.contains("network.matchmaking.arcade_queue_display_name")) {
            config.set("network.matchmaking.arcade_queue_display_name", "Arcade Queue");
            changed = true;
        }
        if (!config.contains("network.matchmaking.rotate_modes")) {
            config.set("network.matchmaking.rotate_modes", Arrays.asList("Duels", "Parkour", "TNT Run", "Mob Arena"));
            changed = true;
        }
        if (!config.contains("network.modes")) {
            config.set("network.modes", createDefaultNetworkModes());
            changed = true;
        }
        if (!config.contains("network.rollout_phases.phase_1")) {
            config.set("network.rollout_phases.phase_1", Arrays.asList("Drowsy SMP", "Drowsy Factions", "Drowsy Arcade"));
            changed = true;
        }
        if (!config.contains("network.rollout_phases.phase_2")) {
            config.set("network.rollout_phases.phase_2", Arrays.asList("Drowsy Skyblock", "Drowsy Prison", "Drowsy Lifesteal", "Drowsy Events"));
            changed = true;
        }
        if (!config.contains("network.rollout_phases.phase_3")) {
            config.set("network.rollout_phases.phase_3", Arrays.asList("BedWars", "SkyWars", "KitPvP", "Seasonal Modes"));
            changed = true;
        }

        return changed;
    }

    public boolean isNetworkEnabled() {
        return getConfig().getBoolean("network.enabled", false);
    }

    public boolean isNetworkStagingOnly() {
        return getConfig().getBoolean("network.staging_only", true);
    }

    public boolean isNetworkProxyEnabled() {
        return isNetworkEnabled() && getConfig().getBoolean("network.proxy.enabled", false);
    }

    public String getNetworkProxyType() {
        return getConfig().getString("network.proxy.type", "velocity");
    }

    public String getNetworkServerName() {
        return getConfig().getString("network.proxy.server_name", "survival");
    }

    public String getNetworkHubServerName() {
        return getConfig().getString("network.proxy.hub_server", "hub");
    }

    public String getNetworkFallbackServerName() {
        return getConfig().getString("network.proxy.fallback_server", "survival");
    }

    public String getNetworkProxyPluginChannel() {
        return getConfig().getString("network.proxy.plugin_channel", "drowsycraft:network");
    }

    public boolean isNetworkSharedDatabaseEnabled() {
        return isNetworkEnabled() && getConfig().getBoolean("network.shared_database.enabled", false);
    }

    public String getNetworkSharedDatabaseProvider() {
        return getConfig().getString("network.shared_database.provider", "postgresql");
    }

    public String getNetworkSharedDatabaseHost() {
        return getConfig().getString("network.shared_database.host", "127.0.0.1");
    }

    public int getNetworkSharedDatabasePort() {
        return getConfig().getInt("network.shared_database.port", 5432);
    }

    public String getNetworkSharedDatabaseName() {
        return getConfig().getString("network.shared_database.database", "drowsycraft_staging");
    }

    public String getNetworkSharedDatabaseUsername() {
        return getConfig().getString("network.shared_database.username", "");
    }

    public String getNetworkSharedDatabasePassword() {
        return getConfig().getString("network.shared_database.password", "");
    }

    public boolean isNetworkSharedDatabaseSslEnabled() {
        return getConfig().getBoolean("network.shared_database.ssl", false);
    }

    public String getNetworkSharedDatabaseTablePrefix() {
        return getConfig().getString("network.shared_database.table_prefix", "drowsy_");
    }

    public int getNetworkSharedDatabasePoolMaxSize() {
        return getConfig().getInt("network.shared_database.pool_max_size", 10);
    }

    public boolean isNetworkLivePlayerTransfersEnabled() {
        return isNetworkEnabled() && getConfig().getBoolean("network.routing.live_player_transfers", false);
    }

    public NetworkModerationService getNetworkModerationService() {
        return networkModerationService;
    }

    public NetworkProfileService getNetworkProfileService() {
        return networkProfileService;
    }

    public NetworkTokenService getNetworkTokenService() {
        return networkTokenService;
    }

    private List<Map<String, Object>> createDefaultNetworkModes() {
        List<Map<String, Object>> modes = new ArrayList<>();
        modes.add(createNetworkMode("survival", "Drowsy SMP", "core", 1, true, Arrays.asList("Long-term progression", "Builder-friendly", "Economy-ready")));
        modes.add(createNetworkMode("factions", "Drowsy Factions", "core", 1, true, Arrays.asList("Competitive claims", "Raids", "Power progression")));
        modes.add(createNetworkMode("arcade", "Drowsy Arcade", "core", 1, true, Arrays.asList("Duels", "Parkour", "TNT Run", "Mob Arena")));
        modes.add(createNetworkMode("skyblock", "Drowsy Skyblock", "growth", 2, false, Arrays.asList("Island progression", "Shared tokens")));
        modes.add(createNetworkMode("prison", "Drowsy Prison", "growth", 2, false, Arrays.asList("Economy grind", "Prestige loops")));
        modes.add(createNetworkMode("lifesteal", "Drowsy Lifesteal", "growth", 2, false, Arrays.asList("High-risk PvP", "Season resets")));
        modes.add(createNetworkMode("events", "Drowsy Events", "growth", 2, false, Arrays.asList("Live events", "Community nights")));
        modes.add(createNetworkMode("bedwars", "BedWars", "network", 3, false, Arrays.asList("Team PvP", "Queue-based")));
        modes.add(createNetworkMode("skywars", "SkyWars", "network", 3, false, Arrays.asList("Fast PvP", "Solo or teams")));
        modes.add(createNetworkMode("kitpvp", "KitPvP", "network", 3, false, Arrays.asList("Drop-in combat", "Loadout mastery")));
        modes.add(createNetworkMode("seasonal", "Seasonal Modes", "network", 3, false, Arrays.asList("Limited-time rulesets", "Fresh progression")));
        return modes;
    }

    private Map<String, Object> createNetworkMode(String key, String name, String category, int phase, boolean enabled, List<String> highlights) {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("key", key);
        mode.put("name", name);
        mode.put("category", category);
        mode.put("phase", phase);
        mode.put("enabled", enabled);
        mode.put("highlights", highlights);
        return mode;
    }

    public String getApiKey() { return apiKey; }

    private boolean isDiscordLinkRequiredAndNotLinked(Player p) {
        if (getConfig().getBoolean("discord.link_required", false)) {
            if (p.isOp() || p.hasPermission("dmt.admin")) {
                return false; // bypass for admins
            }
            String discordLink = getDiscordLink(p.getUniqueId());
            return discordLink == null || discordLink.isEmpty();
        }
        return false;
    }

    private void setupDataFiles() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException ignored) {}
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            try { playersFile.createNewFile(); } catch (IOException ignored) {}
        }
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);

        // One-time migration for player data
        String[] playerMigrationKeys = {"punishments", "homes", "warps", "pwarps", "claims", "trust", "untrust", "notes"};
        boolean playerDataMigrated = false;
        for (String key : playerMigrationKeys) {
            if (dataConfig.contains(key) && !playersConfig.contains(key)) {
                playersConfig.set(key, dataConfig.getConfigurationSection(key).getValues(true));
                dataConfig.set(key, null);
                playerDataMigrated = true;
                getLogger().info("Migrated player data key '" + key + "' to players.yml");
            }
        }
        if (playerDataMigrated) {
            saveDataFile();
            savePlayersFile();
        }

        economyFile = new File(getDataFolder(), "economy.yml");
        if (!economyFile.exists()) {
            try { economyFile.createNewFile(); } catch (IOException ignored) {}
        }
        economyConfig = YamlConfiguration.loadConfiguration(economyFile);

        ticketFile = new File(getDataFolder(), "tickets.yml");
        if (!ticketFile.exists()) {
            try { ticketFile.createNewFile(); } catch (IOException ignored) {}
        }
        ticketConfig = YamlConfiguration.loadConfiguration(ticketFile);

        rankFile = new File(getDataFolder(), "ranks.yml");
        if (!rankFile.exists()) {
            try { rankFile.createNewFile(); } catch (IOException ignored) {}
        }
        rankConfig = YamlConfiguration.loadConfiguration(rankFile);

        // One-time migration for economy
        boolean economyMigrated = false;
        if (dataConfig.contains("coins") && !economyConfig.contains("coins")) {
            economyConfig.set("coins", dataConfig.getConfigurationSection("coins").getValues(true));
            dataConfig.set("coins", null);
            economyMigrated = true;
        }
        if (dataConfig.contains("drowsy_coins") && !economyConfig.contains("drowsy_coins")) {
            economyConfig.set("drowsy_coins", dataConfig.getConfigurationSection("drowsy_coins").getValues(true));
            dataConfig.set("drowsy_coins", null);
            economyMigrated = true;
        }
        normalizeLegacyEconomySection("coins");
        normalizeLegacyEconomySection("drowsy_coins");
        if (economyMigrated) {
            saveDataFile();
            saveEconomyFile();
            getLogger().info("Migrated economy to economy.yml");
        }

        // One-time migration for tickets and appeals
        if (dataConfig.contains("tickets") && !ticketConfig.contains("tickets")) {
            // Keep a backup copy in case migration goes wrong or data is accidentally wiped
            try {
                File backup = new File(getDataFolder(), "tickets-backup-" + System.currentTimeMillis() + ".yml");
                FileConfiguration backupCfg = new YamlConfiguration();
                backupCfg.set("tickets", dataConfig.getConfigurationSection("tickets").getValues(true));
                backupCfg.save(backup);
                getLogger().info("Ticket migration backup saved as " + backup.getName());
            } catch (IOException ex) {
                getLogger().log(Level.WARNING, "Failed to create tickets migration backup", ex);
            }
            ticketConfig.set("tickets", dataConfig.getConfigurationSection("tickets").getValues(true));
            dataConfig.set("tickets", null);
        }
        if (dataConfig.contains("appeals") && !ticketConfig.contains("appeals")) {
            ticketConfig.set("appeals", dataConfig.getConfigurationSection("appeals").getValues(true));
            dataConfig.set("appeals", null);
        }
        if (!dataConfig.contains("tickets.next_id") && ticketConfig.contains("tickets.next_id")) {
            ticketConfig.set("tickets.next_id", ticketConfig.getInt("tickets.next_id", 1));
        }
        if (dataConfig.contains("tickets.next_id")) {
            ticketConfig.set("tickets.next_id", dataConfig.getInt("tickets.next_id", 1));
            dataConfig.set("tickets.next_id", null);
        }
        if (dataConfig.contains("appeals.next_id")) {
            ticketConfig.set("appeals.next_id", dataConfig.getInt("appeals.next_id", 1));
            dataConfig.set("appeals.next_id", null);
        }

        normalizeLegacyTicketSection("tickets");
        normalizeLegacyTicketSection("appeals");

        // One-time migration for ranks and groups
        if (dataConfig.contains("ranks") && !rankConfig.contains("ranks")) {
            rankConfig.set("ranks", dataConfig.getConfigurationSection("ranks").getValues(true));
            dataConfig.set("ranks", null);
        }
        if (dataConfig.contains("groups") && !rankConfig.contains("groups")) {
            rankConfig.set("groups", dataConfig.getConfigurationSection("groups").getValues(true));
            dataConfig.set("groups", null);
        }

        if (dataConfig.contains("groups")) { // this may be moved in above conditionally but preserve if left
            rankConfig.set("groups", dataConfig.getConfigurationSection("groups").getValues(true));
            dataConfig.set("groups", null);
        }

        if (dataConfig.contains("ranks")) {
            rankConfig.set("ranks", dataConfig.getConfigurationSection("ranks").getValues(true));
            dataConfig.set("ranks", null);
        }

        if (dataConfig.contains("player_rank")) {
            ConfigurationSection legacyPlayerRanks = dataConfig.getConfigurationSection("player_rank");
            if (legacyPlayerRanks != null) {
                ConfigurationSection existingPlayerRanks = rankConfig.getConfigurationSection("player_rank");
                if (existingPlayerRanks == null) {
                    rankConfig.set("player_rank", legacyPlayerRanks.getValues(false));
                } else {
                    for (String uuidKey : legacyPlayerRanks.getKeys(false)) {
                        if (!rankConfig.contains("player_rank." + uuidKey)) {
                            rankConfig.set("player_rank." + uuidKey, legacyPlayerRanks.getString(uuidKey));
                        }
                    }
                }
            }
            dataConfig.set("player_rank", null);
        }

        int normalizedPlayerRanks = normalizeLegacyPlayerRankAssignments();
        if (normalizedPlayerRanks > 0) {
            getLogger().info("Normalized " + normalizedPlayerRanks + " player_rank entries in ranks.yml");
        }

        int normalizedDisplayStyles = normalizeLegacyDisplayStyles();
        if (normalizedDisplayStyles > 0) {
            getLogger().info("Populated " + normalizedDisplayStyles + " missing rank/group style entries in ranks.yml");
        }

        saveDataFile();
        saveTicketFile();
        saveRankFile();
    }

    public void saveDataFile() {
        dataConfigDirty = true;
    }

    public void saveDataFileSync() {
        dataConfigDirty = false;
        try {
            synchronized (saveLock) {
                dataConfig.save(dataFile);
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save data.yml synchronously", e);
            dataConfigDirty = true;
        }
    }

    public void savePlayersFile() {
        playersConfigDirty = true;
    }

    public void saveEconomyFile() {
        economyConfigDirty = true;
    }

    public void saveTicketFile() {
        ticketConfigDirty = true;
    }

    public void saveRankFile() {
        rankConfigDirty = true;
    }

    public FileConfiguration getTicketConfig() {
        return ticketConfig;
    }

    public FileConfiguration getRankConfig() {
        return rankConfig;
    }

    private int normalizeLegacyPlayerRankAssignments() {
        ConfigurationSection playerRanks = rankConfig.getConfigurationSection("player_rank");
        ConfigurationSection ranksSection = rankConfig.getConfigurationSection(RANKS_PATH);
        if (playerRanks == null || ranksSection == null) {
            return 0;
        }

        int normalized = 0;
        Set<String> configuredRanks = ranksSection.getKeys(false);
        for (String uuidKey : playerRanks.getKeys(false)) {
            String storedRank = playerRanks.getString(uuidKey);
            if (storedRank == null || storedRank.isEmpty()) {
                continue;
            }
            for (String configuredRank : configuredRanks) {
                if (configuredRank.equals(storedRank)) {
                    break;
                }
                if (configuredRank.equalsIgnoreCase(storedRank)) {
                    rankConfig.set("player_rank." + uuidKey, configuredRank);
                    normalized++;
                    break;
                }
            }
        }
        return normalized;
    }

    private int normalizeLegacyDisplayStyles() {
        int normalized = 0;
        normalized += normalizeDisplayStylesForSection(RANKS_PATH);
        normalized += normalizeDisplayStylesForSection("groups");
        return normalized;
    }

    private int normalizeDisplayStylesForSection(String sectionPath) {
        ConfigurationSection section = rankConfig.getConfigurationSection(sectionPath);
        if (section == null) {
            return 0;
        }

        int normalized = 0;
        for (String name : section.getKeys(false)) {
            String basePath = sectionPath + "." + name;
            String color = rankConfig.getString(basePath + ".color", "");
            String prefix = rankConfig.getString(basePath + ".prefix", "");

            if (prefix == null || prefix.isEmpty()) {
                prefix = buildDefaultDisplayPrefix(color, name);
                rankConfig.set(basePath + ".prefix", prefix);
                normalized++;
            }

            if (color == null || color.isEmpty()) {
                String inferred = inferHexColorFromPrefix(prefix);
                rankConfig.set(basePath + ".color", inferred == null || inferred.isEmpty() ? "#aaaaaa" : inferred);
                normalized++;
            }
        }
        return normalized;
    }

    private void normalizeLegacyEconomySection(String sectionPath) {
        ConfigurationSection section = economyConfig.getConfigurationSection(sectionPath);
        if (section == null) return;

        Set<String> keys = section.getKeys(false);
        boolean hasFlattenedKeys = keys.stream().anyMatch(key -> key.contains("."));
        if (!hasFlattenedKeys) return;

        Map<String, Object> flatValues = new LinkedHashMap<>();
        for (String key : keys) {
            flatValues.put(key, section.get(key));
        }

        economyConfig.set(sectionPath, null);
        for (Map.Entry<String, Object> entry : flatValues.entrySet()) {
            economyConfig.set(sectionPath + "." + entry.getKey(), entry.getValue());
        }

        getLogger().info("Normalized legacy " + sectionPath + " storage for " + flatValues.size() + " balances.");
    }

    private void normalizeLegacyTicketSection(String sectionPath) {
        ConfigurationSection section = ticketConfig.getConfigurationSection(sectionPath);
        if (section == null) return;

        Set<String> keys = section.getKeys(false);
        boolean hasFlattenedKeys = keys.stream().anyMatch(key -> key.contains("."));
        if (!hasFlattenedKeys) return;

        Map<String, Object> flatValues = new LinkedHashMap<>();
        for (String key : keys) {
            flatValues.put(key, section.get(key));
        }

        Object nextId = flatValues.remove("next_id");
        ticketConfig.set(sectionPath, null);
        if (nextId != null) {
            ticketConfig.set(sectionPath + ".next_id", nextId);
        }

        Set<String> recordIds = new TreeSet<>((left, right) -> {
            try {
                return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
            } catch (NumberFormatException ignored) {
                return left.compareTo(right);
            }
        });

        for (Map.Entry<String, Object> entry : flatValues.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            int separator = key.indexOf('.');
            if (separator <= 0 || separator == key.length() - 1) {
                ticketConfig.set(sectionPath + "." + key, value);
                continue;
            }

            String recordId = key.substring(0, separator);
            String fieldPath = key.substring(separator + 1);
            recordIds.add(recordId);
            ticketConfig.set(sectionPath + "." + recordId + "." + fieldPath, value);
        }

        if (!recordIds.isEmpty()) {
            getLogger().info("Normalized legacy " + sectionPath + " storage for " + recordIds.size() + " records.");
        }
    }

    private void performDataSave() {
        if (dataConfigDirty) {
            dataConfigDirty = false;
            saveYamlAsync(dataConfig, dataFile);
        }
        if (playersConfigDirty) {
            playersConfigDirty = false;
            saveYamlAsync(playersConfig, playersFile);
        }
        if (economyConfigDirty) {
            economyConfigDirty = false;
            saveYamlAsync(economyConfig, economyFile);
        }
        if (ticketConfigDirty) {
            ticketConfigDirty = false;
            saveYamlAsync(ticketConfig, ticketFile);
        }
        if (rankConfigDirty) {
            rankConfigDirty = false;
            saveYamlAsync(rankConfig, rankFile);
        }
    }

    private void saveYamlAsync(FileConfiguration config, File file) {
        try {
            final String serializedData = config.saveToString();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                synchronized (saveLock) {
                    try (java.io.Writer writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
                        writer.write(serializedData);
                    } catch (java.io.IOException e) {
                        getLogger().log(Level.SEVERE, "Could not save data to " + file.getName(), e);
                    }
                }
            });
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to serialize " + file.getName(), e);
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

        FileConfiguration rankCfg = getRankConfig();

        // Apply group permissions (if used)
        String groupName = getPlayerGroup(uuid);
        if (groupName != null) {
            List<String> perms = rankCfg.getStringList("groups." + groupName + ".permissions");
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
            List<String> perms = rankCfg.getStringList(RANKS_PATH + "." + rank + ".permissions");
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

    private boolean hasStaffLabel(Player p, String label) {
        return p != null && getStaffLabels(p).contains(label);
    }

    private boolean hasAnyStaffRole(Player p) {
        return p != null && !getStaffLabels(p).isEmpty();
    }

    private boolean isHelper(Player p) {
        return hasStaffLabel(p, "Helper");
    }

    private boolean isModerator(Player p) {
        return hasStaffLabel(p, "Moderator");
    }

    private boolean isAdminTag(Player p) {
        return hasStaffLabel(p, "Admin");
    }

    private boolean isManagerTag(Player p) {
        return hasStaffLabel(p, "Manager");
    }

    private boolean isOwnerTag(Player p) {
        return hasStaffLabel(p, "Owner");
    }

    private boolean isHeadAdminTag(Player p) {
        return hasStaffLabel(p, "Head_Admin");
    }

    private boolean isStaffTagged(Player p) {
        return hasAnyStaffRole(p);
    }

    private void addStaffLabel(Set<String> labels, String label) {
        if (label == null || label.isBlank()) {
            return;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (normalized) {
            case "helper" -> labels.add("Helper");
            case "moderator", "mod" -> labels.add("Moderator");
            case "admin" -> labels.add("Admin");
            case "manager" -> labels.add("Manager");
            case "owner" -> labels.add("Owner");
            case "head_admin", "headadmin" -> labels.add("Head_Admin");
            default -> {
                if (normalized.contains("head") && normalized.contains("admin")) {
                    labels.add("Head_Admin");
                } else if (normalized.contains("manager")) {
                    labels.add("Manager");
                } else if (normalized.contains("owner")) {
                    labels.add("Owner");
                } else if (normalized.contains("admin")) {
                    labels.add("Admin");
                } else if (normalized.contains("moderator") || normalized.equals("mod")) {
                    labels.add("Moderator");
                } else if (normalized.contains("helper")) {
                    labels.add("Helper");
                }
            }
        }
    }

    private List<String> getStaffLabels(Player p) {
        if (p == null) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> labels = new LinkedHashSet<>();

        for (String tag : p.getScoreboardTags()) {
            addStaffLabel(labels, tag);
        }

        addStaffLabel(labels, getPlayerRank(p.getUniqueId()));
        addStaffLabel(labels, getPlayerGroup(p.getUniqueId()));

        if (p.isOp() || p.hasPermission("dmt.admin") || p.hasPermission("realmtool.admin")) {
            labels.add("Admin");
        }

        return new ArrayList<>(labels);
    }

    private boolean canUseInvisible(Player p) {
        return p != null && (
            hasStaffLabel(p, "Moderator")
                || hasStaffLabel(p, "Admin")
                || hasStaffLabel(p, "Manager")
                || hasStaffLabel(p, "Owner")
                || hasStaffLabel(p, "Head_Admin")
        );
    }

    private boolean isInvisible(Player p) {
        return invisiblePlayers.contains(p.getUniqueId());
    }

    private void updateInvisibleVisibility(Player target, boolean invisible) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            try {
                if (invisible) {
                    if (canUseInvisible(viewer)) {
                        viewer.showPlayer(this, target);
                    } else {
                        viewer.hidePlayer(this, target);
                    }
                } else {
                    viewer.showPlayer(this, target);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error updating player visibility for " + viewer.getName() + " to target " + target.getName(), e);
            }
        }

        try {
            if (invisible) {
                target.setGameMode(GameMode.SPECTATOR);
                target.setInvisible(true);
                target.setCollidable(false);
                target.setSilent(true);
                target.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false, false));
                removePlayerFromRankTeams(target, null);
                try {
                    target.setPlayerListName(ChatColor.GRAY + target.getName());
                } catch (Exception ignored) {}
            } else {
                if (target.getGameMode() == GameMode.SPECTATOR) {
                    target.setGameMode(GameMode.SURVIVAL);
                }
                target.setInvisible(false);
                target.setCollidable(true);
                target.setSilent(false);
                target.removePotionEffect(PotionEffectType.INVISIBILITY);
                applyRankToPlayer(target);
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error applying invisible state to " + target.getName(), e);
        }
    }

    private void refreshInvisibleVisibilityFor(Player viewer) {
        for (UUID uuid : invisiblePlayers) {
            Player hidden = Bukkit.getPlayer(uuid);
            if (hidden == null || !hidden.isOnline()) continue;
            if (viewer.equals(hidden)) continue;
            if (canUseInvisible(viewer)) {
                viewer.showPlayer(this, hidden);
            } else {
                viewer.hidePlayer(this, hidden);
            }
        }
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
        // Helpers and moderators should be able to change mode (survival/spectator)
        if ((command.equalsIgnoreCase("gamemode") || command.equalsIgnoreCase("gm")) && (isHelper(p) || isModerator(p))) return true;
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
        return rankService != null ? rankService.getPlayerGroup(uuid) : null;
    }

    private void handleRankCommand(Player p, String[] args) {
        if (rankService != null) rankService.handleRankCommand(p, args);
    }

    private void createRank(Player p, String rank) {
        if (rankService != null) rankService.createRank(p, rank);
    }

    public String getPlayerRank(UUID uuid) {
        return rankService != null ? rankService.getPlayerRank(uuid) : null;
    }

    public void setPlayerRank(UUID uuid, String rank) {
        if (rankService != null) rankService.setPlayerRank(uuid, rank);
    }

    public void removeRank(Player p, String rank) {
        if (rankService != null) rankService.removeRank(p, rank);
    }

    public void addPlayerToRank(Player p, String rank, String playerName) {
        if (rankService != null) rankService.addPlayerToRank(p, rank, playerName);
    }

    public void addRankPrefix(Player p, String rank, String prefix) {
        if (rankService != null) rankService.addRankPrefix(p, rank, prefix);
    }

    public void addRankPermission(Player p, String rank, String perm) {
        if (rankService != null) rankService.addRankPermission(p, rank, perm);
    }

    public void showRankList(Player p) {
        if (rankService != null) rankService.showRankList(p);
    }

    public void showRankInfo(Player p, String rank) {
        if (rankService != null) rankService.showRankInfo(p, rank);
    }

    public void refreshAllPermissions() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyPermissionGroup(p);
        }
    }

    private String resolveDisplayPrefix(String displayRank) {
        if (displayRank == null || displayRank.isEmpty()) {
            return "";
        }

        FileConfiguration rankCfg = getRankConfig();
        String rankKey = resolveConfigKey(rankCfg, RANKS_PATH, displayRank);
        if (rankKey != null) {
            String prefix = rankCfg.getString(RANKS_PATH + "." + rankKey + ".prefix", "");
            if (prefix != null && !prefix.isEmpty()) {
                return prefix;
            }
            return buildDefaultDisplayPrefix(rankCfg.getString(RANKS_PATH + "." + rankKey + ".color", ""), rankKey);
        }

        String groupKey = resolveConfigKey(rankCfg, "groups", displayRank);
        if (groupKey != null) {
            String prefix = rankCfg.getString("groups." + groupKey + ".prefix", "");
            if (prefix != null && !prefix.isEmpty()) {
                return prefix;
            }
            return buildDefaultDisplayPrefix(rankCfg.getString("groups." + groupKey + ".color", ""), groupKey);
        }

        return buildDefaultDisplayPrefix("", displayRank);
    }

    private String resolveConfigKey(FileConfiguration config, String sectionPath, String key) {
        if (config == null || key == null || key.isEmpty()) {
            return null;
        }
        ConfigurationSection section = config.getConfigurationSection(sectionPath);
        if (section == null) {
            return null;
        }
        if (section.contains(key)) {
            return key;
        }
        for (String existing : section.getKeys(false)) {
            if (existing.equalsIgnoreCase(key)) {
                return existing;
            }
        }
        return null;
    }

    private String buildDefaultDisplayPrefix(String color, String label) {
        String colorCode = "&7";
        if (color != null && color.startsWith("#") && color.length() == 7) {
            StringBuilder hexBuilder = new StringBuilder("&x");
            for (char c : color.substring(1).toCharArray()) {
                hexBuilder.append('&').append(c);
            }
            colorCode = hexBuilder.toString();
        }
        String cleanLabel = toDisplayLabel(label);
        return colorCode + "[" + cleanLabel + "] " + "&r";
    }

    private String toDisplayLabel(String label) {
        if (label == null || label.isEmpty()) {
            return "Rank";
        }

        String normalized = label.replace('_', ' ').trim();
        String[] words = normalized.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                builder.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.length() > 0 ? builder.toString() : "Rank";
    }

    public String inferHexColorFromPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "#ffffff";
        if (prefix.contains("&#")) {
            int idx = prefix.indexOf("&#");
            if (idx + 8 <= prefix.length()) return prefix.substring(idx + 1, idx + 8);
        }
        if (prefix.contains("&x") || prefix.contains("§x")) {
            char cc = prefix.contains("&x") ? '&' : '§';
            int idx = prefix.indexOf(cc + "x");
            if (idx + 14 <= prefix.length()) {
                StringBuilder sb = new StringBuilder("#");
                for (int i = 0; i < 6; i++) sb.append(prefix.charAt(idx + 3 + (i * 2)));
                return sb.toString();
            }
        }
        char firstColorChar = 'f';
        for (int i = 0; i < prefix.length() - 1; i++) {
            if ((prefix.charAt(i) == '&' || prefix.charAt(i) == '§') && "0123456789AaBbCcDdEeFf".indexOf(prefix.charAt(i+1)) != -1) {
                firstColorChar = prefix.charAt(i+1);
                break; // Grab the FIRST color for the web panel badge
            }
        }
        switch (Character.toLowerCase(firstColorChar)) {
            case '0': return "#000000"; case '1': return "#0000aa"; case '2': return "#00aa00"; case '3': return "#00aaaa";
            case '4': return "#aa0000"; case '5': return "#aa00aa"; case '6': return "#ffaa00"; case '7': return "#aaaaaa";
            case '8': return "#555555"; case '9': return "#5555ff"; case 'a': return "#55ff55"; case 'b': return "#55ffff";
            case 'c': return "#ff5555"; case 'd': return "#ff55ff"; case 'e': return "#ffff55"; case 'f': return "#ffffff";
            default: return "#ffffff";
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
                Bukkit.getScheduler().runTask(this, () -> {
                    int sent = dataConfig.getInt("discord.webhooks_sent", 0);
                    dataConfig.set("discord.webhooks_sent", sent + 1);
                    saveDataFile();
                });
                return true;
            } else {
                Bukkit.getScheduler().runTask(this, () -> {
                    int failed = dataConfig.getInt("discord.webhooks_failed", 0);
                    dataConfig.set("discord.webhooks_failed", failed + 1);
                    saveDataFile();
                });
                getLogger().warning("Discord webhook returned " + code);
                return false;
            }
        } catch (Exception e) {
            Bukkit.getScheduler().runTask(this, () -> {
                int failed = dataConfig.getInt("discord.webhooks_failed", 0);
                dataConfig.set("discord.webhooks_failed", failed + 1);
                saveDataFile();
            });
            getLogger().warning("Discord webhook failed: " + e.getMessage());
            return false;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    public void sendWebPush(String title, String body) {
        String panelUrl = dataConfig.getString("web_panel_url", "https://manage.drowsycraft.com");
        if (panelUrl == null || panelUrl.isEmpty() || panelUrl.equalsIgnoreCase("none")) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String endpoint = panelUrl + (panelUrl.endsWith("/") ? "" : "/") + "push/notify";
            try {
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                // Add a User-Agent so Cloudflare/host doesn't block the Java request!
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DrowsyCraft-Bot/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

                String json = "{\"title\":" + escapeJson(title) + ",\"body\":" + escapeJson(body) + "}";

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code >= 400) {
                    InputStream errStream = conn.getErrorStream();
                    String errorResp = errStream != null ? new String(errStream.readAllBytes(), StandardCharsets.UTF_8) : "No error body";
                    getLogger().warning("Web Push Error " + code + " from " + endpoint + ": " + errorResp);
                }
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("Web Push Connection Failed (" + endpoint + "): " + e.getMessage());
            }
        });
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
            case "factions": specificKey = "webhook_faction"; break;
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

        if (cmd.getName().equalsIgnoreCase("staff")) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("hours")) {
                if (!canViewStaffHours(p)) {
                    p.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                sendStaffHourReport(p);
                return true;
            }
            sendStaffList(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("drowsytool")) {
            boolean hasTool = false;
            for (ItemStack item : p.getInventory().getContents()) {
                if (item != null && item.hasItemMeta() && TOOL_NAME.equals(item.getItemMeta().getDisplayName())) {
                    hasTool = true;
                    break;
                }
            }
            if (hasTool) {
                p.sendMessage(ChatColor.YELLOW + "You already have the Drowsy Tool in your inventory.");
                ensurePlayerHasTool(p); // Enforces correct slot placement
                return true;
            }
            ensurePlayerHasTool(p);
            p.sendMessage(ChatColor.GREEN + "You have received the Drowsy Tool. It is in your hotbar slot 9.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("randomrole")) {
            p.sendMessage(ChatColor.GREEN + "Thank you for purchasing Random Roles! Please open a minecraft ticket in the Drowsy Vocals Discord to claim. (discord.gg/drowsyvocals)");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("personal")) {
            if (!hasPersonalCommandAccess(p)) {
                p.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }

            if (args.length < 2 || !args[0].equalsIgnoreCase("npc")) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /personal npc spawn miner | despawn miner | miner collect");
                return true;
            }

            String action = args[1].toLowerCase();
            if (action.equals("spawn") && args.length >= 3 && args[2].equalsIgnoreCase("miner")) {
                spawnPersonalMiner(p);
                return true;
            }
            if (action.equals("despawn") && args.length >= 3 && args[2].equalsIgnoreCase("miner")) {
                despawnPersonalMiner(p);
                return true;
            }
            if (action.equals("miner") && args.length >= 3 && args[2].equalsIgnoreCase("collect")) {
                collectPersonalMiner(p);
                return true;
            }

            p.sendMessage(ChatColor.YELLOW + "Usage: /personal npc spawn miner | /personal npc despawn miner | /personal npc miner collect");
            return true;
        }

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
                } else if ((subcommand.equals("gamemode") || subcommand.equals("gm")) && (isHelperTag || isModTag)) {
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
                case "debug":
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt debug <player|discord> [player]");
                        return true;
                    }
                    String debugTarget = args[1].toLowerCase(Locale.ROOT);
                    if (debugTarget.equals("player")) {
                        if (args.length < 3) {
                            p.sendMessage(ChatColor.RED + "Usage: /dmt debug player <player>");
                            return true;
                        }
                        sendDebugPlayerInfo(p, args[2]);
                        return true;
                    }
                    if (debugTarget.equals("discord")) {
                        sendDebugDiscordInfo(p);
                        return true;
                    }
                    if (debugTarget.equals("health")) {
                        sendDebugHealthInfo(p);
                        return true;
                    }
                    p.sendMessage(ChatColor.RED + "Usage: /dmt debug <player|discord> [player]");
                    return true;
                case "discord":
                    if (!hasDmtCommandPermission(p, "discord.check")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt discord <player>");
                        return true;
                    }
                    String discordTargetName = args[1];
                    OfflinePlayer discordTarget = Bukkit.getOfflinePlayer(discordTargetName);
                    if (discordTarget == null || !discordTarget.hasPlayedBefore()) {
                        p.sendMessage(ChatColor.RED + "Player not found.");
                        return true;
                    }
                    String discordLink = getDiscordLink(discordTarget.getUniqueId());
                    if (discordLink != null && !discordLink.isEmpty()) {
                        p.sendMessage(ChatColor.GREEN + discordTarget.getName() + "'s linked Discord is: " + ChatColor.WHITE + discordLink);
                    } else {
                        p.sendMessage(ChatColor.YELLOW + discordTarget.getName() + " has not linked their Discord account.");
                    }
                    break;
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
                        p.sendMessage(ChatColor.RED + "Usage: /dmt punish <username> <duration> [reason]");
                        p.sendMessage(ChatColor.GRAY + "Duration format: 20s, 5m, 2hr, 2.5hr");
                        return true;
                    }
                    String targetName = args[1];
                    String durationStr = args[2];
                    String punishmentReason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "Punished via /dmt punish";
                    long durationMs = parseDuration(durationStr);
                    if (durationMs == -1) {
                        p.sendMessage(ChatColor.RED + "Invalid duration format. Use: 20s, 5m, 2hr, 2.5hr");
                        return true;
                    }
                    OfflinePlayer targetOffline = Bukkit.getOfflinePlayer(targetName);
                    if (!targetOffline.hasPlayedBefore() && !targetOffline.isOp()) {
                        p.sendMessage(ChatColor.RED + "Player not found.");
                        return true;
                    }
                    setPunished(targetOffline.getUniqueId(), durationMs, punishmentReason, p.getName());
                    p.sendMessage(ChatColor.GREEN + "Punished " + targetName + " for " + durationStr);
                    logAction(p.getName(), "punished", targetName + " (" + durationStr + ", " + punishmentReason + ")");
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
                    // Allow anyone to view staff tags
                    if (args.length == 1 || (args.length >= 2 && args[1].equalsIgnoreCase("list"))) {
                        sendStaffList(p);
                        return true;
                    }
                    p.sendMessage(ChatColor.RED + "Usage: /dmt staff list");
                    return true;
                case "staffhours":
                    if (!canViewStaffHours(p)) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    sendStaffHourReport(p);
                    return true;
                case "gamemode":
                case "gm":
                    if (!hasDmtCommandPermission(p, "gamemode")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt gamemode <survival|spectator>");
                        return true;
                    }
                    Player targetPlayer = Bukkit.getPlayer(p.getUniqueId());
                    if (targetPlayer == null) {
                        p.sendMessage(ChatColor.RED + "Unable to find your player object.");
                        return true;
                    }
                    String mode = args[1].toLowerCase();
                    if (mode.equals("survival") || mode.equals("s")) {
                        targetPlayer.setGameMode(GameMode.SURVIVAL);
                        targetPlayer.sendMessage(ChatColor.GREEN + "Gamemode set to Survival.");
                    } else if (mode.equals("spectator") || mode.equals("sp")) {
                        targetPlayer.setGameMode(GameMode.SPECTATOR);
                        targetPlayer.sendMessage(ChatColor.GREEN + "Gamemode set to Spectator.");
                    } else {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt gamemode <survival|spectator>");
                    }
                    return true;
                case "playerwarp":
                    if (args.length != 4) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt playerwarp <player> add|remove <number>");
                        return true;
                    }
                    String targetPlayerName = args[1];
                    String operation = args[2].toLowerCase();
                    int amount;
                    try {
                        amount = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        p.sendMessage(ChatColor.RED + "Invalid number: " + args[3]);
                        return true;
                    }
                    if (amount < 0) {
                        p.sendMessage(ChatColor.RED + "Number must be non-negative.");
                        return true;
                    }

                    OfflinePlayer pwarpTarget = Bukkit.getOfflinePlayer(targetPlayerName);
                    UUID pwarpTargetId = pwarpTarget.getUniqueId();
                    int currentLimit = getPwarpLimit(pwarpTargetId);
                    int newLimit;
                    if (operation.equals("add")) {
                        newLimit = currentLimit + amount;
                    } else if (operation.equals("remove")) {
                        newLimit = Math.max(0, currentLimit - amount);
                    } else {
                        p.sendMessage(ChatColor.RED + "Unknown operation: " + operation + ". Use add or remove.");
                        return true;
                    }

                    setPwarpLimit(pwarpTargetId, newLimit);
                    p.sendMessage(ChatColor.GREEN + "Player warp slots for " + targetPlayerName + " set from " + currentLimit + " to " + newLimit + ".");
                    if (pwarpTarget.isOnline()) {
                        Player tp = pwarpTarget.getPlayer();
                        if (tp != null) {
                            tp.sendMessage(ChatColor.AQUA + "Your player warp slots are now " + newLimit + " (was " + currentLimit + ").");
                        }
                    }
                    int existingWarps = getPwarpCount(pwarpTargetId);
                    if (existingWarps > newLimit) {
                        int removed = prunePwarpsToLimit(pwarpTargetId, newLimit);
                        p.sendMessage(ChatColor.YELLOW + "Player had " + existingWarps + " warps, " + removed + " were removed to meet the new limit.");
                        if (pwarpTarget.isOnline()) {
                            Player tp = pwarpTarget.getPlayer();
                            if (tp != null) tp.sendMessage(ChatColor.RED + "Your warp slots were reduced to " + newLimit + " and " + removed + " warps were removed.");
                        }
                    }
                    return true;
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
                                    Location entryLocation = resolveSafeWorldEntryLocation(w);
                                    p.teleport(entryLocation != null ? entryLocation : w.getSpawnLocation());
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
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt world select <world> | /dmt world <world> <lock|unlock|separate_world|unseparate_world|status> | /dmt world separate_world [<world>] | /dmt world unseparate_world [<world>]");
                        return true;
                    }

                    // support: /dmt world <world> <action>
                    if (args.length >= 3 && !args[1].equalsIgnoreCase("select") && !args[1].equalsIgnoreCase("status")
                            && !args[1].equalsIgnoreCase("separate_world") && !args[1].equalsIgnoreCase("unseparate_world")) {
                        String worldName = args[1];
                        String action = args[2].toLowerCase();
                        World w = Bukkit.getWorld(worldName);
                        if (w == null) {
                            w = Bukkit.createWorld(new org.bukkit.WorldCreator(worldName));
                        }
                        if (w == null) {
                            p.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
                            return true;
                        }
                        if (action.equals("kill") && args.length >= 4 && args[3].equalsIgnoreCase("@e")) {
                            int removed = 0;
                            for (Entity ent : w.getEntities()) {
                                if (ent instanceof Player) continue;
                                if (ent instanceof ArmorStand && "teleport".equalsIgnoreCase(dataConfig.getString("holograms." + ent.getUniqueId() + ".type", ""))) continue;
                                ent.remove();
                                removed++;
                            }
                            p.sendMessage(ChatColor.GREEN + "Removed " + removed + " entities in world '" + worldName + "'.");
                            return true;
                        }
                        if (action.equals("separate_world") || action.equals("seperate_world")) {
                            dataConfig.set("worlds." + worldName + ".separate", true);
                            saveDataFile();
                            p.sendMessage(ChatColor.GREEN + "World '" + worldName + "' is now separated (own inventory/settings).\n");
                            applyWorldSettings(w);
                            return true;
                        }
                        if (action.equals("unseparate_world") || action.equals("unseperate_world")) {
                            dataConfig.set("worlds." + worldName + ".separate", false);
                            saveDataFile();
                            p.sendMessage(ChatColor.GREEN + "World '" + worldName + "' is now normal shared mode.\n");
                            applyWorldSettings(w);
                            return true;
                        }
                        if (action.equals("kill") && args.length >= 4 && args[3].equalsIgnoreCase("@e")) {
                            int removed = 0;
                            for (Entity ent : w.getEntities()) {
                                if (ent instanceof Player) continue;
                                if (ent instanceof ArmorStand && "teleport".equalsIgnoreCase(dataConfig.getString("holograms." + ent.getUniqueId() + ".type", ""))) continue;
                                ent.remove();
                                removed++;
                            }
                            p.sendMessage(ChatColor.GREEN + "Removed " + removed + " entities in world '" + worldName + "'.");
                            return true;
                        }
                        if (action.equals("lock") || action.equals("unlock")) {
                            boolean lock = action.equals("lock");
                            dataConfig.set("worldlocks." + worldName, lock);
                            saveDataFile();
                            refreshTeleportHologramsForWorld(worldName);
                            p.sendMessage(ChatColor.GREEN + "World '" + worldName + "' is now " + (lock ? "locked" : "unlocked") + ".");
                            return true;
                        }
                        if (action.equals("status")) {
                            boolean separated = dataConfig.getBoolean("worlds." + worldName + ".separate", false);
                            boolean lockedStatus = dataConfig.getBoolean("worldlocks." + worldName, false);
                            boolean mobspawns = dataConfig.getBoolean("worlds." + worldName + ".mobspawns", true);
                            String gm = dataConfig.getString("worlds." + worldName + ".gamemode", "default");
                            p.sendMessage(ChatColor.AQUA + "----- World Status: " + worldName + " -----");
                            p.sendMessage(ChatColor.GREEN + "Separated: " + (separated ? "Yes" : "No"));
                            p.sendMessage(ChatColor.GREEN + "Locked: " + (lockedStatus ? "Yes" : "No"));
                            p.sendMessage(ChatColor.GREEN + "Mob Spawns: " + (mobspawns ? "Enabled" : "Disabled"));
                            p.sendMessage(ChatColor.GREEN + "Gamemode: " + gm);
                            p.sendMessage(ChatColor.GREEN + "Loaded: " + (w != null ? "Yes" : "No"));
                            p.sendMessage(ChatColor.AQUA + "-----------------------------------");
                            return true;
                        }
                    }

                    String worldSub = args[1].toLowerCase();
                    if (worldSub.equals("select")) {
                        if (args.length != 3) {
                            p.sendMessage(ChatColor.RED + "Usage: /dmt world select <world>");
                            return true;
                        }
                        String sel = args[2];
                        World selected = Bukkit.getWorld(sel);
                        if (selected == null) {
                            p.sendMessage(ChatColor.RED + "World '" + sel + "' not found.");
                            return true;
                        }
                        selectedWorld.put(p.getUniqueId(), sel);
                        p.sendMessage(ChatColor.GREEN + "Selected world '" + sel + "'. Use /dmt selection ...");
                        return true;
                    }

                    String targetWorld;
                    if (worldSub.equals("separate_world") || worldSub.equals("seperate_world") || worldSub.equals("unseparate_world") || worldSub.equals("unseperate_world")) {
                        if (args.length == 3) {
                            targetWorld = args[2];
                        } else {
                            targetWorld = selectedWorld.get(p.getUniqueId());
                        }
                        if (targetWorld == null) {
                            p.sendMessage(ChatColor.RED + "No world selected. Use /dmt world select <world> or provide world name.");
                            return true;
                        }
                        World wflags = Bukkit.getWorld(targetWorld);
                        if (wflags == null) {
                            // Attempt to load world by name if possible
                            wflags = Bukkit.createWorld(new org.bukkit.WorldCreator(targetWorld));
                        }
                        if (wflags == null) {
                            p.sendMessage(ChatColor.RED + "World '" + targetWorld + "' not found.");
                            return true;
                        }
                        boolean separate = worldSub.startsWith("separate");
                        dataConfig.set("worlds." + targetWorld + ".separate", separate);
                        saveDataFile();
                        if (separate) {
                            p.sendMessage(ChatColor.GREEN + "World '" + targetWorld + "' is now separated (own inventory/settings mode). ");
                        } else {
                            p.sendMessage(ChatColor.GREEN + "World '" + targetWorld + "' is now normal (shared settings mode). ");
                        }
                        applyWorldSettings(wflags);
                        return true;
                    }

                    // world status
                    if (worldSub.equals("status")) {
                        targetWorld = args.length == 3 ? args[2] : selectedWorld.get(p.getUniqueId());
                        if (targetWorld == null) {
                            p.sendMessage(ChatColor.RED + "Usage: /dmt world status [<world>] or select a world first.");
                            return true;
                        }
                        World wstatus = Bukkit.getWorld(targetWorld);
                        if (wstatus == null) {
                            p.sendMessage(ChatColor.RED + "World not found: " + targetWorld);
                            return true;
                        }
                        boolean separated = dataConfig.getBoolean("worlds." + targetWorld + ".separate", false);
                        boolean lockedStatus = dataConfig.getBoolean("worldlocks." + targetWorld, false);
                        boolean mobspawns = dataConfig.getBoolean("worlds." + targetWorld + ".mobspawns", true);
                        String gm = dataConfig.getString("worlds." + targetWorld + ".gamemode", "default");
                        p.sendMessage(ChatColor.AQUA + "----- World Status: " + targetWorld + " -----");
                        p.sendMessage(ChatColor.GREEN + "Separated: " + (separated ? "Yes" : "No"));
                        p.sendMessage(ChatColor.GREEN + "Locked: " + (lockedStatus ? "Yes" : "No"));
                        p.sendMessage(ChatColor.GREEN + "Mob Spawns: " + (mobspawns ? "Enabled" : "Disabled"));
                        p.sendMessage(ChatColor.GREEN + "Sound Gamemode: " + gm);
                        p.sendMessage(ChatColor.GREEN + "Loaded: " + (wstatus != null ? "Yes" : "No"));
                        p.sendMessage(ChatColor.AQUA + "-----------------------------------");
                        return true;
                    }

                    // legacy lock/unlock syntax
                    if (args.length != 3) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt world <world> <lock|unlock>");
                        return true;
                    }
                    targetWorld = args[1];
                    String worldAction = args[2].toLowerCase();
                    if (!worldAction.equals("lock") && !worldAction.equals("unlock")) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt world <world> <lock|unlock>");
                        return true;
                    }
                    boolean lock = worldAction.equals("lock");
                    dataConfig.set("worldlocks." + targetWorld, lock);
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "World '" + targetWorld + "' is now " + (lock ? "locked" : "unlocked") + ".");
                    return true;
                case "selection":
                    if (!hasDmtCommandPermission(p, "world")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    String sel = selectedWorld.get(p.getUniqueId());
                    if (sel == null) {
                        p.sendMessage(ChatColor.RED + "No world selected. Use /dmt world select <world>. ");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt selection <mobspawns|lock|unlock|gamerule|gamemode> ...");
                        return true;
                    }
                    String item = args[1].toLowerCase();
                    if (item.equals("mobspawns")) {
                        if (args.length != 3) {
                            p.sendMessage(ChatColor.RED + "Usage: /dmt selection mobspawns <true|false>");
                            return true;
                        }
                        boolean enabled = Boolean.parseBoolean(args[2]);
                        dataConfig.set("worlds." + sel + ".mobspawns", enabled);
                        saveDataFile();
                        p.sendMessage(ChatColor.GREEN + "Mob spawns in world '" + sel + "' set to " + enabled + ".");
                        return true;
                    }
                    if (item.equals("lock") || item.equals("unlock")) {
                        boolean lockSel = item.equals("lock");
                        dataConfig.set("worldlocks." + sel, lockSel);
                        saveDataFile();
                        refreshTeleportHologramsForWorld(sel);
                        p.sendMessage(ChatColor.GREEN + "World '" + sel + "' " + (lockSel ? "locked" : "unlocked") + ".");
                        return true;
                    }
                    if (item.equals("gamerule")) {
                        if (args.length != 4) {
                            p.sendMessage(ChatColor.RED + "Usage: /dmt selection gamerule <rule> <value>");
                            return true;
                        }
                        String ruleName = args[2];
                        String value = args[3];
                        World world = Bukkit.getWorld(sel);
                        if (world == null) {
                            p.sendMessage(ChatColor.RED + "World not loaded: " + sel);
                            return true;
                        }
                        try {
                            GameRule<?> rule = resolveGameRule(ruleName);
                            if (rule == null) {
                                p.sendMessage(ChatColor.RED + "Unknown gamerule: " + ruleName);
                                return true;
                            }
                            if (rule.getType() == Boolean.class) {
                                world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(value));
                                dataConfig.set("worlds." + sel + ".gamerules." + ruleName, Boolean.parseBoolean(value));
                            } else if (rule.getType() == Integer.class) {
                                world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(value));
                                dataConfig.set("worlds." + sel + ".gamerules." + ruleName, Integer.parseInt(value));
                            } else if (rule.getType() == String.class) {
                                world.setGameRule((GameRule<String>) rule, value);
                                dataConfig.set("worlds." + sel + ".gamerules." + ruleName, value);
                            }
                            saveDataFile();
                            p.sendMessage(ChatColor.GREEN + "GameRule " + ruleName + " for world '" + sel + "' set to " + value + ".");
                        } catch (Exception ex) {
                            p.sendMessage(ChatColor.RED + "Failed to apply gamerule: " + ex.getMessage());
                        }
                        return true;
                    }
                    if (item.equals("gamemode")) {
                        if (args.length != 4 || !args[2].equalsIgnoreCase("set")) {
                            p.sendMessage(ChatColor.RED + "Usage: /dmt selection gamemode set <survival|creative|adventure|spectator>");
                            return true;
                        }
                        String modeName = args[3].toUpperCase();
                        try {
                            GameMode gm = GameMode.valueOf(modeName);
                            dataConfig.set("worlds." + sel + ".gamemode", gm.name());
                            saveDataFile();
                            p.sendMessage(ChatColor.GREEN + "Gamemode for world '" + sel + "' set to " + gm.name() + ".");
                            World world = Bukkit.getWorld(sel);
                            if (world != null) {
                                for (Player wp : world.getPlayers()) {
                                    wp.setGameMode(gm);
                                }
                            } else {
                                world = Bukkit.createWorld(new org.bukkit.WorldCreator(sel));
                            }
                            if (world != null) {
                                applyWorldSettings(world);
                            }
                        } catch (Exception ex) {
                            p.sendMessage(ChatColor.RED + "Unknown gamemode: " + modeName);
                        }
                        return true;
                    }
                    p.sendMessage(ChatColor.RED + "Unknown selection command. Use mobspawns/lock/unlock/gamerule/gamemode.");
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
                    // /dmt npc <id> hidename <true|false>
                    if (args.length >= 4 && args[2].equalsIgnoreCase("hidename")) {
                        String npcId = args[1];
                        String value = args[3].toLowerCase();
                        if (!value.equals("true") && !value.equals("false")) {
                            p.sendMessage(ChatColor.RED + "Usage: /dmt npc <id> hidename <true|false>");
                            break;
                        }
                        boolean hidden = Boolean.parseBoolean(value);
                        if (setCitizenNpcNameTagVisibility(p, npcId, !hidden)) {
                            p.sendMessage(ChatColor.GREEN + "NPC " + npcId + " hide name set to " + hidden);
                        } else {
                            p.sendMessage(ChatColor.RED + "Could not find Citizens NPC with id " + npcId + " or update visibility.");
                        }
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
                case "hub":
                    if (!hasDmtCommandPermission(p, "hub")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length != 3 || !args[1].equalsIgnoreCase("forcespawn")) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt hub forcespawn <true|false>");
                        return true;
                    }
                    String value = args[2].toLowerCase();
                    if (!value.equals("true") && !value.equals("false")) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt hub forcespawn <true|false>");
                        return true;
                    }
                    boolean forceSpawn = Boolean.parseBoolean(value);
                    dataConfig.set("hub.forcespawn", forceSpawn);
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Hub force-spawn is now set to " + forceSpawn + ".");
                    break;
                case "hologram":
                    if (!hasDmtCommandPermission(p, "hologram")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.AQUA + "/dmt hologram list" + ChatColor.WHITE + " - Show teleport holograms");
                        p.sendMessage(ChatColor.AQUA + "/dmt hologram select <id>" + ChatColor.WHITE + " - Select a hologram for editing");
                        p.sendMessage(ChatColor.AQUA + "/dmt hologram edit" + ChatColor.WHITE + " - Edit selected hologram");
                        p.sendMessage(ChatColor.AQUA + "/dmt hologram delete <id>" + ChatColor.WHITE + " - Delete a teleport hologram");
                        p.sendMessage(ChatColor.AQUA + "/dmt hologram clear" + ChatColor.WHITE + " - Clear selected hologram");
                        return true;
                    }
                    String sub = args[1].toLowerCase();
                    if (sub.equals("list")) {
                        if (!dataConfig.contains("holograms")) {
                            p.sendMessage(ChatColor.YELLOW + "No holograms saved.");
                            return true;
                        }
                        for (String id : dataConfig.getConfigurationSection("holograms").getKeys(false)) {
                            String type = dataConfig.getString("holograms." + id + ".type", "");
                            if (!type.equalsIgnoreCase("teleport")) continue;
                            String title = dataConfig.getString("holograms." + id + ".title", "?");
                            String worldName = dataConfig.getString("holograms." + id + ".world", "?");
                            boolean online = dataConfig.getBoolean("holograms." + id + ".online", false);
                            String version = dataConfig.getString("holograms." + id + ".version", "?");
                            p.sendMessage(ChatColor.AQUA + id + ChatColor.WHITE + " - " + title + " (" + worldName + ") " + (online ? ChatColor.GREEN + "Online" : ChatColor.RED + "Offline") + ChatColor.WHITE + " [" + version + "]");
                        }
                        return true;
                    }
                    if (sub.equals("select") && args.length >= 3) {
                        String id = args[2];
                        if (!dataConfig.contains("holograms." + id)) {
                            p.sendMessage(ChatColor.RED + "Hologram not found: " + id);
                            return true;
                        }
                        selectedHologram.put(p.getUniqueId(), id);
                        p.sendMessage(ChatColor.GREEN + "Selected hologram " + id + ". Use /dmt hologram edit to modify or /dmt hologram delete to remove.");
                        return true;
                    }
                    if (sub.equals("clear")) {
                        selectedHologram.remove(p.getUniqueId());
                        p.sendMessage(ChatColor.GREEN + "Hologram selection cleared.");
                        return true;
                    }
                    if (sub.equals("delete")) {
                        String id = args.length >= 3 ? args[2] : selectedHologram.get(p.getUniqueId());
                        if (id == null || id.isEmpty()) {
                            p.sendMessage(ChatColor.RED + "No hologram specified. Use /dmt hologram delete <id> or /dmt hologram select <id> first.");
                            return true;
                        }
                        if (!dataConfig.contains("holograms." + id)) {
                            p.sendMessage(ChatColor.RED + "Hologram not found: " + id);
                            return true;
                        }
                        cleanupTeleportHologramEntities(id);
                        dataConfig.set("holograms." + id, null);
                        dataConfig.set("holograms." + id + ".lines", null);
                        dataConfig.set("holograms." + id + ".type", null);
                        dataConfig.set("holograms." + id + ".title", null);
                        dataConfig.set("holograms." + id + ".world", null);
                        dataConfig.set("holograms." + id + ".online", null);
                        dataConfig.set("holograms." + id + ".version", null);
                        dataConfig.set("holograms." + id + ".location", null);
                        saveDataFile();
                        if (id.equals(selectedHologram.get(p.getUniqueId()))) {
                            selectedHologram.remove(p.getUniqueId());
                        }
                        p.sendMessage(ChatColor.GREEN + "Hologram " + id + " deleted.");
                        return true;
                    }
                    if (sub.equals("edit")) {
                        String id = selectedHologram.get(p.getUniqueId());
                        if (id == null) {
                            p.sendMessage(ChatColor.RED + "No hologram selected. Use /dmt hologram select <id> first.");
                            return true;
                        }
                        if (!dataConfig.contains("holograms." + id)) {
                            p.sendMessage(ChatColor.RED + "Selected hologram no longer exists.");
                            selectedHologram.remove(p.getUniqueId());
                            return true;
                        }
                        PunishmentContext ctx = new PunishmentContext(id, ActionType.HOLOGRAM);
                        ctx.hologramMode = "teleport-edit";
                        ctx.currentLine = 0;
                        ctx.lines = new ArrayList<>();
                        pendingActions.put(p.getUniqueId(), ctx);
                        p.sendMessage(ChatColor.AQUA + "Editing hologram " + id + ". Enter new display title:");
                        return true;
                    }
                    p.sendMessage(ChatColor.RED + "Unknown hologram subcommand. Use /dmt hologram list|select|edit|clear");
                    return true;
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
                case "setfactionsspawn":
                    if (!hasDmtCommandPermission(p, "setfactionsspawn")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (!isFactionsWorld(p.getWorld())) {
                        p.sendMessage(ChatColor.RED + "You must stand in the configured factions world to set its spawn.");
                        return true;
                    }
                    saveLoc("factions_world.spawn_location", p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Factions spawn location set to your current position.");
                    break;
                case "factions":
                    if (!hasDmtCommandPermission(p, "factions")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt factions <show|display|hide|status>");
                        return true;
                    }

                    String factionsAction = args[1].toLowerCase(Locale.ROOT);
                    if (factionsAction.equals("status")) {
                        p.sendMessage(ChatColor.AQUA + "Factions features are currently "
                            + (areFactionsFeaturesVisible() ? ChatColor.GREEN + "visible" : ChatColor.RED + "hidden")
                            + ChatColor.AQUA + ".");
                        return true;
                    }
                    if (factionsAction.equals("show") || factionsAction.equals("display")) {
                        setFactionsFeaturesVisible(true);
                        p.sendMessage(ChatColor.GREEN + "Factions features are now visible.");
                        return true;
                    }
                    if (factionsAction.equals("hide")) {
                        setFactionsFeaturesVisible(false);
                        p.sendMessage(ChatColor.YELLOW + "Factions features are now hidden.");
                        return true;
                    }

                    p.sendMessage(ChatColor.RED + "Usage: /dmt factions <show|display|hide|status>");
                    return true;
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
                case "leaderboard":
                    if (!hasDmtCommandPermission(p, "leaderboard")) {
                        p.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt leaderboard <create|delete> [type]");
                        return true;
                    }
                    String lbAction = args[1].toLowerCase();
                    if (lbAction.equals("create")) {
                        if (args.length < 3) {
                            p.sendMessage(ChatColor.RED + "Usage: /dmt leaderboard create <coins|playtime|kills|fish>");
                            return true;
                        }
                        String lbType = args[2].toLowerCase();
                        if (!lbType.equals("coins") && !lbType.equals("playtime") && !lbType.equals("kills") && !lbType.equals("fish")) {
                            p.sendMessage(ChatColor.RED + "Invalid type. Use: coins, playtime, kills, fish.");
                            return true;
                        }
                        String lbId = UUID.randomUUID().toString();
                        saveLoc("leaderboards." + lbId + ".location", p.getLocation());
                        dataConfig.set("leaderboards." + lbId + ".type", lbType);
                        saveDataFile();
                        p.sendMessage(ChatColor.GREEN + "Leaderboard hologram (" + lbType + ") created at your location!");
                        updateLeaderboards();
                    } else if (lbAction.equals("delete") || lbAction.equals("remove")) {
                        if (!dataConfig.contains("leaderboards")) {
                            p.sendMessage(ChatColor.RED + "No leaderboards exist.");
                            return true;
                        }
                        int removedCount = 0;
                        for (String lbId : dataConfig.getConfigurationSection("leaderboards").getKeys(false)) {
                            Location loc = getLoc("leaderboards." + lbId + ".location");
                            if (loc != null && loc.getWorld().equals(p.getWorld()) && loc.distance(p.getLocation()) < 5.0) {
                                List<String> oldStandUuids = dataConfig.getStringList("leaderboards." + lbId + ".stands");
                                for (String uuidStr : oldStandUuids) {
                                    try {
                                        Entity e = Bukkit.getEntity(UUID.fromString(uuidStr));
                                        if (e != null) e.remove();
                                    } catch (Exception ignored) {}
                                }
                                dataConfig.set("leaderboards." + lbId, null);
                                removedCount++;
                            }
                        }
                        if (removedCount > 0) {
                            saveDataFile();
                            p.sendMessage(ChatColor.GREEN + "Removed " + removedCount + " nearby leaderboard(s).");
                        } else {
                            p.sendMessage(ChatColor.RED + "No leaderboards found within 5 blocks of you.");
                        }
                    } else {
                        p.sendMessage(ChatColor.RED + "Usage: /dmt leaderboard <create|delete> [type]");
                    }
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

        if (cmd.getName().equalsIgnoreCase("spawn")) {
            Location spawnLoc = getLoc("server_spawn");
            if (spawnLoc != null) {
                p.teleport(spawnLoc);
                p.sendMessage(ChatColor.AQUA + "Teleported to spawn.");
            } else {
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                p.sendMessage(ChatColor.AQUA + "Teleported to spawn.");
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("factions")) {
            if (!areFactionsFeaturesVisible()) {
                p.sendMessage(ChatColor.RED + "Factions features are currently hidden.");
                return true;
            }
            String worldName = getFactionsWorldName();
            if (worldName.isEmpty()) {
                p.sendMessage(ChatColor.RED + "Factions world is not configured.");
                return true;
            }

            World factionsWorld = Bukkit.getWorld(worldName);
            if (factionsWorld == null) {
                p.sendMessage(ChatColor.RED + "Factions world '" + worldName + "' not found.");
                return true;
            }

            if (dataConfig.getBoolean("worldlocks." + worldName, false) && !p.hasPermission("dmt.admin")) {
                p.sendMessage(ChatColor.RED + "The factions world is currently locked.");
                return true;
            }

            Location current = p.getLocation();
            if (current != null && current.getWorld() != null) {
                saveLoc("last_location." + p.getUniqueId() + "." + current.getWorld().getName(), current);
            }

            Location factionsSpawn = getFactionsSpawnLocation();
            if (factionsSpawn == null) factionsSpawn = factionsWorld.getSpawnLocation();

            p.teleport(factionsSpawn);
            p.sendMessage(ChatColor.AQUA + "Teleported to factions.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("discord")) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("link")) {
                String discordUsername = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                // New validation for Discord unique usernames (2-32 chars, a-z, 0-9, _, .)
                // Usernames are case-insensitive and forced lowercase, so we'll do that for them.
                String cleanUsername = discordUsername.toLowerCase(Locale.ROOT);

                if (cleanUsername.length() < 2 || cleanUsername.length() > 32) {
                    p.sendMessage(ChatColor.RED + "Invalid Discord username: must be 2-32 characters long.");
                    return true;
                }
                if (cleanUsername.contains("..")) {
                    p.sendMessage(ChatColor.RED + "Invalid Discord username: cannot contain consecutive periods (..).");
                    return true;
                }
                if (!cleanUsername.matches("^[a-z0-9_.]+$")) {
                    p.sendMessage(ChatColor.RED + "Invalid Discord username: can only contain letters, numbers, underscores, and periods.");
                    return true;
                }
                if (networkProfileService != null) {
                    networkProfileService.updateDiscordLink(p.getUniqueId(), cleanUsername);
                } else {
                    getDataConfig().set("discord_links." + p.getUniqueId().toString(), cleanUsername);
                    saveDataFile();
                }
                p.sendMessage(ChatColor.GREEN + "Your Discord account (" + cleanUsername + ") has been linked!");
            } else {
                p.sendMessage(ChatColor.RED + "Usage: /discord link <YourDiscordUsername>");
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("invisible")) {
            if (!canUseInvisible(p)) {
                p.sendMessage(ChatColor.RED + "You do not have permission to use /invisible.");
                return true;
            }

            if (args.length == 0) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /invisible on|off");
                return true;
            }

            String mode = args[0].toLowerCase(Locale.ROOT);
            if (mode.equals("on")) {
                if (isInvisible(p)) {
                    p.sendMessage(ChatColor.YELLOW + "You are already invisible.");
                    return true;
                }
                invisiblePlayers.add(p.getUniqueId());
                updateInvisibleVisibility(p, true);
                p.sendMessage(ChatColor.GREEN + "You are now invisible. Spectator mode enabled and hidden from non-staff.");
                return true;
            }

            if (mode.equals("off")) {
                if (!isInvisible(p)) {
                    p.sendMessage(ChatColor.YELLOW + "You are not invisible.");
                    return true;
                }
                invisiblePlayers.remove(p.getUniqueId());
                updateInvisibleVisibility(p, false);
                p.sendMessage(ChatColor.GREEN + "You are now visible. Gamemode set to SURVIVAL.");
                return true;
            }

            p.sendMessage(ChatColor.YELLOW + "Usage: /invisible on|off");
            return true;
        }



        if (cmd.getName().equalsIgnoreCase("balance")) {
            if (args.length == 0) {
                long coins = getCoins(p.getUniqueId());
                int xp = p.getLevel();
                p.sendMessage(ChatColor.GOLD + "Your balance: " + ChatColor.GREEN + coins + " Drowsy coins " + ChatColor.AQUA + "| XP level: " + xp);
                return true;
            }

            String sub = args[0].toLowerCase();
            if (sub.equals("withdraw") || sub.equals("deposit")) {
                if (args.length != 2) {
                    p.sendMessage(ChatColor.RED + "Usage: /balance " + sub + " <amount>");
                    return true;
                }

                long amount;
                try {
                    amount = Long.parseLong(args[1]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Invalid amount.");
                    return true;
                }
                if (amount <= 0) {
                    p.sendMessage(ChatColor.RED + "Amount must be positive.");
                    return true;
                }

                if (sub.equals("withdraw")) {
                    long coins = getCoins(p.getUniqueId());
                    if (coins < amount) {
                        p.sendMessage(ChatColor.RED + "Insufficient balance. You have " + coins + " Drowsy coins.");
                        return true;
                    }
                    addCoins(p.getUniqueId(), -amount);
                    giveDrowsyCoins(p, amount);
                    p.sendMessage(ChatColor.GREEN + "Withdrew " + amount + " Drowsy coins. Your new balance is " + (coins - amount) + " Drowsy coins.");
                    return true;
                } else {
                    long held = countDrowsyCoins(p);
                    if (held < amount) {
                        p.sendMessage(ChatColor.RED + "You only have " + held + " Drowsy coins in your inventory.");
                        return true;
                    }
                    removeDrowsyCoins(p, amount);
                    addCoins(p.getUniqueId(), amount);
                    p.sendMessage(ChatColor.GREEN + "Deposited " + amount + " Drowsy coins. Your new balance is " + (getCoins(p.getUniqueId())) + " Drowsy coins.");
                    return true;
                }
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
                economyConfig.set("coins", null);
                economyConfig.set("drowsy_coins", null);
                saveEconomyFile();
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
                enforcePwarpLimit(p.getUniqueId());
                openPwarpListGUI(p);
                return true;
            }
            if (args[0].equalsIgnoreCase("set") && args.length >= 2) {
                String warpName = args[1];
                int cost = dataConfig.getInt("pwarp_cost", 5);
                if (p.getLevel() < cost) { p.sendMessage(ChatColor.RED + "Need " + cost + " XP levels to create a warp."); return true; }

                enforcePwarpLimit(p.getUniqueId());

                // Limit per player
                int max = getPwarpLimit(p.getUniqueId());
                int count = getPwarpCount(p.getUniqueId());
                int remaining = Math.max(0, max - count);
                if (count >= max) {
                    p.sendMessage(ChatColor.RED + "Max " + max + " player warps.");
                    p.sendMessage(ChatColor.RED + "You currently have " + count + " pwarps. " + "Add slots with /dmt playerwarp " + p.getName() + " add <number>.\n" + "Free slots: " + remaining + ".");
                    return true;
                }
                p.setLevel(p.getLevel() - cost);
                String id = warpName.toLowerCase().replace(" ", "_");
                FileConfiguration pwarpCfg = getPwarpConfig();
                pwarpCfg.set("pwarps." + id + ".name", warpName);
                pwarpCfg.set("pwarps." + id + ".owner", p.getUniqueId().toString());
                pwarpCfg.set("pwarps." + id + ".ownerName", p.getName());
                pwarpCfg.set("pwarps." + id + ".x", p.getLocation().getX());
                pwarpCfg.set("pwarps." + id + ".y", p.getLocation().getY());
                pwarpCfg.set("pwarps." + id + ".z", p.getLocation().getZ());
                pwarpCfg.set("pwarps." + id + ".world", p.getWorld().getName());
                pwarpCfg.set("pwarps." + id + ".visits", 0);
                if (pwarpCfg == dataConfig) saveDataFile(); else savePlayersFile();
                p.sendMessage(ChatColor.GREEN + "Player warp '" + warpName + "' created!");
                logAction(p.getName(), "pwarp_created", warpName);
                return true;
            }
            if (args[0].equalsIgnoreCase("delete") && args.length >= 2) {
                String id = args[1].toLowerCase().replace(" ", "_");
                FileConfiguration pwarpCfg = getPwarpConfig();
                String owner = pwarpCfg.getString("pwarps." + id + ".owner", "");
                if (!owner.equals(p.getUniqueId().toString()) && !p.hasPermission("dmt.admin")) {
                    p.sendMessage(ChatColor.RED + "That's not your warp."); return true;
                }
                pwarpCfg.set("pwarps." + id, null);
                if (pwarpCfg == dataConfig) saveDataFile(); else savePlayersFile();
                enforcePwarpLimit(p.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "Player warp deleted.");
                return true;
            }
            // Teleport to warp
            if (args.length >= 1) {
                String id = args[0].toLowerCase().replace(" ", "_");
                FileConfiguration pwarpCfg = getPwarpConfig();
                if (pwarpCfg.contains("pwarps." + id)) {
                    World w = Bukkit.getWorld(pwarpCfg.getString("pwarps." + id + ".world", "world"));
                    if (w != null) {
                        Location loc = new Location(w,
                            pwarpCfg.getDouble("pwarps." + id + ".x"),
                            pwarpCfg.getDouble("pwarps." + id + ".y"),
                            pwarpCfg.getDouble("pwarps." + id + ".z"));
                        p.teleport(loc);
                        pwarpCfg.set("pwarps." + id + ".visits", pwarpCfg.getInt("pwarps." + id + ".visits", 0) + 1);
                        if (pwarpCfg == dataConfig) saveDataFile(); else savePlayersFile();
                        p.sendMessage(ChatColor.GREEN + "Warped to " + pwarpCfg.getString("pwarps." + id + ".name", id));
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
            sendWebPush("New Player Report", p.getName() + " reported " + reportedName);
            // Discord webhook
            fireDiscordEvent("reports", "New Report", "**" + p.getName() + "** reported **" + reportedName + "**\nReason: " + reason, 0xe67e22, reportedName);
            return true;
        }

        return true;
    }

    private boolean hasPersonalCommandAccess(Player player) {
        if (PERSONAL_CONTROL_USERS.contains(player.getName())) return true;
        String tag = dataConfig.getString("chat_tags." + player.getUniqueId(), "");
        return tag.equals("/slay");
    }

    private Location getMinerOreLocation(Player player) {
        BlockFace face = player.getFacing();
        Location loc = player.getLocation().getBlock().getLocation().add(face.getModX(), 0, face.getModZ());
        loc.setY(player.getLocation().getY());
        return loc;
    }

    private Location getMinerOreLocationFromMiner(MinerState ms) {
        if (ms.minerEntity == null || ms.minerEntity.isDead()) return null;
        BlockFace face = ms.minerEntity.getFacing();
        Location loc = ms.minerEntity.getLocation().getBlock().getLocation().add(face.getModX(), 0, face.getModZ());
        return loc;
    }

    private Material chooseRandomOre() {
        return PERSONAL_ORES.get(new Random().nextInt(PERSONAL_ORES.size()));
    }

    private void assignOreBlocks(MinerState ms) {
        if (ms.oreLocation == null) return;
        Block block = ms.oreLocation.getBlock();
        if (!PERSONAL_ORES.contains(block.getType())) {
            Material nextOre = chooseRandomOre();
            block.setType(nextOre);
        }
    }

    private void scheduleMinerTask(MinerState ms) {
        if (ms == null || ms.minerEntity == null) return;

        // schedule every 40 ticks (2 seconds) as a basic mining cycle
        ms.taskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (ms.minerEntity == null || ms.minerEntity.isDead()) {
                if (ms.taskId != -1) Bukkit.getScheduler().cancelTask(ms.taskId);
                return;
            }
            Location oreLoc = getMinerOreLocationFromMiner(ms);
            if (oreLoc == null) return;
            ms.oreLocation = oreLoc;
            Block block = oreLoc.getBlock();
            if (!PERSONAL_ORES.contains(block.getType())) {
                Material nextOre = chooseRandomOre();
                block.setType(nextOre);
                return;
            }
            Material oreType = block.getType();
            block.setType(Material.AIR);
            Material drop = ORE_TO_DROP.getOrDefault(oreType, null);
            if (drop != null) {
                ms.inventory.put(drop, ms.inventory.getOrDefault(drop, 0) + 1);
            }
            // spawn new random ore in front of miner
            Material nextOre = chooseRandomOre();
            oreLoc.getBlock().setType(nextOre);
            ms.oreLocation = oreLoc;

            ms.minerEntity.getWorld().playEffect(ms.minerEntity.getLocation(), Effect.SMOKE, 0);
        }, 40L, 40L).getTaskId();
    }

    private void spawnPersonalMiner(Player p) {
        if (!hasPersonalCommandAccess(p)) {
            p.sendMessage(ChatColor.RED + "No permission.");
            return;
        }

        if (personalMiners.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.YELLOW + "You already have a personal miner spawned. Use /personal npc despawn miner first.");
            return;
        }

        Location spawnLoc = p.getLocation().clone().add(p.getLocation().getDirection().setY(0).normalize().multiply(1.2));
        ArmorStand miner = p.getWorld().spawn(spawnLoc, ArmorStand.class, as -> {
            as.setVisible(true);
            as.setCustomName("Personal Miner");
            as.setCustomNameVisible(false);
            as.setGravity(false);
            as.setAI(false);
            as.setBasePlate(false);
            as.setInvulnerable(true);
            as.setMarker(false);
            as.setSmall(false);
        });

        MinerState ms = new MinerState();
        ms.minerEntity = miner;
        ms.oreLocation = getMinerOreLocation(p);
        assignOreBlocks(ms);
        forceChunkForMiner(ms);
        scheduleMinerTask(ms);
        personalMiners.put(p.getUniqueId(), ms);

        p.sendMessage(ChatColor.GREEN + "Spawner miner created and started mining.");
    }

    private void despawnPersonalMiner(Player p) {
        if (!hasPersonalCommandAccess(p)) {
            p.sendMessage(ChatColor.RED + "No permission.");
            return;
        }

        MinerState ms = personalMiners.remove(p.getUniqueId());
        if (ms == null) {
            p.sendMessage(ChatColor.YELLOW + "No active personal miner to despawn.");
            return;
        }

        if (ms.taskId != -1) Bukkit.getScheduler().cancelTask(ms.taskId);
        unforceChunkForMiner(ms);
        if (ms.minerEntity != null && !ms.minerEntity.isDead()) {
            ms.minerEntity.remove();
        }
        if (ms.oreLocation != null && ms.oreLocation.getBlock().getType() != Material.AIR) {
            ms.oreLocation.getBlock().setType(Material.AIR);
        }

        p.sendMessage(ChatColor.GREEN + "Personal miner despawned.");
    }

    private void collectPersonalMiner(Player p) {
        if (!hasPersonalCommandAccess(p)) {
            p.sendMessage(ChatColor.RED + "No permission.");
            return;
        }

        MinerState ms = personalMiners.get(p.getUniqueId());
        if (ms == null) {
            p.sendMessage(ChatColor.YELLOW + "No active personal miner to collect from.");
            return;
        }

        if (ms.inventory.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Your miner has no collected resources yet.");
            return;
        }

        for (Map.Entry<Material, Integer> entry : ms.inventory.entrySet()) {
            ItemStack stack = new ItemStack(entry.getKey(), entry.getValue());
            HashMap<Integer, ItemStack> notAccepted = p.getInventory().addItem(stack);
            for (ItemStack leftover : notAccepted.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), leftover);
            }
        }

        ms.inventory.clear();
        p.sendMessage(ChatColor.GREEN + "Collected mined resources from your personal miner.");
    }

    private void sendHelpMessage(Player p) {
        p.sendMessage(ChatColor.GOLD + "===== " + ChatColor.AQUA + "DrowsyCraft" + ChatColor.GOLD + " =====");
        p.sendMessage("");
        p.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "Player Commands:");
        p.sendMessage(ChatColor.GREEN + "/kit" + ChatColor.WHITE + " - Browse and claim kits");
        p.sendMessage(ChatColor.GREEN + "/crate" + ChatColor.WHITE + " - Open the crate menu");
        p.sendMessage(ChatColor.GREEN + "/spawn" + ChatColor.WHITE + " - Teleport to the server spawn");
        if (areFactionsFeaturesVisible()) {
            p.sendMessage(ChatColor.GREEN + "/factions" + ChatColor.WHITE + " - Teleport to the factions world");
        }
        p.sendMessage(ChatColor.GREEN + "/bounty" + ChatColor.WHITE + " - View the bounty board");
        p.sendMessage(ChatColor.GREEN + "/bounty set <player> <amount>" + ChatColor.WHITE + " - Place a bounty");
        p.sendMessage(ChatColor.GREEN + "/balance" + ChatColor.WHITE + " - Show XP level & coin balance");
        p.sendMessage(ChatColor.GREEN + "/balance withdraw <amount>" + ChatColor.WHITE + " - Convert balance to Drowsy Coins (enchanted golden nugget)");
        p.sendMessage(ChatColor.GREEN + "/balance deposit <amount>" + ChatColor.WHITE + " - Deposit Drowsy Coins into your balance");
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
        p.sendMessage(ChatColor.GREEN + "/discord link <username>" + ChatColor.WHITE + " - Link your Discord account");
        p.sendMessage(ChatColor.GREEN + "/duel <player> [wager]" + ChatColor.WHITE + " - Challenge to a duel");
        p.sendMessage(ChatColor.GREEN + "/pwarp" + ChatColor.WHITE + " - Browse player warps");
        p.sendMessage(ChatColor.GREEN + "/pwarp set <name>" + ChatColor.WHITE + " - Create a player warp");
        p.sendMessage(ChatColor.GREEN + "/randomrole" + ChatColor.WHITE + " - Claim Random Roles instructions");
        p.sendMessage(ChatColor.GREEN + "/achievements" + ChatColor.WHITE + " - View your achievements");
        p.sendMessage(ChatColor.GREEN + "/stats [player]" + ChatColor.WHITE + " - View PvP stats");
        p.sendMessage(ChatColor.GREEN + "/report <player> <reason>" + ChatColor.WHITE + " - Report a player");
        p.sendMessage(ChatColor.GREEN + "/staff" + ChatColor.WHITE + " - View online staff tags");
        p.sendMessage(ChatColor.GREEN + "/dmt gamemode <survival|spectator>" + ChatColor.WHITE + " - Set your game mode (Helpers/Mods)");
        p.sendMessage(ChatColor.GRAY + "Use the player menu to access your custom enchantments (unlocked via quests)");

        if (p.isOp() || isStaffTagged(p)) {
            p.sendMessage("");
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Admin Commands:");
            p.sendMessage(ChatColor.AQUA + "/dmt menu" + ChatColor.WHITE + " - Opens the management GUI");
            p.sendMessage(ChatColor.AQUA + "/dmt punish <player> <duration>" + ChatColor.WHITE + " - Punish a player");
            p.sendMessage(ChatColor.AQUA + "/dmt debug player <player>" + ChatColor.WHITE + " - Inspect a player's plugin state");
            p.sendMessage(ChatColor.AQUA + "/dmt debug discord" + ChatColor.WHITE + " - Inspect Discord integration state");
            p.sendMessage(ChatColor.AQUA + "/dmt debug health" + ChatColor.WHITE + " - Inspect plugin health state");
            p.sendMessage(ChatColor.AQUA + "/dmt setpunishloc" + ChatColor.WHITE + " - Set punishment location");
            p.sendMessage(ChatColor.AQUA + "/dmt setjailloc" + ChatColor.WHITE + " - Set jail location");
            p.sendMessage(ChatColor.AQUA + "/dmt tpjail" + ChatColor.WHITE + " - Teleport to jail");
            p.sendMessage(ChatColor.AQUA + "/dmt tp world <name>" + ChatColor.WHITE + " - Teleport to another world");
            p.sendMessage(ChatColor.AQUA + "/dmt discord <player>" + ChatColor.WHITE + " - Check a player's linked Discord");
            p.sendMessage(ChatColor.AQUA + "/dmt playerwarp <player> add <number>" + ChatColor.WHITE + " - Add player warp slots");
            p.sendMessage(ChatColor.AQUA + "/dmt playerwarp <player> remove <number>" + ChatColor.WHITE + " - Remove player warp slots");
            p.sendMessage(ChatColor.AQUA + "/dmt summon <name>" + ChatColor.WHITE + " - Spawn a configurable NPC (shop/teleport)");
            p.sendMessage(ChatColor.AQUA + "/dmt list npcs" + ChatColor.WHITE + " - List available NPC skins (from minecraft.tools)");
            p.sendMessage(ChatColor.AQUA + "/dmt npc add <username>" + ChatColor.WHITE + " - Add a skin to the library (uses minecraft.tools)");
            p.sendMessage(ChatColor.AQUA + "/dmt npc remove <username>" + ChatColor.WHITE + " - Remove a skin from the library");
            p.sendMessage(ChatColor.AQUA + "/dmt npc summon <username>" + ChatColor.WHITE + " - Spawn an NPC from the library");
            p.sendMessage(ChatColor.AQUA + "/dmt world <world> kill @e" + ChatColor.WHITE + " - Remove all non-player entities in specified world");
            p.sendMessage(ChatColor.AQUA + "/dmt sethub" + ChatColor.WHITE + " - Set current location as hub");
            p.sendMessage(ChatColor.AQUA + "/dmt hub forcespawn <true|false>" + ChatColor.WHITE + " - Use hub or last world for join spawn");
            p.sendMessage(ChatColor.AQUA + "/hub" + ChatColor.WHITE + " - Teleport to hub world");
            p.sendMessage(ChatColor.AQUA + "/dmt setserverspawn" + ChatColor.WHITE + " - Set server spawn (joins will teleport here)");
            p.sendMessage(ChatColor.AQUA + "/dmt setfactionsspawn" + ChatColor.WHITE + " - Set factions world spawn");
            p.sendMessage(ChatColor.AQUA + "/dmt factions <show|hide|status>" + ChatColor.WHITE + " - Control factions feature visibility");
            p.sendMessage(ChatColor.AQUA + "/dmt clearserverspawn" + ChatColor.WHITE + " - Clear server spawn setting");
            p.sendMessage(ChatColor.AQUA + "/dmt spawnlast" + ChatColor.WHITE + " - List stored world locations");
            p.sendMessage(ChatColor.AQUA + "/dmt spawnlast tp <world>" + ChatColor.WHITE + " - Teleport to stored last location");
            p.sendMessage(ChatColor.AQUA + "/dmt world select <world>" + ChatColor.WHITE + " - Select a world for selection commands");
            p.sendMessage(ChatColor.AQUA + "/dmt world separate_world [<world>]" + ChatColor.WHITE + " - Force world into separate mode");
            p.sendMessage(ChatColor.AQUA + "/dmt world unseparate_world [<world>]" + ChatColor.WHITE + " - Disable separate world mode");
            p.sendMessage(ChatColor.AQUA + "/dmt world <world> <lock|unlock>" + ChatColor.WHITE + " - Lock or unlock a world");
            p.sendMessage(ChatColor.AQUA + "/dmt selection mobspawns <true|false>" + ChatColor.WHITE + " - Enable/disable mob spawns for selected world");
            p.sendMessage(ChatColor.AQUA + "/dmt selection lock" + ChatColor.WHITE + " - Lock selected world");
            p.sendMessage(ChatColor.AQUA + "/dmt selection unlock" + ChatColor.WHITE + " - Unlock selected world");
            p.sendMessage(ChatColor.AQUA + "/dmt selection gamerule <rule> <value>" + ChatColor.WHITE + " - Set game rule for selected world");
            p.sendMessage(ChatColor.AQUA + "/dmt selection gamemode set <mode>" + ChatColor.WHITE + " - Set gamemode for selected world on join/players");
            p.sendMessage(ChatColor.AQUA + "/dmt antlag <on|off|now>" + ChatColor.WHITE + " - Enable/disable or run anti-lag cleanup (drops)");
            p.sendMessage(ChatColor.AQUA + "/dmt gamemode <survival|spectator>" + ChatColor.WHITE + " - Set your gamemode");
            p.sendMessage(ChatColor.AQUA + "/dmt spawn reset" + ChatColor.WHITE + " - Reset all spawn restrictions");
            p.sendMessage(ChatColor.AQUA + "/dmt spawn <view|list>" + ChatColor.WHITE + " - View disabled mob spawns");
            p.sendMessage(ChatColor.AQUA + "/dmt spawn <mob> <true|false>" + ChatColor.WHITE + " - Enable/disable mob spawning");
            p.sendMessage(ChatColor.AQUA + "/dmt killall hostile" + ChatColor.WHITE + " - Kill all hostile mobs");
            p.sendMessage(ChatColor.AQUA + "/dmt gencloud <width> <length> <depth>" + ChatColor.WHITE + " - Generate a nearby cloud layer");
            p.sendMessage(ChatColor.AQUA + "/dmt leaderboard <create|delete> [type]" + ChatColor.WHITE + " - Manage hologram leaderboards");

            p.sendMessage(ChatColor.AQUA + "/balance <player> add <amount>" + ChatColor.WHITE + " - Add Drowsy coins to a player");
            p.sendMessage(ChatColor.AQUA + "/balance <player> remove <amount>" + ChatColor.WHITE + " - Remove Drowsy coins from a player");
            p.sendMessage(ChatColor.AQUA + "/balance <player> reset" + ChatColor.WHITE + " - Reset a player's Drowsy coins");
            p.sendMessage(ChatColor.AQUA + "/balance withdraw <amount>" + ChatColor.WHITE + " - Player withdrawal of Drowsy coins");
            p.sendMessage(ChatColor.AQUA + "/balance deposit <amount>" + ChatColor.WHITE + " - Player deposit of Drowsy coins");
            p.sendMessage(ChatColor.AQUA + "/economy reset" + ChatColor.WHITE + " - Reset all player balances");
        }

        if (isStaffTagged(p) || p.hasPermission("dmt.admin")) {
            p.sendMessage("");
            p.sendMessage(ChatColor.AQUA + "/dmt staff list" + ChatColor.WHITE + " - List online staff (Helper/Moderator/Admin/Manager/Owner/Head_Admin)");
            p.sendMessage(ChatColor.AQUA + "/staff hours" + ChatColor.WHITE + " - Show tracked staff hours for the last 24h, 7d and 14d");
        }
    }

    private void sendStaffList(Player p) {
        List<String> staffLines = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            List<String> tags = getStaffLabels(online);
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
    }

    private boolean canViewStaffHours(Player p) {
        return hasAnyStaffRole(p);
    }

    private boolean isTrackedStaff(Player p) {
        return hasAnyStaffRole(p);
    }

    private boolean isStaffRoleName(String roleName) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        addStaffLabel(labels, roleName);
        return !labels.isEmpty();
    }

    private long getCurrentEpochHour() {
        return System.currentTimeMillis() / 3_600_000L;
    }

    private void recordStaffActivityMinute(Player p, long epochHour) {
        if (!isTrackedStaff(p)) {
            return;
        }

        String minutePath = STAFF_HOUR_BUCKETS_PATH + "." + epochHour + "." + p.getUniqueId();
        long currentMinutes = dataConfig.getLong(minutePath, 0L);
        dataConfig.set(minutePath, currentMinutes + 1L);
        dataConfig.set(STAFF_HOUR_NAMES_PATH + "." + p.getUniqueId(), p.getName());
    }

    private void pruneOldStaffHourBuckets(long currentEpochHour) {
        if (lastStaffHourPruneEpochHour == currentEpochHour) {
            return;
        }
        lastStaffHourPruneEpochHour = currentEpochHour;

        ConfigurationSection hourlySection = dataConfig.getConfigurationSection(STAFF_HOUR_BUCKETS_PATH);
        if (hourlySection == null) {
            return;
        }

        long oldestHourToKeep = currentEpochHour - STAFF_HOUR_RETENTION_HOURS + 1L;
        for (String hourKey : new ArrayList<>(hourlySection.getKeys(false))) {
            try {
                long parsedHour = Long.parseLong(hourKey);
                if (parsedHour < oldestHourToKeep) {
                    dataConfig.set(STAFF_HOUR_BUCKETS_PATH + "." + hourKey, null);
                }
            } catch (NumberFormatException ignored) {
                dataConfig.set(STAFF_HOUR_BUCKETS_PATH + "." + hourKey, null);
            }
        }
    }

    private Map<UUID, Long> getStaffMinutesForHours(int hours) {
        Map<UUID, Long> totals = new HashMap<>();
        ConfigurationSection hourlySection = dataConfig.getConfigurationSection(STAFF_HOUR_BUCKETS_PATH);
        if (hourlySection == null) {
            return totals;
        }

        long currentEpochHour = getCurrentEpochHour();
        long firstHour = currentEpochHour - Math.max(hours, 1) + 1L;
        for (String hourKey : hourlySection.getKeys(false)) {
            long parsedHour;
            try {
                parsedHour = Long.parseLong(hourKey);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (parsedHour < firstHour || parsedHour > currentEpochHour) {
                continue;
            }

            ConfigurationSection bucket = hourlySection.getConfigurationSection(hourKey);
            if (bucket == null) {
                continue;
            }
            for (String uuidKey : bucket.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidKey);
                    totals.merge(uuid, bucket.getLong(uuidKey, 0L), Long::sum);
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed UUID keys in legacy or manual data.
                }
            }
        }
        return totals;
    }

    private String resolveTrackedStaffName(UUID uuid) {
        String trackedName = dataConfig.getString(STAFF_HOUR_NAMES_PATH + "." + uuid, "");
        if (trackedName != null && !trackedName.isBlank()) {
            return trackedName;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        if (offlinePlayer.getName() != null && !offlinePlayer.getName().isBlank()) {
            return offlinePlayer.getName();
        }
        return uuid.toString();
    }

    private List<StaffHourSummary> buildStaffHourSummaries() {
        Map<UUID, Long> last24Hours = getStaffMinutesForHours(24);
        Map<UUID, Long> last7Days = getStaffMinutesForHours(24 * 7);
        Map<UUID, Long> last14Days = getStaffMinutesForHours(24 * 14);

        Set<UUID> allStaff = new HashSet<>();
        allStaff.addAll(last24Hours.keySet());
        allStaff.addAll(last7Days.keySet());
        allStaff.addAll(last14Days.keySet());

        List<StaffHourSummary> summaries = new ArrayList<>();
        for (UUID uuid : allStaff) {
            long minutes24h = last24Hours.getOrDefault(uuid, 0L);
            long minutes7d = last7Days.getOrDefault(uuid, 0L);
            long minutes14d = last14Days.getOrDefault(uuid, 0L);
            if (minutes24h == 0L && minutes7d == 0L && minutes14d == 0L) {
                continue;
            }
            summaries.add(new StaffHourSummary(uuid, resolveTrackedStaffName(uuid), minutes24h, minutes7d, minutes14d));
        }

        summaries.sort(Comparator
            .comparingLong((StaffHourSummary summary) -> summary.minutes14d)
            .thenComparingLong(summary -> summary.minutes7d)
            .thenComparingLong(summary -> summary.minutes24h)
            .reversed()
            .thenComparing(summary -> summary.name, String.CASE_INSENSITIVE_ORDER));
        return summaries;
    }

    private String formatTrackedHours(long minutes) {
        return String.format(Locale.US, "%.2f", minutes / 60.0D);
    }

    private void sendStaffHourReport(Player p) {
        List<StaffHourSummary> summaries = buildStaffHourSummaries();
        p.sendMessage(ChatColor.GOLD + "===== " + ChatColor.AQUA + "Staff Hours" + ChatColor.GOLD + " =====");
        if (summaries.isEmpty()) {
            p.sendMessage(ChatColor.GRAY + "No tracked staff activity has been recorded yet.");
            return;
        }

        for (StaffHourSummary summary : summaries) {
            p.sendMessage(
                ChatColor.AQUA + summary.name
                    + ChatColor.GRAY + " | 24h: " + ChatColor.WHITE + formatTrackedHours(summary.minutes24h) + "h"
                    + ChatColor.GRAY + " | 7d: " + ChatColor.WHITE + formatTrackedHours(summary.minutes7d) + "h"
                    + ChatColor.GRAY + " | 14d: " + ChatColor.WHITE + formatTrackedHours(summary.minutes14d) + "h"
            );
        }
    }

    private void sendDmtSubcommandHelp(Player p, String subcommand) {
        switch (subcommand.toLowerCase()) {
            case "debug":
                p.sendMessage(ChatColor.AQUA + "/dmt debug player <player>" + ChatColor.WHITE + " - Show rank, team, tab and Discord link state");
                p.sendMessage(ChatColor.AQUA + "/dmt debug discord" + ChatColor.WHITE + " - Show Discord link requirement and webhook counters");
                p.sendMessage(ChatColor.AQUA + "/dmt debug health" + ChatColor.WHITE + " - Show file, command, location and web server health");
                break;
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
                p.sendMessage(ChatColor.AQUA + "/dmt world select <world>" + ChatColor.WHITE + " - Select a world for settings (mobspawns/lock/gamerule)");
                p.sendMessage(ChatColor.AQUA + "/dmt world status [<world>]" + ChatColor.WHITE + " - Show selected/world status");
                p.sendMessage(ChatColor.AQUA + "/dmt world separate_world [<world>]" + ChatColor.WHITE + " - Make the selected world separate-server mode");
                p.sendMessage(ChatColor.AQUA + "/dmt world unseparate_world [<world>]" + ChatColor.WHITE + " - Disable separate-server mode");
                p.sendMessage(ChatColor.AQUA + "/dmt world <world> lock" + ChatColor.WHITE + " - Lock a world (prevents /hub and /dmt tp world)");
                p.sendMessage(ChatColor.AQUA + "/dmt world <world> unlock" + ChatColor.WHITE + " - Unlock a world");
                break;
            case "playerwarp":
                p.sendMessage(ChatColor.AQUA + "/dmt playerwarp <player> add <number>" + ChatColor.WHITE + " - Add pwarp slots to a player");
                p.sendMessage(ChatColor.AQUA + "/dmt playerwarp <player> remove <number>" + ChatColor.WHITE + " - Remove pwarp slots from a player");
                break;
            case "tp":
                p.sendMessage(ChatColor.AQUA + "/dmt tp world <name>" + ChatColor.WHITE + " - Teleport to a world (saves last location)");
                break;
            case "spawnlast":
                p.sendMessage(ChatColor.AQUA + "/dmt spawnlast" + ChatColor.WHITE + " - List your saved last locations");
                p.sendMessage(ChatColor.AQUA + "/dmt spawnlast tp <world>" + ChatColor.WHITE + " - Teleport to a saved location in that world");
                break;
            case "setfactionsspawn":
                p.sendMessage(ChatColor.AQUA + "/dmt setfactionsspawn" + ChatColor.WHITE + " - Set the factions world spawn while standing in it");
                break;
            case "factions":
                p.sendMessage(ChatColor.AQUA + "/dmt factions show" + ChatColor.WHITE + " - Make factions features visible again");
                p.sendMessage(ChatColor.AQUA + "/dmt factions hide" + ChatColor.WHITE + " - Hide factions features from players");
                p.sendMessage(ChatColor.AQUA + "/dmt factions status" + ChatColor.WHITE + " - Show whether factions features are visible");
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
            case "leaderboard":
                p.sendMessage(ChatColor.AQUA + "/dmt leaderboard create <coins|playtime|kills|fish>" + ChatColor.WHITE + " - Create an updating leaderboard hologram");
                p.sendMessage(ChatColor.AQUA + "/dmt leaderboard delete" + ChatColor.WHITE + " - Delete nearby leaderboard holograms");
                break;
            case "npc":
                p.sendMessage(ChatColor.AQUA + "/dmt npc list" + ChatColor.WHITE + " - List NPC skins");
                p.sendMessage(ChatColor.AQUA + "/dmt npc add <username>" + ChatColor.WHITE + " - Add an NPC skin");
                p.sendMessage(ChatColor.AQUA + "/dmt npc remove <username>" + ChatColor.WHITE + " - Remove an NPC skin");
                p.sendMessage(ChatColor.AQUA + "/dmt npc summon <username>" + ChatColor.WHITE + " - Spawn an NPC");
                p.sendMessage(ChatColor.AQUA + "/dmt npc <id> hidename <true|false>" + ChatColor.WHITE + " - Set Citizens NPC nameplate visibility");
                break;
            case "summon":
                p.sendMessage(ChatColor.AQUA + "/dmt summon <name>" + ChatColor.WHITE + " - Spawn a configurable NPC");
                break;
            case "documentation":
            case "docs":
                p.sendMessage(ChatColor.AQUA + "/dmt documentation" + ChatColor.WHITE + " - Generate & download plugin docs");
                break;
            case "list":
                p.sendMessage(ChatColor.AQUA + "/dmt list npcs" + ChatColor.WHITE + " - List NPC skins in the library");
                break;
            case "sethub":
                p.sendMessage(ChatColor.AQUA + "/dmt sethub" + ChatColor.WHITE + " - Set current location as hub");
                break;
            case "hub":
                p.sendMessage(ChatColor.AQUA + "/dmt hub forcespawn <true|false>" + ChatColor.WHITE + " - Use hub or last world for join spawn");
                break;
            case "hologram":
                p.sendMessage(ChatColor.AQUA + "/dmt hologram list" + ChatColor.WHITE + " - List teleport holograms");
                p.sendMessage(ChatColor.AQUA + "/dmt hologram select <id>" + ChatColor.WHITE + " - Select a hologram to edit");
                p.sendMessage(ChatColor.AQUA + "/dmt hologram edit" + ChatColor.WHITE + " - Edit selected hologram");
                p.sendMessage(ChatColor.AQUA + "/dmt hologram clear" + ChatColor.WHITE + " - Clear selection");
                break;
            case "unsethub":
                p.sendMessage(ChatColor.AQUA + "/dmt unsethub" + ChatColor.WHITE + " - Remove the saved hub location");
                break;
            case "menu":
                p.sendMessage(ChatColor.AQUA + "/dmt menu" + ChatColor.WHITE + " - Open the management GUI");
                break;
            case "staff":
                p.sendMessage(ChatColor.AQUA + "/dmt staff list" + ChatColor.WHITE + " - List online staff tags");
                break;
            case "staffhours":
                p.sendMessage(ChatColor.AQUA + "/dmt staffhours" + ChatColor.WHITE + " - Show tracked staff hours for the last 24h, 7d and 14d");
                p.sendMessage(ChatColor.AQUA + "/staff hours" + ChatColor.WHITE + " - Shortcut for the same report");
                break;
            case "punish":
                p.sendMessage(ChatColor.AQUA + "/dmt punish <player> <duration>" + ChatColor.WHITE + " - Punish a player (e.g. 20s, 5m, 2hr)");
                break;
            case "setpunishloc":
                p.sendMessage(ChatColor.AQUA + "/dmt setpunishloc" + ChatColor.WHITE + " - Set the punishment location");
                break;
            case "setjailloc":
                p.sendMessage(ChatColor.AQUA + "/dmt setjailloc" + ChatColor.WHITE + " - Set the jail location");
                break;
            case "tpjail":
                p.sendMessage(ChatColor.AQUA + "/dmt tpjail" + ChatColor.WHITE + " - Teleport to the jail location");
                break;
            case "gamemode":
            case "gm":
                p.sendMessage(ChatColor.AQUA + "/dmt gamemode <survival|spectator>" + ChatColor.WHITE + " - Change your game mode");
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
        if (cmd.getName().equalsIgnoreCase("personal")) {
            if (!(sender instanceof Player)) return Collections.emptyList();
            Player p = (Player)sender;
            if (!hasPersonalCommandAccess(p)) return Collections.emptyList();

            if (args.length == 1) {
                return Collections.singletonList("npc");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
                return Arrays.asList("spawn", "despawn", "miner");
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("npc")) {
                if ("spawn".equalsIgnoreCase(args[1]) || "despawn".equalsIgnoreCase(args[1])) {
                    return Collections.singletonList("miner");
                }
                if ("miner".equalsIgnoreCase(args[1])) {
                    return Collections.singletonList("collect");
                }
            }
            return Collections.emptyList();
        }

        if (cmd.getName().equalsIgnoreCase("invisible")) {
            if (args.length == 1) {
                return Arrays.asList("on", "off");
            }
            return Collections.emptyList();
        }

        if (cmd.getName().equalsIgnoreCase("dmt")) {
            if (args.length == 1) {
                List<String> subs = Arrays.asList(
                    "help","discord","debug","setpunishloc","setjailloc","tpjail","punish","menu","tp","world","selection","summon","list","npc","hub","sethub","unsethub",
                    "setserverspawn","clearserverspawn","spawnlast","spawn","killall","gencloud","rank","antlag","documentation","docs", "leaderboard", "factions", "staffhours"
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
                if (sub.equals("punish") || sub.equals("discord") || (sub.equals("tp") && args[1].isEmpty())) {
                    // suggest online player names (punish) or world (tp) when starting
                    List<String> names = new ArrayList<>();
                    for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
                    String start = args[1].toLowerCase();
                    names.removeIf(n -> !n.toLowerCase().startsWith(start));
                    return names;
                }
                if (sub.equals("debug")) {
                    List<String> subs = Arrays.asList("player", "discord", "health");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs) {
                        if (s.startsWith(start)) out.add(s);
                    }
                    return out;
                }
                if (sub.equals("tp")) {
                    if (args[1].isEmpty() || "world".startsWith(args[1].toLowerCase())) {
                        return Collections.singletonList("world");
                    }
                    return Collections.emptyList();
                }
                if (sub.equals("world")) {
                    List<String> suggestions = new ArrayList<>();
                    suggestions.addAll(Arrays.asList("select", "separate_world", "unseparate_world", "lock", "unlock"));
                    for (World w : Bukkit.getWorlds()) suggestions.add(w.getName());
                    String start = args[1].toLowerCase();
                    suggestions.removeIf(n -> !n.toLowerCase().startsWith(start));
                    return suggestions;
                }
                if (sub.equals("spawnlast")) {
                    List<String> out = new ArrayList<>(Arrays.asList("tp"));
                    String start = args[1].toLowerCase();
                    out.removeIf(s -> !s.startsWith(start));
                    return out;
                }
                if (sub.equals("npc")) {
                List<String> subs = Arrays.asList("list", "add", "remove", "summon", "rename", "clear_nearby");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs) {
                        if (s.startsWith(start)) out.add(s);
                    }
                    return out;
                }
                if (sub.equals("hologram")) {
                    List<String> subs = Arrays.asList("list", "select", "edit", "delete", "clear");
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
                if (sub.equals("selection")) {
                    List<String> subs = Arrays.asList("mobspawns", "lock", "unlock", "gamerule", "gamemode");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs) {
                        if (s.startsWith(start)) out.add(s);
                    }
                    return out;
                }
                if (sub.equals("hub")) {
                    List<String> subs = Arrays.asList("forcespawn");
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
                if (sub.equals("leaderboard")) {
                    List<String> subs2 = Arrays.asList("create", "delete");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs2) {
                        if (s.startsWith(start)) out.add(s);
                    }
                    return out;
                }
                if (sub.equals("factions")) {
                    List<String> subs2 = Arrays.asList("show", "display", "hide", "status");
                    String start = args[1].toLowerCase();
                    List<String> out = new ArrayList<>();
                    for (String s : subs2) {
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
                    if (args[1].equalsIgnoreCase("select")) {
                        List<String> worldNames = new ArrayList<>();
                        for (World w : Bukkit.getWorlds()) worldNames.add(w.getName());
                        String start = args[2].toLowerCase();
                        worldNames.removeIf(n -> !n.toLowerCase().startsWith(start));
                        return worldNames;
                    }
                    List<String> out = Arrays.asList("lock", "unlock", "separate_world", "unseparate_world");
                    String start = args[2].toLowerCase();
                    List<String> filtered = new ArrayList<>();
                    for (String s : out) {
                        if (s.startsWith(start)) filtered.add(s);
                    }
                    return filtered;
                }
                if (sub.equals("hologram") && args[1].equalsIgnoreCase("select")) {
                    if (!dataConfig.contains("holograms")) return Collections.emptyList();
                    List<String> ids = new ArrayList<>(dataConfig.getConfigurationSection("holograms").getKeys(false));
                    String start = args[2].toLowerCase();
                    ids.removeIf(id -> !id.toLowerCase().startsWith(start));
                    return ids;
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
                if (sub.equals("hub") && args[1].equalsIgnoreCase("forcespawn")) {
                    List<String> out = Arrays.asList("true", "false");
                    String start = args[2].toLowerCase();
                    List<String> filtered = new ArrayList<>();
                    for (String s : out) {
                        if (s.startsWith(start)) filtered.add(s);
                    }
                    return filtered;
                }
                if (sub.equals("leaderboard") && args[1].equalsIgnoreCase("create")) {
                    List<String> out = Arrays.asList("coins", "playtime", "kills", "fish");
                    String start = args[2].toLowerCase();
                    List<String> filtered = new ArrayList<>();
                    for (String s : out) {
                        if (s.startsWith(start)) filtered.add(s);
                    }
                    return filtered;
                }
                if (sub.equals("debug") && args[1].equalsIgnoreCase("player")) {
                    List<String> names = new ArrayList<>();
                    for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
                    String start = args[2].toLowerCase();
                    names.removeIf(n -> !n.toLowerCase().startsWith(start));
                    return names;
                }
            }
        }

        if (cmd.getName().equalsIgnoreCase("discord")) {
            if (args.length == 1) {
                return Arrays.asList("link");
            }
            return Collections.emptyList();
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

    private void sendDebugPlayerInfo(Player viewer, String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            viewer.sendMessage(ChatColor.RED + "Player not found.");
            return;
        }

        UUID uuid = target.getUniqueId();
        String rank = getPlayerRank(uuid);
        String group = getPlayerGroup(uuid);
        String displayRank = rank != null ? rank : group;
        String prefix = displayRank != null ? resolveDisplayPrefix(displayRank) : "";
        String discordLink = getDiscordLink(uuid);

        viewer.sendMessage(ChatColor.GOLD + "--- Debug Player: " + target.getName() + " ---");
        viewer.sendMessage(ChatColor.AQUA + "UUID: " + ChatColor.WHITE + uuid);
        viewer.sendMessage(ChatColor.AQUA + "Online: " + ChatColor.WHITE + target.isOnline());
        viewer.sendMessage(ChatColor.AQUA + "Stored Rank: " + ChatColor.WHITE + (rank != null ? rank : "<none>"));
        viewer.sendMessage(ChatColor.AQUA + "Stored Group: " + ChatColor.WHITE + (group != null ? group : "<none>"));
        viewer.sendMessage(ChatColor.AQUA + "Resolved Prefix: " + ChatColor.WHITE + (prefix == null || prefix.isEmpty() ? "<none>" : prefix));
        viewer.sendMessage(ChatColor.AQUA + "Discord Link: " + ChatColor.WHITE + ((discordLink == null || discordLink.isEmpty()) ? "<none>" : discordLink));

        if (target.isOnline()) {
            Player online = target.getPlayer();
            boolean invisible = isInvisible(online);
            Scoreboard activeBoard = online.getScoreboard();
            Team activeTeam = activeBoard != null ? activeBoard.getEntryTeam(online.getName()) : null;
            viewer.sendMessage(ChatColor.AQUA + "Invisible Mode: " + ChatColor.WHITE + invisible);
            viewer.sendMessage(ChatColor.AQUA + "Permission Attachment: " + ChatColor.WHITE + permissionAttachments.containsKey(uuid));
            viewer.sendMessage(ChatColor.AQUA + "Tab Name: " + ChatColor.WHITE + online.getPlayerListName());
            viewer.sendMessage(ChatColor.AQUA + "Display Name: " + ChatColor.WHITE + online.getDisplayName());
            viewer.sendMessage(ChatColor.AQUA + "Active Team: " + ChatColor.WHITE + (activeTeam != null ? activeTeam.getName() : "<none>"));
            viewer.sendMessage(ChatColor.AQUA + "Team Prefix: " + ChatColor.WHITE + (activeTeam != null ? String.valueOf(activeTeam.getPrefix()) : "<none>"));
            viewer.sendMessage(ChatColor.AQUA + "Discord Check Passes: " + ChatColor.WHITE + !isDiscordLinkRequiredAndNotLinked(online));
            viewer.sendMessage(ChatColor.AQUA + "Admin Bypass: " + ChatColor.WHITE + (online.isOp() || online.hasPermission("dmt.admin")));
        } else {
            boolean linkRequired = getConfig().getBoolean("discord.link_required", false);
            boolean linked = discordLink != null && !discordLink.isEmpty();
            viewer.sendMessage(ChatColor.AQUA + "Invisible Mode: " + ChatColor.WHITE + "<offline>");
            viewer.sendMessage(ChatColor.AQUA + "Permission Attachment: " + ChatColor.WHITE + "<offline>");
            viewer.sendMessage(ChatColor.AQUA + "Tab Name: " + ChatColor.WHITE + "<offline>");
            viewer.sendMessage(ChatColor.AQUA + "Display Name: " + ChatColor.WHITE + "<offline>");
            viewer.sendMessage(ChatColor.AQUA + "Active Team: " + ChatColor.WHITE + "<offline>");
            viewer.sendMessage(ChatColor.AQUA + "Team Prefix: " + ChatColor.WHITE + "<offline>");
            viewer.sendMessage(ChatColor.AQUA + "Discord Check Passes: " + ChatColor.WHITE + (!linkRequired || linked));
            viewer.sendMessage(ChatColor.AQUA + "Admin Bypass: " + ChatColor.WHITE + "<offline>");
        }
    }

    private void sendDebugDiscordInfo(Player viewer) {
        String webhook = dataConfig.getString("discord.webhook", "");
        String webhookBan = dataConfig.getString("discord.webhook_ban", "");
        String webhookWarn = dataConfig.getString("discord.webhook_warn", "");
        String webhookReport = dataConfig.getString("discord.webhook_report", "");

        viewer.sendMessage(ChatColor.GOLD + "--- Debug Discord ---");
        viewer.sendMessage(ChatColor.AQUA + "Link Required: " + ChatColor.WHITE + getConfig().getBoolean("discord.link_required", false));
        viewer.sendMessage(ChatColor.AQUA + "Link Message: " + ChatColor.WHITE + getConfig().getString("discord.link_message", "<none>"));
        viewer.sendMessage(ChatColor.AQUA + "Primary Webhook: " + ChatColor.WHITE + maskWebhook(webhook));
        viewer.sendMessage(ChatColor.AQUA + "Ban Webhook: " + ChatColor.WHITE + maskWebhook(webhookBan));
        viewer.sendMessage(ChatColor.AQUA + "Warn Webhook: " + ChatColor.WHITE + maskWebhook(webhookWarn));
        viewer.sendMessage(ChatColor.AQUA + "Report Webhook: " + ChatColor.WHITE + maskWebhook(webhookReport));
        viewer.sendMessage(ChatColor.AQUA + "Events Enabled: " + ChatColor.WHITE
            + "joins=" + dataConfig.getBoolean("discord.joins", false)
            + ", leaves=" + dataConfig.getBoolean("discord.leaves", false)
            + ", bans=" + dataConfig.getBoolean("discord.bans", false)
            + ", warns=" + dataConfig.getBoolean("discord.warns", false)
            + ", reports=" + dataConfig.getBoolean("discord.reports", false));
        viewer.sendMessage(ChatColor.AQUA + "Webhook Counters: " + ChatColor.WHITE
            + "sent=" + dataConfig.getInt("discord.webhooks_sent", 0)
            + ", failed=" + dataConfig.getInt("discord.webhooks_failed", 0));
    }

    private String maskWebhook(String webhook) {
        if (webhook == null || webhook.isEmpty()) return "<none>";
        int slash = webhook.lastIndexOf('/');
        if (slash < 0 || slash == webhook.length() - 1) return "<configured>";
        String tail = webhook.substring(slash + 1);
        if (tail.length() <= 6) return "..." + tail;
        return "..." + tail.substring(tail.length() - 6);
    }

    private void sendDebugHealthInfo(Player viewer) {
        viewer.sendMessage(ChatColor.GOLD + "--- Debug Health ---");
        viewer.sendMessage(ChatColor.AQUA + "Config Files: " + ChatColor.WHITE
            + "config=" + healthStatus(getConfig() != null)
            + ", data=" + healthStatus(dataFile != null && dataFile.exists() && dataConfig != null)
            + ", players=" + healthStatus(playersFile != null && playersFile.exists() && playersConfig != null)
            + ", economy=" + healthStatus(economyFile != null && economyFile.exists() && economyConfig != null)
            + ", tickets=" + healthStatus(ticketFile != null && ticketFile.exists() && ticketConfig != null)
            + ", ranks=" + healthStatus(rankFile != null && rankFile.exists() && rankConfig != null));
        viewer.sendMessage(ChatColor.AQUA + "Core Systems: " + ChatColor.WHITE
            + "scoreboard=" + healthStatus(scoreboard != null)
            + ", punishTeam=" + healthStatus(punishTeam != null)
            + ", rankService=" + healthStatus(rankService != null)
            + ", ticketService=" + healthStatus(ticketService != null)
            + ", economyService=" + healthStatus(economyService != null)
            + ", web=" + healthStatus(webServer != null && webServer.isRunning()));
        viewer.sendMessage(ChatColor.AQUA + "Dirty Flags: " + ChatColor.WHITE
            + "data=" + dataConfigDirty
            + ", players=" + playersConfigDirty
            + ", economy=" + economyConfigDirty
            + ", tickets=" + ticketConfigDirty
            + ", ranks=" + rankConfigDirty);
        viewer.sendMessage(ChatColor.AQUA + "Commands: " + ChatColor.WHITE
            + "dmt=" + healthStatus(getCommand("dmt") != null)
            + ", discord=" + healthStatus(getCommand("discord") != null)
            + ", ticket=" + healthStatus(getCommand("ticket") != null)
            + ", balance=" + healthStatus(getCommand("balance") != null));
        viewer.sendMessage(ChatColor.AQUA + "Saved Locations: " + ChatColor.WHITE
            + "hub=" + locationStatus(getLoc("hub_location"))
            + ", spawn=" + locationStatus(getLoc("server_spawn"))
            + ", punish=" + locationStatus(getLoc("punishment_location"))
            + ", jail=" + locationStatus(getLoc("jail_location"))
            + ", factionsSpawn=" + locationStatus(getLoc("factions_world.spawn_location")));
        viewer.sendMessage(ChatColor.AQUA + "Runtime: " + ChatColor.WHITE
            + "onlinePlayers=" + Bukkit.getOnlinePlayers().size()
            + ", worlds=" + Bukkit.getWorlds().size()
            + ", hubForceSpawn=" + dataConfig.getBoolean("hub.forcespawn", false)
            + ", discordLinkRequired=" + getConfig().getBoolean("discord.link_required", false));
    }

    private String healthStatus(boolean healthy) {
        return healthy ? "OK" : "MISSING";
    }

    private String locationStatus(Location location) {
        if (location == null) return "MISSING";
        World world = location.getWorld();
        return world != null ? ("OK@" + world.getName()) : "MISSING";
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
                        List<String> notes = getPlayerNotes(tid);
                        notes.add("[" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + " - " + p.getName() + "] " + reason);
                        savePlayerNotes(tid, notes);
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
                                p.sendMessage(ChatColor.YELLOW + "Could not apply enchant (already present or invalid item).");
                            }
                        }
                    } catch (NumberFormatException ex) {
                        p.sendMessage(ChatColor.RED + "Invalid slot number. Enter a value 1-9.");
                    }
                    pendingActions.remove(p.getUniqueId());
                    break;
                case SET_WARP:
                    String rawName = reason.trim();
                    String id = rawName.toLowerCase().replace(" ", "_").replaceAll("[^a-z0-9_\\-]", "");
                    if (id.isEmpty()) {
                        p.sendMessage(ChatColor.RED + "Invalid warp name. Use letters, numbers, underscore, or dash.");
                        break;
                    }

                    int cost = dataConfig.getInt("pwarp_cost", 5);
                    if (p.getLevel() < cost) {
                        p.sendMessage(ChatColor.RED + "Need " + cost + " XP levels to create a warp.");
                        pendingActions.remove(p.getUniqueId());
                        break;
                    }

                    int ownersWarpCount = getPwarpCount(p.getUniqueId());

                    int maxWarps = getPwarpLimit(p.getUniqueId());
                    if (ownersWarpCount >= maxWarps) {
                        p.sendMessage(ChatColor.RED + "You have reached the warp limit (" + maxWarps + "). Delete an existing warp to create a new one.");
                        pendingActions.remove(p.getUniqueId());
                        break;
                    }

                    FileConfiguration pwarpCfg = getPwarpConfig();
                    if (pwarpCfg.contains("pwarps." + id)) {
                        String ownerStr = pwarpCfg.getString("pwarps." + id + ".owner");
                        if (ownerStr != null && !ownerStr.equals(p.getUniqueId().toString())) {
                            p.sendMessage(ChatColor.RED + "A warp with that name already exists!");
                            break;
                        }
                    }

                    p.setLevel(p.getLevel() - cost);

                    pwarpCfg.set("pwarps." + id + ".name", rawName);
                    pwarpCfg.set("pwarps." + id + ".owner", p.getUniqueId().toString());
                    pwarpCfg.set("pwarps." + id + ".ownerName", p.getName());
                    pwarpCfg.set("pwarps." + id + ".x", p.getLocation().getX());
                    pwarpCfg.set("pwarps." + id + ".y", p.getLocation().getY());
                    pwarpCfg.set("pwarps." + id + ".z", p.getLocation().getZ());
                    pwarpCfg.set("pwarps." + id + ".world", p.getWorld().getName());
                    pwarpCfg.set("pwarps." + id + ".visits", 0);

                    if (pwarpCfg == dataConfig) saveDataFile(); else savePlayersFile();
                    p.sendMessage(ChatColor.GREEN + "Player warp '" + rawName + "' set! Use the Player Warps menu to teleport.");
                    logAction(p.getName(), "pwarp_created", rawName);
                    pendingActions.remove(p.getUniqueId());
                    break;
                case HOLOGRAM:
                    String input = reason.trim();
                    if (ctx.hologramMode == null) {
                        if (input.equalsIgnoreCase("regular") || input.equalsIgnoreCase("r")) {
                            ctx.hologramMode = "regular";
                            ctx.expectedLines = 0;
                            ctx.currentLine = 0;
                            ctx.lines = null;
                            p.sendMessage(ChatColor.AQUA + "How many lines should the hologram have? (1-10)");
                        } else if (input.equalsIgnoreCase("teleport") || input.equalsIgnoreCase("tp")) {
                            ctx.hologramMode = "teleport";
                            ctx.currentLine = 0;
                            ctx.lines = new ArrayList<>();
                            p.sendMessage(ChatColor.AQUA + "Enter display title (e.g. Survival SMP):");
                        } else {
                            p.sendMessage(ChatColor.RED + "Please type 'regular' or 'teleport'.");
                        }
                        break;
                    }

                    if (ctx.hologramMode.equals("regular")) {
                        if (ctx.expectedLines <= 0) {
                            int lines;
                            try {
                                lines = Integer.parseInt(input);
                            } catch (NumberFormatException ex) {
                                p.sendMessage(ChatColor.RED + "Please enter a valid number of lines (1-10). (regular)");
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
                        ctx.lines.add(input);
                        ctx.currentLine++;
                        if (ctx.currentLine < ctx.expectedLines) {
                            p.sendMessage(ChatColor.AQUA + "Enter text for line " + (ctx.currentLine + 1) + " (use § for colors/formatting):");
                            break;
                        }
                        spawnHologram(p, ctx.lines);
                        p.sendMessage(ChatColor.GREEN + "Regular hologram created!");
                        pendingActions.remove(p.getUniqueId());
                        break;
                    }

                    if (ctx.hologramMode.equals("teleport") || ctx.hologramMode.equals("teleport-edit")) {
                        if (ctx.currentLine == 0) {
                            ctx.lines.clear();
                            ctx.lines.add(input);
                            ctx.currentLine = 1;
                            p.sendMessage(ChatColor.AQUA + "Enter season (1-9) or 'coming soon':");
                            break;
                        }
                        if (ctx.currentLine == 1) {
                            String seasonLine;
                            if (input.equalsIgnoreCase("coming soon")) {
                                seasonLine = "Coming Soon";
                            } else {
                                int seasonNum;
                                try {
                                    seasonNum = Integer.parseInt(input);
                                } catch (NumberFormatException ex) {
                                    p.sendMessage(ChatColor.RED + "Invalid season. Enter 1-9 or coming soon.");
                                    break;
                                }
                                if (seasonNum < 1 || seasonNum > 9) {
                                    p.sendMessage(ChatColor.RED + "Invalid season. Enter 1-9 or coming soon.");
                                    break;
                                }
                                seasonLine = "Season " + seasonNum;
                            }
                            ctx.lines.add(seasonLine);
                            ctx.currentLine = 2;
                            p.sendMessage(ChatColor.AQUA + "Enter world name to check status (online/offline):");
                            break;
                        }
                        if (ctx.currentLine == 2) {
                            String worldName = input;
                            World world = Bukkit.getWorld(worldName);
                            boolean exists = world != null;
                            boolean locked = exists && dataConfig.getBoolean("worldlocks." + worldName, false);
                            boolean online = exists && !locked;
                            String version = Bukkit.getBukkitVersion();
                            String statusText = online
                                ? ChatColor.GREEN + "Online (" + version + ")"
                                : ChatColor.RED + "Offline (" + version + ")";
                            ctx.lines.add(statusText);

                            // spawn hologram and persist
                            boolean isEdit = ctx.hologramMode.equals("teleport-edit");
                            String selectedId = isEdit && ctx.targetName != null && !ctx.targetName.isEmpty() ? ctx.targetName : generateHologramId();

                            Location targetLocation = null;
                            if (isEdit) {
                                targetLocation = getSavedTeleportHologramLocation(selectedId);
                                if (targetLocation != null) {
                                    cleanupTeleportHologramEntitiesAtLocation(targetLocation);
                                }
                            }
                            if (targetLocation == null) {
                                targetLocation = p.getLocation().add(0, 1.6, 0);
                            }

                            spawnHologram(targetLocation, ctx.lines);
                            saveTeleportHologram(selectedId, ctx.lines.get(0), ctx.lines.get(1), worldName, online, version, targetLocation);

                            p.sendMessage(ChatColor.GREEN + "Teleport hologram " + (isEdit ? "updated" : "created") + " with ID: " + selectedId + "!");
                            pendingActions.remove(p.getUniqueId());
                            if (isEdit) {
                                selectedHologram.put(p.getUniqueId(), selectedId);
                            }
                            break;
                        }
                    }

                    p.sendMessage(ChatColor.RED + "Hologram process error; please retry.");
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
                    String npcInput = reason.trim();

                    // Initial choice: shop or teleport
                    if (mode.isEmpty()) {
                        if (npcInput.equalsIgnoreCase("shop") || npcInput.equalsIgnoreCase("admin shop")) {
                            dataConfig.set(base + ".type", "shop");
                            dataConfig.set(base + ".shop_items", new ArrayList<>());
                            saveDataFile();
                            ctx.lines = new ArrayList<>();
                            p.sendMessage(ChatColor.AQUA + "Enter item to sell in the format: material x<amount> = <price> (e.g. grass_block x64 = 24)");
                            p.sendMessage(ChatColor.AQUA + "Type 'done' when finished.");
                        } else if (npcInput.equalsIgnoreCase("teleport")) {
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
                        if (npcInput.equalsIgnoreCase("done") || npcInput.equalsIgnoreCase("no")) {
                            p.sendMessage(ChatColor.GREEN + "Shop configuration saved.");
                            pendingActions.remove(p.getUniqueId());
                            break;
                        }
                        String[] parts = npcInput.split("=");
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
                        String[] parts = npcInput.split("\\s+");
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
                        WorldCreator wc = buildManagedWorldCreator(worldName, type);
                        World created = null;
                        try {
                            created = Bukkit.createWorld(wc);
                        } catch (Exception ex) {
                            p.sendMessage(ChatColor.RED + "Failed to create world: " + ex.getMessage());
                            getLogger().log(java.util.logging.Level.SEVERE, "Failed to create world", ex);
                        }
                        if (created != null) {
                            rememberManagedWorld(worldName, type);
                            initializeCreatedWorld(created, type);
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
                    if (ctx.targetName != null) addWarning(Bukkit.getOfflinePlayer(ctx.targetName).getUniqueId(), reason, p.getName());
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
                    sendWebPush("New Player Report", p.getName() + " reported " + ctx.targetName);
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

        String prefix = resolveDisplayPrefix(rank);
        if (prefix == null || prefix.isEmpty()) return;

        prefix = ChatColor.translateAlternateColorCodes('&', prefix);
        // %1$s = player name, %2$s = message
        e.setFormat(prefix + "%1$s: %2$s");
    }

    // --- ticket helpers ---
    public String getWebBindHost() {
        return getConfig().getString("web.host", "0.0.0.0").trim();
    }

    public int getWebPort() {
        return Math.max(1, getConfig().getInt("web.port", 8091));
    }

    public String getWebPublicBaseUrl() {
        String publicUrl = getConfig().getString("web.public_url", "").trim();
        if (!publicUrl.isEmpty()) {
            return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        }

        String serverIp = Bukkit.getServer().getIp();
        String host = (serverIp == null || serverIp.isBlank()) ? "localhost" : serverIp;
        return "http://" + host + ":" + getWebPort();
    }

    private String getWebPanelUrl() {
        return getWebPublicBaseUrl() + "/tickets";
    }

    private String getDocumentationUrl() {
        return getWebPublicBaseUrl() + "/docs.pdf";
    }

    public String getDiscordLink(UUID uuid) {
        return dataConfig.getString("discord_links." + uuid.toString());
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

    // --- coin economy helpers (delegated through EconomyService) ---
    public long getCoins(UUID uuid) {
        return networkTokenService != null ? networkTokenService.getTokens(uuid) : getLocalCoins(uuid);
    }

    public long getPunishmentExpiry(UUID uuid) {
        NetworkPunishment punishment = getPunishment(uuid);
        return punishment != null ? Math.max(0L, punishment.expiresAt()) : 0L;
    }

    public NetworkPunishment getPunishment(UUID uuid) {
        long now = System.currentTimeMillis();
        CachedPunishmentState cachedState = punishmentCache.get(uuid);
        if (cachedState != null && cachedState.refreshUntil() > now) {
            if (cachedState.punishment() != null && cachedState.punishment().expiresAt() > now) {
                activePunishmentExpiries.put(uuid, cachedState.punishment().expiresAt());
            } else {
                activePunishmentExpiries.remove(uuid);
            }
            return cachedState.punishment();
        }

        NetworkPunishment punishment = networkModerationService != null
            ? networkModerationService.getPunishment(uuid)
            : getLocalPunishment(uuid);

        if (punishment == null || punishment.expiresAt() <= now) {
            punishmentCache.put(uuid, new CachedPunishmentState(null, now + PUNISHMENT_CACHE_TTL_MS));
            activePunishmentExpiries.remove(uuid);
            return null;
        }

        long refreshUntil = Math.min(punishment.expiresAt(), now + PUNISHMENT_CACHE_TTL_MS);
        punishmentCache.put(uuid, new CachedPunishmentState(punishment, refreshUntil));
        activePunishmentExpiries.put(uuid, punishment.expiresAt());
        return punishment;
    }

    public Map<UUID, Long> getActivePunishments() {
        Map<UUID, Long> punishments = networkModerationService != null ? networkModerationService.getPunishmentExpiries() : getAllLocalPunishments();
        Map<UUID, Long> activePunishments = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : punishments.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > now) {
                activePunishments.put(entry.getKey(), entry.getValue());
            }
        }
        return activePunishments;
    }

    public Map<UUID, NetworkPunishment> getActivePunishmentRecords() {
        long now = System.currentTimeMillis();
        CachedPunishmentRecordsSnapshot cachedSnapshot = activePunishmentRecordsSnapshotCache;
        if (cachedSnapshot != null && cachedSnapshot.expiresAt() > now) {
            return new HashMap<>(cachedSnapshot.punishments());
        }

        Map<UUID, NetworkPunishment> punishments = networkModerationService != null ? networkModerationService.getPunishments() : getAllLocalPunishmentRecords();
        Map<UUID, NetworkPunishment> activePunishments = new HashMap<>();
        for (Map.Entry<UUID, NetworkPunishment> entry : punishments.entrySet()) {
            NetworkPunishment punishment = entry.getValue();
            if (punishment != null && punishment.expiresAt() > now) {
                activePunishments.put(entry.getKey(), punishment);
            }
        }
        activePunishmentRecordsSnapshotCache = new CachedPunishmentRecordsSnapshot(new HashMap<>(activePunishments), now + MODERATION_AGGREGATE_CACHE_TTL_MS);
        return activePunishments;
    }

    public List<String> getPlayerNotes(UUID uuid) {
        return new ArrayList<>(networkModerationService != null ? networkModerationService.getNotes(uuid) : getLocalNotes(uuid));
    }

    public Map<UUID, List<String>> getAllPlayerNotes() {
        return networkModerationService != null ? networkModerationService.getAllNotes() : getAllLocalNotes();
    }

    public void savePlayerNotes(UUID uuid, List<String> notes) {
        if (networkModerationService != null) {
            networkModerationService.saveNotes(uuid, notes == null ? Collections.emptyList() : new ArrayList<>(notes));
            return;
        }
        setLocalNotes(uuid, notes);
    }

    public void setCoins(UUID uuid, long amount) {
        if (networkTokenService != null) {
            networkTokenService.setTokens(uuid, amount);
            return;
        }
        setLocalCoins(uuid, amount);
    }

    public void addCoins(UUID uuid, long delta) {
        if (networkTokenService != null) {
            networkTokenService.addTokens(uuid, delta);
            return;
        }
        addLocalCoins(uuid, delta);
    }

    public long getLocalCoins(UUID uuid) {
        return economyService != null ? economyService.getCoins(uuid) : 0;
    }

    public long getLocalPunishmentExpiry(UUID uuid) {
        return playersConfig.getLong("punishments." + uuid, 0L);
    }

    public NetworkPunishment getLocalPunishment(UUID uuid) {
        long expiry = getLocalPunishmentExpiry(uuid);
        if (expiry <= 0L) {
            return null;
        }

        String basePath = "punishment_meta." + uuid;
        return new NetworkPunishment(
            uuid,
            expiry,
            playersConfig.getString(basePath + ".reason"),
            playersConfig.getString(basePath + ".actor"),
            playersConfig.getLong(basePath + ".created_at", 0L)
        );
    }

    public Map<UUID, Long> getAllLocalPunishments() {
        Map<UUID, Long> punishments = new HashMap<>();
        ConfigurationSection section = playersConfig.getConfigurationSection("punishments");
        if (section == null) {
            return punishments;
        }

        for (String uuidStr : section.getKeys(false)) {
            try {
                punishments.put(UUID.fromString(uuidStr), playersConfig.getLong("punishments." + uuidStr, 0L));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return punishments;
    }

    public Map<UUID, NetworkPunishment> getAllLocalPunishmentRecords() {
        Map<UUID, NetworkPunishment> punishments = new HashMap<>();
        for (UUID uuid : getAllLocalPunishments().keySet()) {
            NetworkPunishment punishment = getLocalPunishment(uuid);
            if (punishment != null) {
                punishments.put(uuid, punishment);
            }
        }
        return punishments;
    }

    public void setLocalPunishmentExpiry(UUID uuid, long expiryTimestamp) {
        playersConfig.set("punishments." + uuid, expiryTimestamp <= 0L ? null : expiryTimestamp);
        savePlayersFile();
    }

    public void saveLocalPunishment(NetworkPunishment punishment) {
        if (punishment == null || punishment.expiresAt() <= 0L) {
            if (punishment != null) {
                playersConfig.set("punishments." + punishment.uuid(), null);
                playersConfig.set("punishment_meta." + punishment.uuid(), null);
            }
            savePlayersFile();
            return;
        }

        String uuidKey = punishment.uuid().toString();
        String metaPath = "punishment_meta." + uuidKey;
        playersConfig.set("punishments." + uuidKey, punishment.expiresAt());
        playersConfig.set(metaPath + ".reason", punishment.reason() == null || punishment.reason().isBlank() ? null : punishment.reason().trim());
        playersConfig.set(metaPath + ".actor", punishment.actor() == null || punishment.actor().isBlank() ? null : punishment.actor().trim());
        playersConfig.set(metaPath + ".created_at", Math.max(0L, punishment.createdAt()));
        savePlayersFile();
    }

    public List<String> getLocalNotes(UUID uuid) {
        return new ArrayList<>(getNotesConfig().getStringList("notes." + uuid));
    }

    public Map<UUID, List<String>> getAllLocalNotes() {
        Map<UUID, List<String>> notesByPlayer = new HashMap<>();
        FileConfiguration notesConfig = getNotesConfig();
        ConfigurationSection section = notesConfig.getConfigurationSection("notes");
        if (section == null) {
            return notesByPlayer;
        }

        for (String uuidStr : section.getKeys(false)) {
            try {
                notesByPlayer.put(UUID.fromString(uuidStr), new ArrayList<>(notesConfig.getStringList("notes." + uuidStr)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return notesByPlayer;
    }

    public void setLocalNotes(UUID uuid, List<String> notes) {
        FileConfiguration notesConfig = getNotesConfig();
        notesConfig.set("notes." + uuid, notes == null || notes.isEmpty() ? null : new ArrayList<>(notes));
        if (notesConfig == dataConfig) {
            saveDataFile();
        } else {
            savePlayersFile();
        }
    }

    public void setLocalCoins(UUID uuid, long amount) {
        if (economyService != null) economyService.setCoins(uuid, amount);
    }

    public void addLocalCoins(UUID uuid, long delta) {
        if (economyService != null) economyService.addCoins(uuid, delta);
    }

    public boolean isDrowsyCoin(ItemStack item) {
        return economyService != null && economyService.isDrowsyCoin(item);
    }

    public ItemStack makeDrowsyCoinStack(int amount) {
        return economyService != null ? economyService.makeDrowsyCoinStack(amount) : null;
    }

    public long countDrowsyCoins(Player p) {
        return economyService != null ? economyService.countDrowsyCoins(p) : 0;
    }

    public long removeDrowsyCoins(Player p, long amount) {
        return economyService != null ? economyService.removeDrowsyCoins(p, amount) : 0;
    }

    public void giveDrowsyCoins(Player p, long amount) {
        if (economyService != null) economyService.giveDrowsyCoins(p, amount);
    }

    public void createTicket(Player p, String text) {
        if (ticketService != null) ticketService.createTicket(p, text);
    }

    public void createAppeal(Player p, String text) {
        if (ticketService != null) ticketService.createAppeal(p, text);
    }

    public Map<String, Object> getTicketData(int id) {
        return ticketService != null ? ticketService.getTicketData(id) : new HashMap<>();
    }

    public void addTicketResponse(int id, String admin, String message) {
        if (ticketService != null) ticketService.addTicketResponse(id, admin, message);
    }

    public void updateTicketField(int id, String field, String value) {
        if (ticketService != null) ticketService.updateTicketField(id, field, value);
    }

    public void resolveTicket(int id, String reason) {
        if (ticketService != null) ticketService.resolveTicket(id, reason);
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
        gui.setItem(10, playerMenu);

        // Helper Menu button
        if (isHelper(p)) {
            ItemStack helperMenu = new ItemStack(Material.BLUE_STAINED_GLASS);
            ItemMeta hmMeta = helperMenu.getItemMeta();
            hmMeta.setDisplayName(ChatColor.GREEN + "Helper Menu");
            hmMeta.setLore(Arrays.asList(ChatColor.GRAY + "Staff tools for Helpers"));
            helperMenu.setItemMeta(hmMeta);
            gui.setItem(12, helperMenu);
        }

        // Moderator Menu button
        if (isModerator(p)) {
            ItemStack modMenu = new ItemStack(Material.GOLD_BLOCK);
            ItemMeta mmMeta = modMenu.getItemMeta();
            mmMeta.setDisplayName(ChatColor.GOLD + "Moderator Menu");
            mmMeta.setLore(Arrays.asList(ChatColor.GRAY + "Staff tools for Moderators"));
            modMenu.setItemMeta(mmMeta);
            gui.setItem(14, modMenu);
        }

        // Admin Menu button
        if (p.isOp() || p.hasPermission("dmt.admin")) {
            ItemStack adminMenu = new ItemStack(Material.REDSTONE_BLOCK);
            ItemMeta amMeta = adminMenu.getItemMeta();
            amMeta.setDisplayName(ChatColor.RED + "Admin Menu");
            amMeta.setLore(Arrays.asList(ChatColor.GRAY + "Management tools"));
            adminMenu.setItemMeta(amMeta);
            gui.setItem(16, adminMenu);
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
        
        // TP Spawn
        ItemStack tpSpawn = new ItemStack(Material.COMPASS);
        ItemMeta tsMeta = tpSpawn.getItemMeta();
        tsMeta.setDisplayName(ChatColor.YELLOW + "TP Spawn");
        tsMeta.setLore(Arrays.asList(ChatColor.GRAY + "Teleport to server spawn"));
        tpSpawn.setItemMeta(tsMeta);
        gui.setItem(getNextGridSlot(), tpSpawn);

        if (areFactionsFeaturesVisible()) {
            // TP Factions
            ItemStack tpFactions = new ItemStack(Material.NETHER_STAR);
            ItemMeta tfMeta = tpFactions.getItemMeta();
            tfMeta.setDisplayName(ChatColor.RED + "TP Factions");
            String factionsWorldName = getFactionsWorldName();
            String displayWorldName = factionsWorldName.isEmpty() ? "Not configured" : factionsWorldName;
            String safeZoneStatus = getConfig().getBoolean("factions_world.safe_zone_enabled", true)
                ? "Safe Zone: " + (int) Math.round(getFactionsSafeZoneRadius()) + " blocks"
                : "Safe Zone: Disabled";
            String claimStatus = getConfig().getBoolean("factions_world.claims_enabled", true)
                ? "Claims: Enabled"
                : "Claims: Disabled";
            tfMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Teleport to the factions world",
                ChatColor.DARK_GRAY + "World: " + ChatColor.GRAY + displayWorldName,
                ChatColor.DARK_GRAY + safeZoneStatus,
                ChatColor.DARK_GRAY + claimStatus
            ));
            tpFactions.setItemMeta(tfMeta);
            gui.setItem(getNextGridSlot(), tpFactions);
        }
        
        // Set Warp
        ItemStack setWarp = new ItemStack(Material.OAK_SIGN);
        ItemMeta swMeta = setWarp.getItemMeta();
        swMeta.setDisplayName(ChatColor.BLUE + "Set Warp");
        setWarp.setItemMeta(swMeta);
        gui.setItem(getNextGridSlot(), setWarp);
        
        // View Player Warps
        ItemStack viewWarps = new ItemStack(Material.FILLED_MAP);
        ItemMeta vwMeta = viewWarps.getItemMeta();
        vwMeta.setDisplayName(ChatColor.BLUE + "View Player Warps");
        viewWarps.setItemMeta(vwMeta);
        gui.setItem(getNextGridSlot(), viewWarps);
        
        // Tickets (open ticket menu)
        ItemStack tickets = new ItemStack(Material.PAPER);
        ItemMeta tiMeta = tickets.getItemMeta();
        tiMeta.setDisplayName(ChatColor.GOLD + "Tickets");
        tiMeta.setLore(Arrays.asList(ChatColor.GRAY + "Open ticket menu"));
        tickets.setItemMeta(tiMeta);
        gui.setItem(getNextGridSlot(), tickets);
        
        if (areClaimFeaturesAvailableInWorld(p.getWorld())) {
            // Chunk Claims
            ItemStack claims = new ItemStack(Material.CRYING_OBSIDIAN);
            ItemMeta cMeta = claims.getItemMeta();
            cMeta.setDisplayName(ChatColor.BLUE + "Chunk Claims");
            cMeta.setLore(Arrays.asList(ChatColor.GRAY + "Manage your claims"));
            claims.setItemMeta(cMeta);
            gui.setItem(getNextGridSlot(), claims);
        }
        
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
        FileConfiguration ticketCfg = getTicketConfig();
        if (ticketCfg.contains("tickets")) {
            for (String key : ticketCfg.getConfigurationSection("tickets").getKeys(false)) {
                if (key.equals("next_id")) continue;
                String player = ticketCfg.getString("tickets." + key + ".player", "");
                if (!player.equalsIgnoreCase(p.getName())) continue;
                String priority = ticketCfg.getString("tickets." + key + ".priority", "medium");
                Material mat;
                switch (priority) {
                    case "critical": mat = Material.REDSTONE_BLOCK; break;
                    case "high": mat = Material.ORANGE_WOOL; break;
                    case "low": mat = Material.LIME_WOOL; break;
                    default: mat = Material.YELLOW_WOOL; break;
                }
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.YELLOW + "Status: " + ticketCfg.getString("tickets." + key + ".status", "open"));
                lore.add(ChatColor.GRAY + ticketCfg.getString("tickets." + key + ".message", ""));
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
        FileConfiguration ticketCfg = getTicketConfig();
        if (ticketCfg.contains("appeals")) {
            for (String key : ticketCfg.getConfigurationSection("appeals").getKeys(false)) {
                if (key.equals("next_id")) continue;
                String player = ticketCfg.getString("appeals." + key + ".player", "");
                if (!player.equalsIgnoreCase(p.getName())) continue;
                String priority = ticketCfg.getString("appeals." + key + ".priority", "medium");
                Material mat;
                switch (priority) {
                    case "critical": mat = Material.REDSTONE_BLOCK; break;
                    case "high": mat = Material.ORANGE_WOOL; break;
                    case "low": mat = Material.LIME_WOOL; break;
                    default: mat = Material.YELLOW_WOOL; break;
                }
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.YELLOW + "Status: " + ticketCfg.getString("appeals." + key + ".status", "open"));
                lore.add(ChatColor.GRAY + ticketCfg.getString("appeals." + key + ".message", ""));
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
        FileConfiguration ticketCfg = getTicketConfig();
        if (ticketCfg.contains("tickets")) {
            for (String key : ticketCfg.getConfigurationSection("tickets").getKeys(false)) {
                if (key.equals("next_id")) continue;
                String status = ticketCfg.getString("tickets." + key + ".status", "open");
                if (!"open".equals(status) && !"in_progress".equals(status)) continue;
                String priority = ticketCfg.getString("tickets." + key + ".priority", "medium");
                String category = ticketCfg.getString("tickets." + key + ".category", "other");
                String player = ticketCfg.getString("tickets." + key + ".player", "???");
                String message = ticketCfg.getString("tickets." + key + ".message", "");
                String assignee = ticketCfg.getString("tickets." + key + ".assignee", "");
                String time = ticketCfg.getString("tickets." + key + ".timestamp", "");
                int responseCount = ticketCfg.getStringList("tickets." + key + ".responses").size();

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
        FileConfiguration ticketCfg = getTicketConfig();
        String base = "tickets." + ticketId;
        if (!ticketCfg.contains(base)) { p.sendMessage(ChatColor.RED + "Ticket not found."); return; }

        Inventory gui = Bukkit.createInventory(null, 54, GUI_TICKET_DETAIL + ticketId);
        String player = ticketCfg.getString(base + ".player", "???");
        String status = ticketCfg.getString(base + ".status", "open");
        String priority = ticketCfg.getString(base + ".priority", "medium");
        String category = ticketCfg.getString(base + ".category", "other");
        String message = ticketCfg.getString(base + ".message", "");
        String assignee = ticketCfg.getString(base + ".assignee", "");
        String time = ticketCfg.getString(base + ".timestamp", "");
        String resolution = ticketCfg.getString(base + ".resolution", "");
        List<String> responses = ticketCfg.getStringList(base + ".responses");

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
        if (ticketCfg.contains(base + ".world")) {
            infoLore.add("");
            infoLore.add(ChatColor.AQUA + "Location: " + ticketCfg.getString(base + ".world") + " " +
                ticketCfg.getInt(base + ".x") + ", " + ticketCfg.getInt(base + ".y") + ", " + ticketCfg.getInt(base + ".z"));
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

        boolean isAdmin = p.hasPermission("dmt.admin") || p.hasPermission("realmtool.admin");

        // Action buttons row
        gui.setItem(28, createGuiItem(Material.WRITABLE_BOOK, ChatColor.GREEN + "Add Response", Collections.singletonList(ChatColor.GRAY + "Click to type a response")));
        if (isAdmin) {
            gui.setItem(29, createGuiItem(Material.ARROW, ChatColor.AQUA + "Set In Progress"));
            gui.setItem(30, createGuiItem(Material.GOLD_INGOT, ChatColor.YELLOW + "Set Priority"));
            gui.setItem(31, createGuiItem(Material.ARMOR_STAND, ChatColor.BLUE + "Assign to Me"));
            gui.setItem(32, createGuiItem(Material.EMERALD_BLOCK, ChatColor.GREEN + "Resolve"));
        }
        gui.setItem(33, createGuiItem(Material.BARRIER, ChatColor.RED + "Close Ticket"));

        // Teleport to location (slot 34)
        if (isAdmin && dataConfig.contains(base + ".world")) {
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

        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + (isAdmin ? "Back to Ticket List" : "Back to My Tickets")));
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
        // Nether lock toggle
        slot = getNextGridSlot();
        if (slot >= 0 && slot < gui.getSize()) {
            boolean netherLocked = dataConfig.getBoolean("locks.nether", false);
            item = createGuiItem(Material.CRYING_OBSIDIAN, ChatColor.DARK_PURPLE + "Nether Access",
                Collections.singletonList(ChatColor.GRAY + (netherLocked ? "Locked" : "Unlocked")));
            if (item != null && item.getItemMeta() != null) gui.setItem(slot, item);
        }
        // End lock toggle
        slot = getNextGridSlot();
        if (slot >= 0 && slot < gui.getSize()) {
            boolean endLocked = dataConfig.getBoolean("locks.end", false);
            item = createGuiItem(Material.END_STONE, ChatColor.DARK_PURPLE + "The End Access",
                Collections.singletonList(ChatColor.GRAY + (endLocked ? "Locked" : "Unlocked")));
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

    private WorldCreator buildManagedWorldCreator(String worldName, String type) {
        WorldCreator creator = new WorldCreator(worldName);
        if ("flat".equalsIgnoreCase(type)) {
            creator.type(WorldType.FLAT);
        } else if ("void".equalsIgnoreCase(type)) {
            creator.generator(new org.bukkit.generator.ChunkGenerator() {
                private static final int BASE_Y = 64;
                private static final int BASE_RADIUS = 40;
                private static final int CLOUD_Y = 120;
                private static final int CLOUD_RADIUS = 40;

                @Override
                public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, org.bukkit.generator.ChunkGenerator.BiomeGrid biome) {
                    ChunkData data = createChunkData(world);
                    int baseStartX = chunkX * 16;
                    int baseStartZ = chunkZ * 16;

                    Random cloudRand = new Random(world.getSeed() ^ ((long) chunkX * 341873128712L) ^ ((long) chunkZ * 132897987541L));
                    int puffCount = 3;
                    int[][] puffs = new int[puffCount][3];
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

                            if (Math.abs(wx) <= BASE_RADIUS && Math.abs(wz) <= BASE_RADIUS) {
                                boolean onBorder = Math.abs(wx) == BASE_RADIUS || Math.abs(wz) == BASE_RADIUS;
                                data.setBlock(dx, BASE_Y, dz, onBorder ? Material.WHITE_WOOL : Material.GRASS_BLOCK);
                            }

                            double bestDist = Double.MAX_VALUE;
                            int bestRadius = 0;
                            for (int[] puff : puffs) {
                                int cx = puff[0];
                                int cz = puff[1];
                                int radius = puff[2];
                                double distance = Math.sqrt((double) (wx - cx) * (wx - cx) + (double) (wz - cz) * (wz - cz));
                                if (distance < bestDist && distance <= radius) {
                                    bestDist = distance;
                                    bestRadius = radius;
                                }
                            }

                            if (bestDist <= bestRadius) {
                                Material cloudMaterial = bestDist <= (bestRadius - 2) ? Material.WHITE_WOOL : Material.WHITE_STAINED_GLASS;
                                data.setBlock(dx, CLOUD_Y, dz, cloudMaterial);
                                data.setBlock(dx, CLOUD_Y + 1, dz, Material.WHITE_WOOL);
                            }
                        }
                    }

                    return data;
                }
            });
            creator.generateStructures(false);
        }

        return creator;
    }

    private void rememberManagedWorld(String worldName, String type) {
        if (worldName == null || worldName.isBlank()) {
            return;
        }

        String basePath = MANAGED_WORLDS_PATH + "." + worldName;
        dataConfig.set(basePath + ".type", type == null || type.isBlank() ? "normal" : type.toLowerCase(Locale.ROOT));
        saveDataFile();
    }

    private void forgetManagedWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return;
        }

        dataConfig.set(MANAGED_WORLDS_PATH + "." + worldName, null);
        saveDataFile();
    }

    private void loadManagedWorldsFromConfig() {
        ConfigurationSection managedWorlds = dataConfig.getConfigurationSection(MANAGED_WORLDS_PATH);
        if (managedWorlds == null) {
            return;
        }

        for (String worldName : managedWorlds.getKeys(false)) {
            if (Bukkit.getWorld(worldName) != null) {
                continue;
            }

            String type = managedWorlds.getString(worldName + ".type", "normal");
            try {
                World world = Bukkit.createWorld(buildManagedWorldCreator(worldName, type));
                if (world != null) {
                    initializeCreatedWorld(world, type);
                }
            } catch (Exception ex) {
                getLogger().warning("Failed to load managed world '" + worldName + "': " + ex.getMessage());
            }
        }
    }

    private void saveLoadedWorlds() {
        for (World world : Bukkit.getWorlds()) {
            try {
                world.save();
            } catch (Exception ex) {
                getLogger().warning("Failed to save world '" + world.getName() + "' during disable: " + ex.getMessage());
            }
        }
    }

    private void persistCreatedWorld(World world) {
        if (world == null) {
            return;
        }

        try {
            world.save();
        } catch (Exception ex) {
            getLogger().warning("Failed to immediately save world '" + world.getName() + "': " + ex.getMessage());
        }
    }

    private void initializeCreatedWorld(World world, String type) {
        if (world == null) {
            return;
        }

        world.setAutoSave(true);

        if ("void".equalsIgnoreCase(type)) {
            world.setSpawnLocation(0, 65, 0);
        } else {
            Location safeSpawn = resolveSafeWorldEntryLocation(world);
            if (safeSpawn != null) {
                world.setSpawnLocation(safeSpawn.getBlockX(), safeSpawn.getBlockY(), safeSpawn.getBlockZ());
            }
        }

        persistCreatedWorld(world);
    }

    private Location resolveSafeWorldEntryLocation(World world) {
        if (world == null) {
            return null;
        }

        Location spawn = world.getSpawnLocation();
        Location exactSpawn = resolveSafeExactWorldEntryLocation(spawn);
        if (exactSpawn != null) {
            return exactSpawn;
        }

        Location safeSpawn = resolveSafeSurfaceLocation(world, spawn != null ? spawn.getBlockX() : 0, spawn != null ? spawn.getBlockZ() : 0, spawn);
        if (safeSpawn != null) {
            return safeSpawn;
        }

        return resolveSafeSurfaceLocation(world, 0, 0, spawn);
    }

    private Location resolveSafeExactWorldEntryLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        World world = location.getWorld();
        int x = location.getBlockX();
        int z = location.getBlockZ();

        try {
            world.getChunkAt(x >> 4, z >> 4).load();
        } catch (Exception ignored) {
        }

        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        if (!feet.isPassable() || !head.isPassable()) {
            return null;
        }
        if (ground.isPassable() || !ground.getType().isSolid()) {
            return null;
        }

        return location.clone();
    }

    private Location resolveSafeSurfaceLocation(World world, int x, int z, Location fallback) {
        try {
            world.getChunkAt(x >> 4, z >> 4).load();
        } catch (Exception ignored) {
        }

        int highestY = world.getHighestBlockYAt(x, z);
        if (highestY > world.getMinHeight()) {
            float yaw = fallback != null ? fallback.getYaw() : 0.0f;
            float pitch = fallback != null ? fallback.getPitch() : 0.0f;
            return new Location(world, x + 0.5, highestY + 1.0, z + 0.5, yaw, pitch);
        }

        return null;
    }

    private void loadPersistedWorlds() {
        loadManagedWorldsFromConfig();

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
        List<String> notesList = getPlayerNotes(uuid);
        for (String note : notesList) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Click for options");
            ItemStack noteItem = createGuiItem(Material.PAPER, ChatColor.YELLOW + note, lore);
            int slot = getNextGridSlot();
            if (slot != -1) gui.setItem(slot, noteItem);
        }
        gui.setItem(45, createGuiItem(Material.WRITABLE_BOOK, ChatColor.GREEN + "Add Note"));
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private void openNoteManagementMenu(Player p, String targetName, int noteIndex) {
        UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        List<String> notesList = getPlayerNotes(uuid);
        
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
        List<UUID> punishedPlayers = new ArrayList<>(getActivePunishments().keySet());
        punishedPlayers.sort(Comparator.comparing(this::getStaffDisplayNameForUuid, String.CASE_INSENSITIVE_ORDER));

        for (UUID uuid : punishedPlayers) {
            if (isPunished(uuid)) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(offlinePlayer);
                meta.setDisplayName(ChatColor.RED + getStaffDisplayNameForUuid(uuid));
                head.setItemMeta(meta);
                int slot = getNextGridSlot();
                if (slot != -1) gui.setItem(slot, head);
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        p.openInventory(gui);
    }

    private String getStaffDisplayNameForUuid(UUID uuid) {
        if (networkProfileService != null) {
            try {
                String lastSeenName = networkProfileService.getProfile(uuid).lastSeenName();
                if (lastSeenName != null && !lastSeenName.isBlank()) {
                    return lastSeenName;
                }
            } catch (Exception ignored) {
            }
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        String fallbackName = offlinePlayer.getName();
        return fallbackName != null && !fallbackName.isBlank() ? fallbackName : uuid.toString();
    }

    private FileConfiguration getWarpConfig() {
        return playersConfig.contains("warps") ? playersConfig : dataConfig;
    }

    private void openWarpListMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.BLUE + "Warps");
        
        // Fill all slots with gray glass
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        
        int warpSlot = 1;
        FileConfiguration warpCfg = getWarpConfig();
        if (warpCfg.contains("warps")) {
            for (String warpName : warpCfg.getConfigurationSection("warps").getKeys(false)) {
                String ownerStr = warpCfg.getString("warps." + warpName + ".owner");
                
                if (ownerStr != null && !ownerStr.equals(p.getUniqueId().toString())) {
                    continue; // Skip warps owned by someone else
                } else if (ownerStr == null && !p.hasPermission("dmt.admin")) {
                    continue; // Hide legacy unowned warps from regular players
                }
                
                if (warpSlot >= 26) break; // Don't go past slot 25
                
                ItemStack warp = new ItemStack(Material.FILLED_MAP);
                ItemMeta wMeta = warp.getItemMeta();
                wMeta.setDisplayName(ChatColor.BLUE + warpName);
                double x = warpCfg.getDouble("warps." + warpName + ".x");
                double y = warpCfg.getDouble("warps." + warpName + ".y");
                double z = warpCfg.getDouble("warps." + warpName + ".z");
                String worldName = warpCfg.getString("warps." + warpName + ".world");
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
        if (!areClaimFeaturesAvailableInWorld(p.getWorld())) {
            p.sendMessage(ChatColor.RED + "Claims are not enabled in this world.");
            return;
        }
        Inventory gui = Bukkit.createInventory(null, 27, GUI_CLAIMS);
        for (int i = 0; i < 27; i++) gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        
        UUID uuid = p.getUniqueId();
        String worldName = p.getWorld().getName();
        int chunkLimit = getChunkLimit(uuid);
        int claimedChunks = getClaimedChunks(uuid, worldName).size();
        
        ItemStack chunks = new ItemStack(Material.ARMOR_STAND);
        ItemMeta chkMeta = chunks.getItemMeta();
        chkMeta.setDisplayName(ChatColor.AQUA + "Chunks Available");
        chkMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "World: " + ChatColor.WHITE + worldName,
            ChatColor.YELLOW + "Limit: " + chunkLimit,
            ChatColor.YELLOW + "Claimed: " + claimedChunks
        ));
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
        trMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Add trusted player",
            ChatColor.DARK_GRAY + "Scope: " + ChatColor.GRAY + worldName
        ));
        trust.setItemMeta(trMeta);
        gui.setItem(11, trust);
        
        ItemStack untrust = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta untrMeta = untrust.getItemMeta();
        untrMeta.setDisplayName(ChatColor.RED + "Remove Trusted");
        untrMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Remove trusted player",
            ChatColor.DARK_GRAY + "Scope: " + ChatColor.GRAY + worldName
        ));
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
        UUID owner = getChunkOwner(chunkKey);
        boolean chunkOwnedByOthers = (owner != null && !owner.equals(p.getUniqueId()));
        boolean chunkOwnedByPlayer = (owner != null && owner.equals(p.getUniqueId()));

        String message;
        boolean confirmAllowed = true;

        if (action.equals("claim")) {
            if (!isClaimingAllowedInWorld(p.getWorld())) {
                message = "Claims can only be created in the configured claim worlds.";
                confirmAllowed = false;
            } else if (isInsideFactionsSafeZone(p.getLocation())) {
                message = "You cannot claim land inside the factions spawn safe zone.";
                confirmAllowed = false;
            } else {
                UUID nearbyOwner = getNearbyClaimOwner(chunkKey, CLAIM_PROXIMITY_RADIUS);
                boolean nearbyOther = nearbyOwner != null && !nearbyOwner.equals(p.getUniqueId());

                if (chunkOwnedByOthers) {
                    message = "This chunk is already claimed by another player.";
                    confirmAllowed = false;
                } else if (chunkOwnedByPlayer) {
                    message = "You already own this chunk.";
                    confirmAllowed = false;
                } else if (nearbyOther) {
                    message = "This chunk is too close to another claim (in a " + CLAIM_PROXIMITY_RADIUS + "-chunk buffer).";
                    confirmAllowed = false;
                } else {
                    message = "Do you want to claim this chunk?";
                }
            }
        } else {
            if (chunkOwnedByPlayer) {
                message = "Do you want to unclaim this chunk?";
            } else {
                message = "You cannot unclaim a chunk you do not own.";
                confirmAllowed = false;
            }
        }

        ItemStack confirm = confirmAllowed ? new ItemStack(Material.EMERALD_BLOCK) : new ItemStack(Material.BARRIER);
        ItemMeta confMeta = confirm.getItemMeta();
        confMeta.setDisplayName(confirmAllowed ? ChatColor.GREEN + "Confirm" : ChatColor.RED + "Not Allowed");
        confMeta.setLore(Arrays.asList(ChatColor.GRAY + message));
        confirm.setItemMeta(confMeta);
        gui.setItem(11, confirm);

        ItemStack cancel = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta canMeta = cancel.getItemMeta();
        canMeta.setDisplayName(ChatColor.RED + "Cancel");
        cancel.setItemMeta(canMeta);
        gui.setItem(15, cancel);
        
        if (confirmAllowed) {
            pendingClaimAction.put(p.getUniqueId(), action + ":" + chunkKey);
        } else {
            pendingClaimAction.remove(p.getUniqueId());
        }
        p.openInventory(gui);
    }

    private void openTrustPlayerMenu(Player p) {
        openTrustPlayerMenu(p, 0);
    }

    private void openTrustPlayerMenu(Player p, int page) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_TRUST_PLAYER);
        for (int i = 0; i < 27; i++) gui.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        String worldName = p.getWorld().getName();

        ItemStack scope = new ItemStack(Material.MAP);
        ItemMeta scopeMeta = scope.getItemMeta();
        scopeMeta.setDisplayName(ChatColor.AQUA + "Trust Scope");
        scopeMeta.setLore(Arrays.asList(ChatColor.GRAY + "World: " + ChatColor.WHITE + worldName));
        scope.setItemMeta(scopeMeta);
        gui.setItem(4, scope);
        
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
        String worldName = p.getWorld().getName();

        ItemStack scope = new ItemStack(Material.MAP);
        ItemMeta scopeMeta = scope.getItemMeta();
        scopeMeta.setDisplayName(ChatColor.AQUA + "Trust Scope");
        scopeMeta.setLore(Arrays.asList(ChatColor.GRAY + "World: " + ChatColor.WHITE + worldName));
        scope.setItemMeta(scopeMeta);
        gui.setItem(4, scope);
        
        List<String> trusted = getTrustedList(p.getUniqueId(), worldName);
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
        String root = "homes." + uuid;
        FileConfiguration cfg = playersConfig.contains(root) ? playersConfig : dataConfig;
        if (!cfg.contains(root)) return null;
        String worldName = cfg.getString(root + ".world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        double x = cfg.getDouble(root + ".x");
        double y = cfg.getDouble(root + ".y");
        double z = cfg.getDouble(root + ".z");
        float yaw = (float) cfg.getDouble(root + ".yaw");
        float pitch = (float) cfg.getDouble(root + ".pitch");

        Location loc = new Location(world, x, y, z, yaw, pitch);
        return loc;
    }

    private Location getWarpLocation(String warpName) {
        String root = "warps." + warpName;
        FileConfiguration cfg = playersConfig.contains(root) ? playersConfig : dataConfig;
        if (!cfg.contains(root)) return null;
        String worldName = cfg.getString(root + ".world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        double x = cfg.getDouble(root + ".x");
        double y = cfg.getDouble(root + ".y");
        double z = cfg.getDouble(root + ".z");
        float yaw = (float) cfg.getDouble(root + ".yaw");
        float pitch = (float) cfg.getDouble(root + ".pitch");

        Location loc = new Location(world, x, y, z, yaw, pitch);
        return loc;
    }

    private FileConfiguration getClaimsConfig() {
        return playersConfig.contains("claims") ? playersConfig : dataConfig;
    }

    private void invalidateClaimOwnerIndex() {
        claimOwnerIndexDirty = true;
    }

    private Map<String, UUID> getClaimOwnerIndex() {
        if (!claimOwnerIndexDirty) {
            return claimOwnerIndex;
        }

        synchronized (claimOwnerIndexLock) {
            if (!claimOwnerIndexDirty) {
                return claimOwnerIndex;
            }

            Map<String, UUID> rebuiltIndex = new HashMap<>();
            collectClaimOwnerIndex(playersConfig, rebuiltIndex);
            collectClaimOwnerIndex(dataConfig, rebuiltIndex);

            claimOwnerIndex.clear();
            claimOwnerIndex.putAll(rebuiltIndex);
            claimOwnerIndexDirty = false;
        }

        return claimOwnerIndex;
    }

    private void collectClaimOwnerIndex(FileConfiguration config, Map<String, UUID> target) {
        if (config == null) {
            return;
        }

        ConfigurationSection claimsSection = config.getConfigurationSection("claims");
        if (claimsSection == null) {
            return;
        }

        for (String ownerKey : claimsSection.getKeys(false)) {
            UUID ownerId;
            try {
                ownerId = UUID.fromString(ownerKey);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            for (String chunkKey : claimsSection.getStringList(ownerKey + ".claimed")) {
                if (chunkKey != null && !chunkKey.isBlank()) {
                    target.put(chunkKey, ownerId);
                }
            }
        }
    }

    private FileConfiguration getPwarpConfig() {
        return playersConfig.contains("pwarps") ? playersConfig : dataConfig;
    }

    private FileConfiguration getNotesConfig() {
        return playersConfig.contains("notes") ? playersConfig : dataConfig;
    }

    private List<String> getClaimList(UUID uuid) {
        String path = "claims." + uuid + ".claimed";
        if (playersConfig.contains(path)) return playersConfig.getStringList(path);
        return dataConfig.getStringList(path);
    }

    private List<String> getTrustedList(UUID uuid) {
        String path = "claims." + uuid + ".trusted";
        if (playersConfig.contains(path)) return playersConfig.getStringList(path);
        return dataConfig.getStringList(path);
    }

    private List<String> getTrustedList(UUID uuid, String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return getTrustedList(uuid);
        }

        String path = "claims." + uuid + ".trusted_by_world." + worldName;
        if (playersConfig.contains(path)) return playersConfig.getStringList(path);
        if (dataConfig.contains(path)) return dataConfig.getStringList(path);
        return getTrustedList(uuid);
    }

    private void setClaimList(UUID uuid, List<String> chunks) {
        String path = "claims." + uuid + ".claimed";
        playersConfig.set(path, chunks);
        dataConfig.set(path, null);
        invalidateClaimOwnerIndex();
        savePlayersFile();
        saveDataFile();
    }

    private void setTrustedList(UUID uuid, List<String> trusted) {
        String path = "claims." + uuid + ".trusted";
        playersConfig.set(path, trusted);
        dataConfig.set(path, null);
        savePlayersFile();
        saveDataFile();
    }

    private void setTrustedList(UUID uuid, String worldName, List<String> trusted) {
        if (worldName == null || worldName.isBlank()) {
            setTrustedList(uuid, trusted);
            return;
        }

        String path = "claims." + uuid + ".trusted_by_world." + worldName;
        playersConfig.set(path, trusted);
        dataConfig.set(path, null);
        savePlayersFile();
        saveDataFile();
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
            || strippedTitle.startsWith(ChatColor.stripColor(GUI_PWARP_MANAGE))
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
        if (strippedTitle.equals(ChatColor.stripColor(GUI_MENU_SELECTOR))) {
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
        if (strippedTitle.equals(ChatColor.stripColor(GUI_HELPER_MENU))) {
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
        if (strippedTitle.equals(ChatColor.stripColor(GUI_MODERATOR_MENU))) {
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
        if (strippedTitle.equals(ChatColor.stripColor(GUI_PLAYER_MENU))) {
            if (itemName.equals("Set Home")) {
                p.closeInventory();
                String root = "homes." + p.getUniqueId();
                playersConfig.set(root + ".x", p.getLocation().getX());
                playersConfig.set(root + ".y", p.getLocation().getY());
                playersConfig.set(root + ".z", p.getLocation().getZ());
                playersConfig.set(root + ".world", p.getWorld().getName());
                playersConfig.set(root + ".yaw", p.getLocation().getYaw());
                playersConfig.set(root + ".pitch", p.getLocation().getPitch());
                dataConfig.set(root, null);
                savePlayersFile();
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
            } else if (itemName.equals("TP Spawn")) {
                p.closeInventory();
                Location spawnLoc = getLoc("server_spawn");
                if (spawnLoc != null) {
                    p.teleport(spawnLoc);
                    p.sendMessage(ChatColor.AQUA + "Teleported to spawn.");
                } else {
                    p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                    p.sendMessage(ChatColor.AQUA + "Teleported to spawn.");
                }
            } else if (itemName.equals("TP Factions")) {
                p.closeInventory();
                Bukkit.dispatchCommand(p, "factions");
            } else if (itemName.equals("Set Warp")) {
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(null, ActionType.SET_WARP));
                p.sendMessage(ChatColor.GOLD + "Enter warp name:");
            } else if (itemName.equals("View Player Warps")) {
                openPwarpListGUI(p);
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
        if (strippedTitle.equals(ChatColor.stripColor(GUI_PLAYER_TICKET_MENU))) {
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
        if (strippedTitle.equals(ChatColor.stripColor(GUI_EVENT_LIST))) {
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
        if (strippedTitle.equals(ChatColor.stripColor(GUI_CUSTOM_ENCHANTS))) {
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
        if (strippedTitle.equals(ChatColor.stripColor(GUI_ACTIVE_EVENT))) {
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
            FileConfiguration ticketCfg = getTicketConfig();
            if (itemName.equals("View Ticket")) {
                p.closeInventory();
                openTicketDetailMenu(p, ticketId);
            } else if (itemName.equals("Delete Ticket")) {
                p.closeInventory();
                ticketCfg.set("tickets." + ticketId, null);
                saveTicketFile();
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
            FileConfiguration ticketCfg = getTicketConfig();
            if (itemName.equals("View Appeal")) {
                String message = ticketCfg.getString("appeals." + appealId + ".message", "");
                p.sendMessage(ChatColor.AQUA + "Appeal #" + appealId + ": " + ChatColor.WHITE + message);
            } else if (itemName.equals("Delete Appeal")) {
                p.closeInventory();
                ticketCfg.set("appeals." + appealId, null);
                saveTicketFile();
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
                if (warpId != null && getPwarpConfig().contains("pwarps." + warpId)) {
                    p.closeInventory();
                    pendingPwarpAction.put(p.getUniqueId(), warpId);
                    openPwarpManageGUI(p, warpId);
                }
            }
            return;
        }

        // Player Warp Manage GUI click
        if (title.startsWith(GUI_PWARP_MANAGE)) {
            String warpId = pendingPwarpAction.get(p.getUniqueId());
            FileConfiguration pwarpCfg = getPwarpConfig();
            if (warpId == null || !pwarpCfg.contains("pwarps." + warpId)) {
                p.sendMessage(ChatColor.RED + "Warp no longer exists.");
                p.closeInventory();
                return;
            }

            if (type == Material.BARRIER) {
                openPwarpListGUI(p);
                return;
            }

            if (type == Material.ENDER_PEARL) {
                p.closeInventory();
                World w = Bukkit.getWorld(pwarpCfg.getString("pwarps." + warpId + ".world", "world"));
                if (w != null) {
                    Location loc = new Location(w,
                        pwarpCfg.getDouble("pwarps." + warpId + ".x"),
                        pwarpCfg.getDouble("pwarps." + warpId + ".y"),
                        pwarpCfg.getDouble("pwarps." + warpId + ".z"));
                    p.teleport(loc);
                    pwarpCfg.set("pwarps." + warpId + ".visits", pwarpCfg.getInt("pwarps." + warpId + ".visits", 0) + 1);
                    if (pwarpCfg == dataConfig) saveDataFile(); else savePlayersFile();
                    p.sendMessage(ChatColor.GREEN + "Warped to " + pwarpCfg.getString("pwarps." + warpId + ".name", warpId));
                }
                return;
            }

            if (type == Material.REDSTONE) {
                String owner = pwarpCfg.getString("pwarps." + warpId + ".owner", "");
                if (owner.equals(p.getUniqueId().toString()) || p.isOp() || p.hasPermission("dmt.admin")) {
                    pwarpCfg.set("pwarps." + warpId, null);
                    if (pwarpCfg == dataConfig) saveDataFile(); else savePlayersFile();
                    p.sendMessage(ChatColor.GREEN + "Player warp deleted.");
                } else {
                    p.sendMessage(ChatColor.RED + "You are not allowed to delete this warp.");
                }
                openPwarpListGUI(p);
                return;
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
                FileConfiguration warpCfg = getWarpConfig();
                warpCfg.set("warps." + warpName, null);
                if (warpCfg == dataConfig) saveDataFile(); else savePlayersFile();
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
            } else if (type == Material.CRYING_OBSIDIAN) {
                if (!p.hasPermission("adminpanel.netherlock")) {
                    p.sendMessage(ChatColor.RED + "You do not have permission to use this option.");
                    return;
                }
                boolean currentNether = dataConfig.getBoolean("locks.nether", false);
                dataConfig.set("locks.nether", !currentNether);
                saveDataFile();
                saveDataFileSync();
                p.sendMessage(ChatColor.AQUA + "Nether access " + (currentNether ? "unlocked" : "locked") + "!");
                openWorldUtilitiesMenu(p);
            } else if (type == Material.END_STONE) {
                if (!p.hasPermission("adminpanel.endlock")) {
                    p.sendMessage(ChatColor.RED + "You do not have permission to use this option.");
                    return;
                }
                boolean currentEnd = dataConfig.getBoolean("locks.end", false);
                dataConfig.set("locks.end", !currentEnd);
                saveDataFile();
                saveDataFileSync();
                p.sendMessage(ChatColor.AQUA + "The End access " + (currentEnd ? "unlocked" : "locked") + "!");
                openWorldUtilitiesMenu(p);
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
                        Location entryLocation = resolveSafeWorldEntryLocation(w);
                        p.teleport(entryLocation != null ? entryLocation : w.getSpawnLocation());
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
                forgetManagedWorld(worldName);
                dataConfig.set("worldlocks." + worldName, null);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "World " + worldName + " deleted.");
            }
            return;
        }
        // Claims Menu
        if (title.equals(GUI_CLAIMS)) {
            if (!areClaimFeaturesAvailableInWorld(p.getWorld())) {
                p.closeInventory();
                p.sendMessage(ChatColor.RED + "Claims are not enabled in this world.");
                return;
            }
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
            if (!areClaimFeaturesAvailableInWorld(p.getWorld())) {
                pendingClaimAction.remove(p.getUniqueId());
                p.closeInventory();
                p.sendMessage(ChatColor.RED + "Claims are not enabled in this world.");
                return;
            }
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
                    UUID owner = getChunkOwner(chunkKey);
                    if (!isClaimingAllowedInWorld(p.getWorld())) {
                        p.sendMessage(ChatColor.RED + "Claims can only be created in the configured claim worlds.");
                    } else if (isInsideFactionsSafeZone(p.getLocation())) {
                        p.sendMessage(ChatColor.RED + "You cannot claim land inside the factions spawn safe zone.");
                    } else if (owner != null && !owner.equals(p.getUniqueId())) {
                        p.sendMessage(ChatColor.RED + "This chunk is already claimed by someone else.");
                    } else {
                        int limit = getChunkLimit(p.getUniqueId());
                        int claimed = getClaimedChunks(p.getUniqueId(), getChunkWorldName(chunkKey)).size();
                        if (claimed < limit) {
                            claimChunk(p.getUniqueId(), chunkKey);
                            p.sendMessage(ChatColor.GREEN + "Chunk claimed!");
                        } else {
                            p.sendMessage(ChatColor.RED + "You have reached your claim limit!");
                        }
                    }
                } else {
                    UUID owner = getChunkOwner(chunkKey);
                    if (owner != null && owner.equals(p.getUniqueId())) {
                        unclaimChunk(p.getUniqueId(), chunkKey);
                        p.sendMessage(ChatColor.GREEN + "Chunk unclaimed!");
                    } else {
                        p.sendMessage(ChatColor.RED + "You cannot unclaim a chunk you do not own.");
                    }
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
            if (!areClaimFeaturesAvailableInWorld(p.getWorld())) {
                p.closeInventory();
                p.sendMessage(ChatColor.RED + "Claims are not enabled in this world.");
                return;
            }
            if (type == Material.PLAYER_HEAD) {
                String playerName = itemName;
                trustPlayer(p.getUniqueId(), p.getWorld().getName(), playerName);
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
            if (!areClaimFeaturesAvailableInWorld(p.getWorld())) {
                p.closeInventory();
                p.sendMessage(ChatColor.RED + "Claims are not enabled in this world.");
                return;
            }
            if (type == Material.NAME_TAG) {
                String playerName = itemName;
                untrustPlayer(p.getUniqueId(), p.getWorld().getName(), playerName);
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
                    saveDataFileSync();
                    p.sendMessage(ChatColor.AQUA + "Nether access " + (currentNether ? "unlocked" : "locked") + "!");
                    openMainMenu(p);
                    break;
                case 15:
                    // Toggle end lock
                    boolean currentEnd = dataConfig.getBoolean("locks.end", false);
                    dataConfig.set("locks.end", !currentEnd);
                    saveDataFile();
                    saveDataFileSync();
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
            FileConfiguration ticketCfg = getTicketConfig();
            boolean isAdmin = p.hasPermission("dmt.admin") || p.hasPermission("realmtool.admin");
            if (type == Material.REDSTONE) { 
                if (isAdmin) {
                    openTicketListMenu(p); 
                } else {
                    openMyTicketsMenu(p);
                }
                return; 
            }
            if (type == Material.WRITABLE_BOOK) {
                // Add response via chat
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(ticketId, ActionType.TICKET_RESPOND));
                p.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + "Type your response for ticket #" + ticketId + ":");
            } else if (type == Material.ARROW) {
                if (!isAdmin) return;
                // Set in progress
                ticketCfg.set("tickets." + ticketId + ".status", "in_progress");
                saveTicketFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " set to in_progress.");
                openTicketDetailMenu(p, ticketId);
            } else if (type == Material.GOLD_INGOT) {
                if (!isAdmin) return;
                // Cycle priority
                String current = ticketCfg.getString("tickets." + ticketId + ".priority", "medium");
                String next;
                switch (current) {
                    case "low": next = "medium"; break;
                    case "medium": next = "high"; break;
                    case "high": next = "critical"; break;
                    default: next = "low"; break;
                }
                ticketCfg.set("tickets." + ticketId + ".priority", next);
                saveTicketFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " priority set to " + next + ".");
                openTicketDetailMenu(p, ticketId);
            } else if (type == Material.ARMOR_STAND) {
                if (!isAdmin) return;
                // Assign to me
                ticketCfg.set("tickets." + ticketId + ".assignee", p.getName());
                saveTicketFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " assigned to you.");
                openTicketDetailMenu(p, ticketId);
            } else if (type == Material.EMERALD_BLOCK) {
                if (!isAdmin) return;
                // Resolve
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(ticketId, ActionType.TICKET_RESOLVE));
                p.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + "Type a resolution reason for ticket #" + ticketId + ":");
            } else if (type == Material.BARRIER) {
                // Close ticket
                ticketCfg.set("tickets." + ticketId + ".status", "closed");
                saveTicketFile();
                p.sendMessage(ChatColor.GREEN + "Ticket #" + ticketId + " closed.");
                // Notify player
                String ticketPlayer = ticketCfg.getString("tickets." + ticketId + ".player", "");
                Player target = Bukkit.getPlayer(ticketPlayer);
                if (target != null && target.isOnline()) {
                    target.sendMessage(ChatColor.GOLD + "[Tickets] " + ChatColor.YELLOW + "Your ticket #" + ticketId + " has been closed by " + p.getName() + ".");
                }
                if (isAdmin) {
                    openTicketListMenu(p);
                } else {
                    openMyTicketsMenu(p);
                }
            } else if (type == Material.ENDER_PEARL) {
                if (!isAdmin) return;
                // Teleport to ticket location
                String world = ticketCfg.getString("tickets." + ticketId + ".world");
                if (world != null && Bukkit.getWorld(world) != null) {
                    int x = ticketCfg.getInt("tickets." + ticketId + ".x");
                    int y = ticketCfg.getInt("tickets." + ticketId + ".y");
                    int z = ticketCfg.getInt("tickets." + ticketId + ".z");
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
                List<String> notesList = getPlayerNotes(uuid);
                
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
                    List<String> notesList = getPlayerNotes(uuid);
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
                    List<String> notesList = getPlayerNotes(uuid);
                    if (noteIndex < notesList.size()) {
                        notesList.remove(noteIndex);
                        savePlayerNotes(uuid, notesList);
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
                    setPunished(uuid, d, "Punished via staff menu", p.getName());
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
        if (isDiscordLinkRequiredAndNotLinked(e.getPlayer())) {
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
                e.setCancelled(true);
                // To avoid spam, only message occasionally.
                if (System.currentTimeMillis() % 5000 < 50) { // roughly every 5 seconds
                    String message = getConfig().getString("discord.link_message", "&cPlease link your Discord account to play!\n&eUse the command: /discord link <YourDiscordUsername>");
                    e.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
            }
            return;
        }

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
        if (e.getFrom().getWorld() != null
                && e.getTo().getWorld() != null
                && e.getFrom().getWorld().equals(e.getTo().getWorld())
                && e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
            return;
        }

        boolean wasInFactionsSafeZone = isInsideFactionsSafeZone(e.getFrom());
        boolean isInFactionsSafeZone = isInsideFactionsSafeZone(e.getTo());
        if (wasInFactionsSafeZone != isInFactionsSafeZone) {
            if (isInFactionsSafeZone) {
                sendActionBar(p, ChatColor.AQUA + "Entered factions safe zone");
            } else {
                sendActionBar(p, ChatColor.YELLOW + "Left factions safe zone");
            }
        }
        showFactionsSafeZoneBorderParticles(p, e.getTo());
        
        String newChunk = getChunkKey(e.getTo());
        String oldChunk = currentChunk.getOrDefault(p.getUniqueId(), newChunk);
        
        if (areFactionsFeaturesVisible() && !newChunk.equals(oldChunk)) {
            // Leaving a claimed chunk
            if (shouldProtectClaim(oldChunk)) {
                UUID owner = getChunkOwner(oldChunk);
                if (owner != null && !p.getUniqueId().equals(owner)) {
                    Player ownerPlayer = Bukkit.getPlayer(owner);
                    String ownerName = ownerPlayer != null ? ownerPlayer.getName() : "Unknown";
                    p.sendMessage(ChatColor.YELLOW + "You have left " + ownerName + "'s claim!");
                }
            }
            
            // Entering a claimed chunk
            if (shouldProtectClaim(newChunk)) {
                UUID owner = getChunkOwner(newChunk);
                if (owner != null && !p.getUniqueId().equals(owner) && !isTrustedInChunk(p, newChunk)) {
                    Player ownerPlayer = Bukkit.getPlayer(owner);
                    String ownerName = ownerPlayer != null ? ownerPlayer.getName() : "Unknown";
                    p.sendMessage(ChatColor.YELLOW + "You have entered " + ownerName + "'s claim!");
                }
            }
            currentChunk.put(p.getUniqueId(), newChunk);
        } else if (!newChunk.equals(oldChunk)) {
            currentChunk.put(p.getUniqueId(), newChunk);
        }
    }
    private boolean isDrowsyTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return TOOL_NAME.equals(meta.getDisplayName());
    }

    @EventHandler
    public void onPrepareItemCraft(PrepareItemCraftEvent e) {
        CraftingInventory inv = e.getInventory();
        if (inv == null) return;
        for (ItemStack item : inv.getMatrix()) {
            if (isDrowsyCoin(item) || isDrowsyTool(item)) {
                inv.setResult(new ItemStack(Material.AIR));
                return;
            }
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent e) {
        CraftingInventory inv = e.getInventory();
        for (ItemStack item : inv.getMatrix()) {
            if (isDrowsyCoin(item) || isDrowsyTool(item)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        if (inv == null) return;
        if (isDrowsyCoin(inv.getFirstItem()) || isDrowsyCoin(inv.getSecondItem()) ||
            isDrowsyTool(inv.getFirstItem()) || isDrowsyTool(inv.getSecondItem())) {
            e.setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler
    public void onPrepareGrindstone(PrepareGrindstoneEvent e) {
        Inventory inv = e.getInventory();
        if (inv == null) return;
        if (isDrowsyTool(inv.getItem(0)) || isDrowsyTool(inv.getItem(1)) ||
            isDrowsyCoin(inv.getItem(0)) || isDrowsyCoin(inv.getItem(1))) {
            e.setResult(new ItemStack(Material.AIR));
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
    public void onWorldLoad(WorldLoadEvent e) {
        applyWorldSettings(e.getWorld());
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
            String fromWorld = e.getFrom().getWorld().getName();
            String toWorld = e.getTo().getWorld().getName();
            if (isWorldSeparated(fromWorld)) {
                saveInventoryToConfig(e.getPlayer(), fromWorld);
            } else {
                saveInventoryToShared(e.getPlayer());
            }
            if (isWorldSeparated(toWorld)) {
                loadInventoryFromConfig(e.getPlayer(), toWorld);
            } else {
                loadInventoryFromShared(e.getPlayer());
            }
            applyWorldSettings(e.getTo().getWorld());
            applyPlayerGamemodeForWorld(e.getPlayer(), e.getTo().getWorld());
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
    public void onFactionsSafeZoneDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (isInsideFactionsSafeZone(p.getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFactionsSafeZonePvp(EntityDamageByEntityEvent e) {
        Player attacker = null;
        if (e.getDamager() instanceof Player) {
            attacker = (Player) e.getDamager();
        } else if (e.getDamager() instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) e.getDamager()).getShooter();
            if (shooter instanceof Player) {
                attacker = (Player) shooter;
            }
        }

        if (attacker != null && isInsideFactionsSafeZone(attacker.getLocation())) {
            e.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "PvP is disabled inside the factions spawn safe zone.");
            return;
        }

        if (e.getEntity() instanceof Player) {
            Player victim = (Player) e.getEntity();
            if (isInsideFactionsSafeZone(victim.getLocation())) {
                e.setCancelled(true);
                if (attacker != null) {
                    attacker.sendMessage(ChatColor.RED + "You cannot attack players inside the factions spawn safe zone.");
                }
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isDiscordLinkRequiredAndNotLinked(e.getPlayer())) {
            e.setCancelled(true);
            String message = getConfig().getString("discord.link_message", "&cPlease link your Discord account to play!\n&eUse the command: /discord link <YourDiscordUsername>");
            e.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }

        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (isPunished(e.getPlayer().getUniqueId())) e.setCancelled(true);
        else {
            if (isProtectedFactionsSafeZone(e.getPlayer(), e.getBlock().getLocation())) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot build inside the factions spawn safe zone!");
                return;
            }
            if (factionService != null && factionService.isEnabled() && !factionService.canBuild(e.getPlayer(), e.getBlock().getLocation())) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot build in this faction claim!");
                return;
            }
            // Check chunk claims
            String chunkKey = getChunkKey(e.getBlock().getLocation());
            if (shouldProtectClaim(chunkKey) && !isTrustedInChunk(e.getPlayer(), chunkKey)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot build in this claimed chunk!");
                return;
            }
            saveLog(e.getBlock().getLocation(), ChatColor.GREEN + "Placed by " + e.getPlayer().getName());
        }
    }

    @EventHandler
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent e) {
        Player p = e.getPlayer();
        if (isDiscordLinkRequiredAndNotLinked(p)) {
            e.setCancelled(true);
            String message = getConfig().getString("discord.link_message", "&cPlease link your Discord account to play!\n&eUse the command: /discord link <YourDiscordUsername>");
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }

        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());
        if (isPunished(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        Location target = e.getBlockClicked().getRelative(e.getBlockFace()).getLocation();
        if (isProtectedFactionsSafeZone(p, target)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot use buckets inside the factions spawn safe zone!");
            return;
        }
        if (factionService != null && factionService.isEnabled() && !factionService.canBuild(p, target)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot place lava/water in this faction claim!");
            return;
        }
        String chunkKey = getChunkKey(target);
        if (shouldProtectClaim(chunkKey) && !isTrustedInChunk(p, chunkKey)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot place lava/water in this claimed chunk!");
            return;
        }
        saveLog(target, ChatColor.GREEN + "Bucket used by " + p.getName());
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent e) {
        Material type = e.getBlock().getType();
        if (type != Material.WATER && type != Material.LAVA && type != Material.WATER_CAULDRON && type != Material.LAVA_CAULDRON) {
            return;
        }

        String chunkKey = getChunkKey(e.getToBlock().getLocation());
        if (isInsideFactionsSafeZone(e.getToBlock().getLocation())
            || (factionService != null && factionService.isEnabled() && factionService.isFactionClaimed(e.getToBlock().getLocation()))
            || shouldProtectClaim(chunkKey)) {
            // Prevent fluid flow into claimed chunks to avoid griefing via lava/water spread
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        if (factionService != null && factionService.isEnabled()) {
            String sourceName = e.getEntityType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (e.getEntity() instanceof org.bukkit.entity.TNTPrimed primed) {
                Entity source = primed.getSource();
                if (source instanceof Player playerSource) {
                    sourceName = playerSource.getName();
                }
            }
            factionService.handleExplosion(e.blockList(), sourceName);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (isDiscordLinkRequiredAndNotLinked(e.getPlayer())) {
            e.setCancelled(true);
            String message = getConfig().getString("discord.link_message", "&cPlease link your Discord account to play!\n&eUse the command: /discord link <YourDiscordUsername>");
            e.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }

        Player p = e.getPlayer();
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());

        if (isProtectedFactionsSafeZone(p, e.getBlock().getLocation())) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot break blocks inside the factions spawn safe zone!");
            return;
        }

        // Anti-xray: block excessive ore mining in a short time window
        if (checkXray(p, e.getBlock())) {
            e.setCancelled(true);
            return;
        }

        if (isPunished(p.getUniqueId())) {
            e.setCancelled(true);
        } else {
            if (factionService != null && factionService.isEnabled() && !factionService.canBuild(p, e.getBlock().getLocation())) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You cannot break blocks in this faction claim!");
                return;
            }
            // Check chunk claims
            String chunkKey = getChunkKey(e.getBlock().getLocation());
            if (shouldProtectClaim(chunkKey) && !isTrustedInChunk(p, chunkKey)) {
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
            if (m.name().contains("ORE")) {
                trackQuestProgress(p, "break_ores", 1);
                if (dataConfig.getBoolean("mining_coins.enabled", true)) {
                    if (Math.random() < dataConfig.getDouble("mining_coins.chance", 0.05)) {
                        int amount = dataConfig.getInt("mining_coins.amount", 1);
                        addCoins(p.getUniqueId(), amount);
                        try {
                            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(ChatColor.GOLD + "+" + amount + " Coins"));
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        Location pistonLoc = e.getBlock().getLocation();
        if (isInsideFactionsSafeZone(pistonLoc)) {
            e.setCancelled(true);
            return;
        }
        String pistonChunk = getChunkKey(pistonLoc);
        UUID pistonOwner = getChunkOwner(pistonChunk);

        for (Block moved : e.getBlocks()) {
            Location dest = moved.getLocation().add(e.getDirection().getDirection());
            if (isInsideFactionsSafeZone(moved.getLocation()) || isInsideFactionsSafeZone(dest)) {
                e.setCancelled(true);
                return;
            }
            String destChunk = getChunkKey(dest);
            if (shouldProtectClaim(destChunk)) {
                UUID destOwner = getChunkOwner(destChunk);
                if (pistonOwner == null || !pistonOwner.equals(destOwner)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!e.isSticky()) return;

        Location pistonLoc = e.getBlock().getLocation();
        if (isInsideFactionsSafeZone(pistonLoc)) {
            e.setCancelled(true);
            return;
        }
        String pistonChunk = getChunkKey(pistonLoc);
        UUID pistonOwner = getChunkOwner(pistonChunk);

        for (Block moved : e.getBlocks()) {
            Location dest = moved.getLocation().add(e.getDirection().getDirection());
            if (isInsideFactionsSafeZone(moved.getLocation()) || isInsideFactionsSafeZone(dest)) {
                e.setCancelled(true);
                return;
            }
            String destChunk = getChunkKey(dest);
            if (shouldProtectClaim(destChunk)) {
                UUID destOwner = getChunkOwner(destChunk);
                if (pistonOwner == null || !pistonOwner.equals(destOwner)) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onChestAccess(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player) {
            lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        }

        Location invLocation = e.getInventory().getLocation();
        if (invLocation != null) {
            String chunkKey = getChunkKey(invLocation);
            Player p = (Player) e.getPlayer();
            if (isProtectedFactionsSafeZone(p, invLocation)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You cannot access containers inside the factions spawn safe zone!");
                return;
            }
            if (shouldProtectClaim(chunkKey) && !isTrustedInChunk(p, chunkKey)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You cannot access containers in this claimed chunk!");
                return;
            }
        }

        if (e.getInventory().getType() == InventoryType.CHEST && invLocation != null) {
            saveLog(invLocation, ChatColor.YELLOW + "Opened by " + e.getPlayer().getName());
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClickForAfk(InventoryClickEvent e) {
        lastActivity.put(e.getWhoClicked().getUniqueId(), System.currentTimeMillis());
    }
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (isDiscordLinkRequiredAndNotLinked(e.getPlayer())) {
            e.setCancelled(true);
        }
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
                if (!areClaimsEnabled()) {
                    p.sendMessage(ChatColor.RED + "Claims are currently disabled.");
                    return;
                }
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
                    if (factionService != null && factionService.isEnabled() && factionService.isFactionClaimed(e.getClickedBlock().getLocation())) {
                        p.sendMessage(factionService.describeClaim(e.getClickedBlock().getLocation()));
                    } else if (shouldProtectClaim(chunkKey)) {
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
                Player p = e.getPlayer();

                if (p.isSneaking()) {
                    // If crouching, try deleting the hologram we're clicking on instead of creating
                    Entity target = null;
                    for (Entity ent : p.getNearbyEntities(6, 6, 6)) {
                        if (!(ent instanceof ArmorStand)) continue;
                        ArmorStand as = (ArmorStand) ent;
                        if (!as.isMarker() || as.isVisible()) continue;
                        if (as.getLocation().distance(p.getLocation()) <= 6) {
                            target = as;
                            break;
                        }
                    }

                    if (target instanceof ArmorStand) {
                        String hologramId = findTeleportHologramIdAtLocation(target.getLocation());
                        if (hologramId != null) {
                            deleteTeleportHologram(hologramId, p);
                            e.setCancelled(true);
                            return;
                        }
                    }
                }

                e.setCancelled(true);
                p.sendMessage(ChatColor.AQUA + "Hologram type? regular or teleport (type the word)");
                pendingActions.put(p.getUniqueId(), new PunishmentContext(null, ActionType.HOLOGRAM));
            }
        }
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractAtEntityEvent e) {
        if (!(e.getRightClicked() instanceof ArmorStand)) return;
        ArmorStand as = (ArmorStand) e.getRightClicked();
        Player p = e.getPlayer();

        ItemStack held = p.getInventory().getItemInMainHand();
        if (p.isSneaking() && held != null && held.hasItemMeta() && HOLOGRAM_WAND_NAME.equals(held.getItemMeta().getDisplayName())) {
            String hologramId = findTeleportHologramIdAtLocation(as.getLocation());
            if (hologramId != null) {
                cleanupTeleportHologramEntities(hologramId);
                dataConfig.set("holograms." + hologramId, null);
                dataConfig.set("holograms." + hologramId + ".lines", null);
                dataConfig.set("holograms." + hologramId + ".type", null);
                dataConfig.set("holograms." + hologramId + ".title", null);
                dataConfig.set("holograms." + hologramId + ".world", null);
                dataConfig.set("holograms." + hologramId + ".online", null);
                dataConfig.set("holograms." + hologramId + ".version", null);
                dataConfig.set("holograms." + hologramId + ".location", null);
                saveDataFile();
                if (hologramId.equals(selectedHologram.get(p.getUniqueId()))) {
                    selectedHologram.remove(p.getUniqueId());
                }
                p.sendMessage(ChatColor.GREEN + "Hologram " + hologramId + " removed by crouch+wand." );
                e.setCancelled(true);
                return;
            }
        }

        handleNpcInteraction(p, as.getUniqueId().toString());
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
                    Location entryLocation = resolveSafeWorldEntryLocation(w);
                    p.teleport(entryLocation != null ? entryLocation : w.getSpawnLocation());
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
    public void onClaimedDoorButtonBedInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;

        Material type = block.getType();
        boolean isDoor = type.toString().endsWith("_DOOR") || type.toString().endsWith("_TRAPDOOR") || type == Material.IRON_DOOR;
        boolean isButton = type.toString().endsWith("_BUTTON");
        boolean isBed = type.toString().endsWith("_BED");
        if (!isDoor && !isButton && !isBed) return;

        Player p = e.getPlayer();
        String chunkKey = getChunkKey(block.getLocation());

        if (isProtectedFactionsSafeZone(p, block.getLocation())) {
            e.setCancelled(true);
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setUseItemInHand(Event.Result.DENY);
            p.sendMessage(ChatColor.RED + "You cannot interact with blocks inside the factions spawn safe zone!");
            return;
        }

        if (factionService != null && factionService.isEnabled() && !factionService.canBuild(p, block.getLocation())) {
            e.setCancelled(true);
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setUseItemInHand(Event.Result.DENY);
            p.sendMessage(ChatColor.RED + "You cannot interact in this faction claim!");
            return;
        }

        if (shouldProtectClaim(chunkKey) && !isTrustedInChunk(p, chunkKey)) {
            e.setCancelled(true);
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setUseItemInHand(Event.Result.DENY);
            p.sendMessage(ChatColor.RED + "You cannot interact with this block in a claimed chunk!");
        }
    }

    @EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent e) {
        Player p = e.getPlayer();
        Location bedLoc = e.getBed().getLocation();
        if (isProtectedFactionsSafeZone(p, bedLoc)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot sleep inside the factions spawn safe zone!");
            return;
        }
        if (factionService != null && factionService.isEnabled() && !factionService.canBuild(p, bedLoc)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot sleep in this faction claim!");
            return;
        }
        String chunkKey = getChunkKey(bedLoc);
        if (shouldProtectClaim(chunkKey) && !isTrustedInChunk(p, chunkKey)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You cannot sleep in this claimed chunk!");
        }
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onToolUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!item.getItemMeta().getDisplayName().equals(TOOL_NAME)) return;

        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long lastOpenAt = lastToolMenuOpenAt.get(p.getUniqueId());
        if (lastOpenAt != null && now - lastOpenAt < TOOL_MENU_OPEN_DEBOUNCE_MS) {
            e.setCancelled(true);
            return;
        }
        lastToolMenuOpenAt.put(p.getUniqueId(), now);
        e.setCancelled(true);

        // Keep the tool locked to the last hotbar slot
        ensurePlayerHasTool(p);

        if (isHelper(p) || isModerator(p) || p.isOp() || p.hasPermission("dmt.admin")) {
            openMenuSelector(p);
        } else {
            openPlayerMenu(p);
        }
    }

    @EventHandler
    public void onToolDrop(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        if (item != null && item.hasItemMeta() && TOOL_NAME.equals(item.getItemMeta().getDisplayName())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You cannot drop the Drowsy tool.");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        String rawMsg = e.getMessage().trim();
        String msgLower = rawMsg.toLowerCase(Locale.ROOT);
        if (!msgLower.startsWith("/")) return;

        Player p = e.getPlayer();
        if (isDiscordLinkRequiredAndNotLinked(p)) {
            if (!msgLower.startsWith("/discord") && !msgLower.startsWith("/drowsytool")) {
                e.setCancelled(true);
                String message = getConfig().getString("discord.link_message", "&cPlease link your Discord account to play!\n&eUse the command: /discord link <YourDiscordUsername>");
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                return;
            }
        }

        // Hide /personal actions from console / command log by handling in preprocess and cancelling default execution.
        if (msgLower.startsWith("/personal")) {
            e.setCancelled(true);
            if (!hasPersonalCommandAccess(p)) {
                p.sendMessage(ChatColor.RED + "No permission.");
                return;
            }

            String[] args = rawMsg.substring(1).split("\\s+"); // remove leading slash
            if (args.length < 2 || !args[0].equalsIgnoreCase("personal") || !args[1].equalsIgnoreCase("npc")) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /personal npc spawn miner | /personal npc despawn miner | /personal npc miner collect");
                return;
            }

            if (args.length >= 3 && args[1].equalsIgnoreCase("npc")) {
                String action = args[2].toLowerCase(Locale.ROOT);
                if (action.equals("spawn") && args.length >= 4 && args[3].equalsIgnoreCase("miner")) {
                    spawnPersonalMiner(p);
                    return;
                }
                if (action.equals("despawn") && args.length >= 4 && args[3].equalsIgnoreCase("miner")) {
                    despawnPersonalMiner(p);
                    return;
                }
                if (action.equals("miner") && args.length >= 4 && args[3].equalsIgnoreCase("collect")) {
                    collectPersonalMiner(p);
                    return;
                }
            }

            p.sendMessage(ChatColor.YELLOW + "Usage: /personal npc spawn miner | /personal npc despawn miner | /personal npc miner collect");
            return;
        }

        // Prevent players from revealing the world seed via commands.
        // Admins are allowed to use these commands.
        if (!e.getPlayer().hasPermission("dmt.admin")) {
            // Block any command containing "seed" (covers /seed, /world seed, /gamerule seed, /help seed, etc.)
            if (msgLower.contains("seed")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot use that command.");
                return;
            }

            // Some plugins may expose seed via other terms; block typical patterns.
            if (msgLower.contains("worldseed") || msgLower.contains("world_seed") || msgLower.contains("world-seed")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You cannot use that command.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(AsyncPlayerPreLoginEvent e) {
        if (dataConfig.getBoolean("maintenance.enabled", false)) {
            String name = e.getName();
            List<String> whitelist = dataConfig.getStringList("maintenance.whitelist");
            boolean isExempt = whitelist.contains(name);
            if (!isExempt) {
                String msg = dataConfig.getString("maintenance.message", "Server is under maintenance...");
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ChatColor.RED + msg);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (isDiscordLinkRequiredAndNotLinked(e.getPlayer())) {
            String message = getConfig().getString("discord.link_message", "&cPlease link your Discord account to play!\n&eUse the command: /discord link <YourDiscordUsername>");
            e.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }

        UUID uuid = e.getPlayer().getUniqueId();
        lastActivity.put(uuid, System.currentTimeMillis());

        // Load the correct per-world/shared inventory on join
        String worldName = e.getPlayer().getWorld().getName();
        if (isWorldSeparated(worldName)) {
            loadInventoryFromConfig(e.getPlayer(), worldName);
        } else {
            loadInventoryFromShared(e.getPlayer());
        }
        applyWorldSettings(e.getPlayer().getWorld());
        applyPlayerGamemodeForWorld(e.getPlayer(), e.getPlayer().getWorld());

        if (isPunished(uuid)) {
            if (!punishTeam.hasEntry(e.getPlayer().getName())) {
                punishTeam.addEntry(e.getPlayer().getName());
            }
            long min = (getPunishmentExpiry(uuid) - System.currentTimeMillis()) / 60000;
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

        // Join spawn override logic for hub forcespawn / last world restore
        boolean hubForceSpawn = dataConfig.getBoolean("hub.forcespawn", false);
        Location hubLocation = getLoc("hub_location");
        Location serverSpawn = getLoc("server_spawn");
        String lastWorldName = dataConfig.getString("last_world." + uuid, null);

        Location targetSpawn = null;
        String spawnReason = null;

        if (hubForceSpawn) {
            if (hubLocation != null) {
                targetSpawn = hubLocation;
                spawnReason = "the hub";
            } else if (serverSpawn != null) {
                targetSpawn = serverSpawn;
                spawnReason = "server spawn (hub not set)";
            }
        } else {
            if (lastWorldName != null && !lastWorldName.isEmpty()) {
                World lastWorld = Bukkit.getWorld(lastWorldName);
                if (lastWorld != null) {
                    if (dataConfig.getBoolean("worldlocks." + lastWorldName, false) && !e.getPlayer().hasPermission("dmt.admin")) {
                        if (serverSpawn != null) {
                            targetSpawn = serverSpawn;
                            spawnReason = "server spawn (last world is locked)";
                        }
                    } else {
                        Location lastLoc = getLoc("last_location." + uuid + "." + lastWorldName);
                        targetSpawn = (lastLoc != null ? lastLoc : lastWorld.getSpawnLocation());
                        spawnReason = "your last world (" + lastWorldName + ")";
                    }
                }
            }
            if (targetSpawn == null && serverSpawn != null) {
                targetSpawn = serverSpawn;
                spawnReason = "server spawn";
            }
        }

        if (targetSpawn != null) {
            final Location finalSpawn = targetSpawn;
            final String finalReason = spawnReason;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (e.getPlayer().isOnline()) {
                    e.getPlayer().teleport(finalSpawn);
                    e.getPlayer().sendMessage(ChatColor.AQUA + "You have been teleported to " + finalReason + ".");
                    applyWorldSettings(finalSpawn.getWorld());
                    if (isWorldSeparated(finalSpawn.getWorld().getName())) {
                        loadInventoryFromConfig(e.getPlayer(), finalSpawn.getWorld().getName());
                    } else {
                        loadInventoryFromShared(e.getPlayer());
                    }
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

        // Maintain invisible / spectator staff state on join
        if (isInvisible(e.getPlayer())) {
            updateInvisibleVisibility(e.getPlayer(), true);
            e.getPlayer().sendMessage(ChatColor.GRAY + "You are currently in invisible spectator mode.");
        }
        refreshInvisibleVisibilityFor(e.getPlayer());

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
                int coinsReward = dataConfig.getInt("daily_login_base_coins", 10) + (streak * dataConfig.getInt("daily_login_streak_coins", 2));
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
        if (networkProfileService != null) {
            networkProfileService.updateLastSeenName(uuid, e.getPlayer().getName());
        } else {
            dataConfig.set("last_seen_name." + uuid, e.getPlayer().getName());
        }
        saveDataFile();

        // --- TICKET NOTIFICATIONS ON JOIN ---
        FileConfiguration ticketCfg = getTicketConfig();
        if (ticketCfg.contains("tickets")) {
            int updatedCount = 0;
            for (String key : ticketCfg.getConfigurationSection("tickets").getKeys(false)) {
                if (key.equals("next_id")) continue;
                String ticketPlayer = ticketCfg.getString("tickets." + key + ".player", "");
                if (ticketPlayer.equalsIgnoreCase(e.getPlayer().getName()) && ticketCfg.getBoolean("tickets." + key + ".has_new_response", false)) {
                    updatedCount++;
                    ticketCfg.set("tickets." + key + ".has_new_response", false);
                }
            }
            if (updatedCount > 0) {
                saveTicketFile();
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
        // Ensure the player has the tool item and keep it locked in the last hotbar slot (slot 8).
        Inventory inv = p.getInventory();
        int toolSlot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.hasItemMeta() && TOOL_NAME.equals(item.getItemMeta().getDisplayName())) {
                toolSlot = i;
                break;
            }
        }

        if (toolSlot == -1) {
            ItemStack tool = new ItemStack(Material.DIAMOND);
            ItemMeta m = tool.getItemMeta();
            m.setDisplayName(TOOL_NAME);
            m.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            tool.setItemMeta(m);
            inv.setItem(8, tool);
            return;
        }

        // If the tool exists but isn't in the locked slot, move it back to slot 8.
        if (toolSlot != 8) {
            ItemStack existing = inv.getItem(8);
            inv.setItem(8, inv.getItem(toolSlot));
            inv.setItem(toolSlot, existing);
        }
    }

    private void registerCommand(String name, CommandExecutor executor, TabCompleter completer,
                                 List<String> registeredCommands, List<String> missingCommands) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            missingCommands.add(name);
            getLogger().warning("Command '" + name + "' is missing from plugin.yml and was not registered.");
            return;
        }

        command.setExecutor(executor);
        if (completer != null) {
            command.setTabCompleter(completer);
        }
        registeredCommands.add(name);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        // Save last seen location per world so we can restore when teleporting back.
        Location lastLoc = e.getPlayer().getLocation();
        if (lastLoc != null && lastLoc.getWorld() != null) {
            saveLoc("last_location." + uuid + "." + lastLoc.getWorld().getName(), lastLoc);
            dataConfig.set("last_world." + uuid, lastLoc.getWorld().getName());
            if (isWorldSeparated(lastLoc.getWorld().getName())) {
                saveInventoryToConfig(e.getPlayer(), lastLoc.getWorld().getName());
            } else {
                saveInventoryToShared(e.getPlayer());
            }
        }

        lastActivity.remove(uuid);
        removePermissionAttachment(e.getPlayer());
        this.trackSession(uuid, e.getPlayer().getName(), false);
        this.logAction("System", "player_left", e.getPlayer().getName());
        fireDiscordEvent("leaves", "Player Left", "**" + e.getPlayer().getName() + "** left the server.", 0xe74c3c, e.getPlayer().getName());
        
        // Force an immediate save to prevent data loss if the server crashes shortly after
        performDataSave();
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        lastActivity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (isDiscordLinkRequiredAndNotLinked(e.getPlayer())) {
            e.setCancelled(true);
            String message = getConfig().getString("discord.link_message", "&cPlease link your Discord account to play!\n&eUse the command: /discord link <YourDiscordUsername>");
            e.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }

        if (isMuted(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You are muted and cannot chat.");
            return;
        }

        if (factionService != null && factionService.isEnabled() && factionService.isFactionChatEnabled(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            String factionMessage = e.getMessage();
            Bukkit.getScheduler().runTask(this, () -> factionService.sendFactionChat(e.getPlayer(), factionMessage));
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
        setPunished(u, d, "Punished in-game", null);
    }

    public void setPunished(UUID u, long d, String reason, String actor) {
        long expiryTimestamp = System.currentTimeMillis() + d;
        NetworkPunishment punishment = new NetworkPunishment(
            u,
            expiryTimestamp,
            reason == null || reason.isBlank() ? "Punished in-game" : reason.trim(),
            actor == null || actor.isBlank() ? null : actor.trim(),
            System.currentTimeMillis()
        );
        if (networkModerationService != null) {
            networkModerationService.savePunishment(punishment);
        } else {
            saveLocalPunishment(punishment);
        }
        punishmentCache.put(u, new CachedPunishmentState(punishment, expiryTimestamp));
        activePunishmentExpiries.put(u, expiryTimestamp);
        invalidateModerationAggregateCaches();
        
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            // Store player's current location for later restoration
            saveLoc("player_location." + u, p.getLocation());
            
            if (!punishTeam.hasEntry(p.getName())) {
                punishTeam.addEntry(p.getName());
            }
            p.sendMessage(ChatColor.RED + "You are punished! You cannot move/build.");
            
            // Teleport to punishment location if set
            Location punishLoc = getLoc("punishment_location");
            if (punishLoc != null) {
                p.teleport(punishLoc);
                p.sendMessage(ChatColor.RED + "You have been teleported to the punishment location.");
            }
        }
    }
    
    public void removePunishment(UUID u) {
        if (networkModerationService != null) {
            networkModerationService.savePunishment(new NetworkPunishment(u, 0L, null, null, 0L));
        } else {
            saveLocalPunishment(new NetworkPunishment(u, 0L, null, null, 0L));
        }
        punishmentCache.put(u, new CachedPunishmentState(null, System.currentTimeMillis() + PUNISHMENT_CACHE_TTL_MS));
        activePunishmentExpiries.remove(u);
        invalidateModerationAggregateCaches();
        Player p = Bukkit.getPlayer(u);
        // Restore player's original location
        Location originalLoc = getLoc("player_location." + u);
        if (originalLoc != null) {
            if (p != null) {
                // Player is online - schedule teleport next tick to ensure they're fully loaded
                if (punishTeam.hasEntry(p.getName())) {
                    punishTeam.removeEntry(p.getName());
                }
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

    public boolean areFactionsFeaturesVisible() {
        return getConfig().getBoolean("factions_world.visible", true);
    }

    private void setFactionsFeaturesVisible(boolean visible) {
        getConfig().set("factions_world.visible", visible);
        saveConfig();
    }

    private String getFactionsWorldName() {
        return getConfig().getString("factions_world.name", "").trim();
    }

    private boolean isFactionsWorld(World world) {
        return world != null && !getFactionsWorldName().isEmpty() && world.getName().equalsIgnoreCase(getFactionsWorldName());
    }

    private boolean isPrimarySmpWorld(World world) {
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            return false;
        }

        for (World candidate : Bukkit.getWorlds()) {
            if (candidate.getEnvironment() == World.Environment.NORMAL) {
                return candidate.getUID().equals(world.getUID());
            }
        }

        return false;
    }

    private boolean isConfiguredClaimWorld(World world) {
        if (world == null) {
            return false;
        }

        if (isFactionsWorld(world)) {
            return true;
        }

        if (isPrimarySmpWorld(world)) {
            return true;
        }

        for (String worldName : getConfig().getStringList("factions_world.claims_allowed_worlds")) {
            if (worldName != null && world.getName().equalsIgnoreCase(worldName.trim())) {
                return true;
            }
        }

        return false;
    }

    private boolean areClaimsEnabled() {
        return getConfig().getBoolean("factions_world.claims_enabled", true);
    }

    private boolean areClaimFeaturesAvailableInWorld(World world) {
        return world != null && areClaimsEnabled() && isConfiguredClaimWorld(world);
    }

    private boolean isClaimingAllowedInWorld(World world) {
        return areClaimFeaturesAvailableInWorld(world);
    }

    private Location getFactionsSpawnLocation() {
        Location saved = getLoc("factions_world.spawn_location");
        if (saved != null) return saved;

        String worldName = getFactionsWorldName();
        if (worldName.isEmpty()) return null;

        World world = Bukkit.getWorld(worldName);
        return world != null ? world.getSpawnLocation() : null;
    }

    private double getFactionsSafeZoneRadius() {
        return Math.max(0D, getConfig().getDouble("factions_world.safe_zone_radius", 96.0D));
    }

    private boolean canBypassFactionsSafeZone(Player p) {
        return p != null && (p.isOp() || p.hasPermission("dmt.admin") || p.hasPermission("claims.override"));
    }

    private boolean isInsideFactionsSafeZone(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!areFactionsFeaturesVisible()) return false;
        if (!getConfig().getBoolean("factions_world.safe_zone_enabled", true)) return false;
        if (!isFactionsWorld(location.getWorld())) return false;

        Location spawn = getFactionsSpawnLocation();
        if (spawn == null || spawn.getWorld() == null) return false;
        if (!spawn.getWorld().getName().equalsIgnoreCase(location.getWorld().getName())) return false;

        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        double radius = getFactionsSafeZoneRadius();
        return (dx * dx) + (dz * dz) <= (radius * radius);
    }

    private boolean isProtectedFactionsSafeZone(Player p, Location location) {
        return !canBypassFactionsSafeZone(p) && isInsideFactionsSafeZone(location);
    }

    private boolean shouldProtectClaim(String chunkKey) {
        return areClaimsEnabled() && isChunkClaimed(chunkKey);
    }

    private void sendActionBar(Player p, String message) {
        if (p == null || message == null || message.isEmpty()) return;
        try {
            p.spigot().sendMessage(
                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message)
            );
        } catch (Exception ignored) {
            p.sendMessage(message);
        }
    }

    private void showFactionsSafeZoneBorderParticles(Player p, Location location) {
        if (p == null || location == null || location.getWorld() == null) return;
        if (!isFactionsWorld(location.getWorld())) return;

        Location spawn = getFactionsSpawnLocation();
        if (spawn == null || spawn.getWorld() == null) return;
        if (!spawn.getWorld().getName().equalsIgnoreCase(location.getWorld().getName())) return;

        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        double distance = Math.sqrt((dx * dx) + (dz * dz));
        double radius = getFactionsSafeZoneRadius();
        if (radius <= 0D || distance <= 0.0001D) return;

        double edgeDistance = Math.abs(distance - radius);
        if (edgeDistance > 6.0D) return;

        double normX = dx / distance;
        double normZ = dz / distance;
        double edgeX = spawn.getX() + (normX * radius);
        double edgeZ = spawn.getZ() + (normZ * radius);

        for (int i = 0; i < 6; i++) {
            Location particleLoc = new Location(location.getWorld(), edgeX, location.getY() + 0.4D + (i * 0.35D), edgeZ);
            p.spawnParticle(org.bukkit.Particle.END_ROD, particleLoc, 1, 0.08, 0.08, 0.08, 0.0);
        }
    }

    private boolean isWorldSeparated(String worldName) {
        return dataConfig.getBoolean("worlds." + worldName + ".separate", false);
    }

    private String itemStackArrayToBase64(ItemStack[] items) {
        try {
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            org.bukkit.util.io.BukkitObjectOutputStream dataOutput = new org.bukkit.util.io.BukkitObjectOutputStream(outputStream);
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();
            return java.util.Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to serialize inventory to Base64", e);
            return "";
        }
    }

    private ItemStack[] itemStackArrayFromBase64(String data) {
        try {
            java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(data));
            org.bukkit.util.io.BukkitObjectInputStream dataInput = new org.bukkit.util.io.BukkitObjectInputStream(inputStream);
            ItemStack[] items = new ItemStack[dataInput.readInt()];
            for (int i = 0; i < items.length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            dataInput.close();
            return items;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to deserialize inventory from Base64", e);
            return new ItemStack[0];
        }
    }

    private void saveInventoryToConfig(Player p, String worldName) {
        String base = "worlds." + worldName + ".inventory." + p.getUniqueId();
        dataConfig.set(base + ".contents_b64", itemStackArrayToBase64(p.getInventory().getContents()));
        dataConfig.set(base + ".armor_b64", itemStackArrayToBase64(p.getInventory().getArmorContents()));
        dataConfig.set(base + ".contents", null);
        dataConfig.set(base + ".armor", null);
        saveDataFile();
    }

    private void loadInventoryFromConfig(Player p, String worldName) {
        String base = "worlds." + worldName + ".inventory." + p.getUniqueId();
        p.getInventory().clear();
        if (dataConfig.contains(base + ".contents_b64")) {
            p.getInventory().setContents(itemStackArrayFromBase64(dataConfig.getString(base + ".contents_b64")));
        } else if (dataConfig.contains(base + ".contents")) {
            List<ItemStack> contents = (List<ItemStack>) dataConfig.getList(base + ".contents");
            if (contents != null) {
                for (int i = 0; i < contents.size(); i++) {
                    if (i < p.getInventory().getSize()) p.getInventory().setItem(i, contents.get(i));
                }
            }
        }
        if (dataConfig.contains(base + ".armor_b64")) {
            p.getInventory().setArmorContents(itemStackArrayFromBase64(dataConfig.getString(base + ".armor_b64")));
        } else if (dataConfig.contains(base + ".armor")) {
            List<ItemStack> armor = (List<ItemStack>) dataConfig.getList(base + ".armor");
            if (armor != null && armor.size() == 4) p.getInventory().setArmorContents(armor.toArray(new ItemStack[0]));
        }
        ensurePlayerHasTool(p);
    }

    private void saveInventoryToShared(Player p) {
        String base = "worlds.shared.inventory." + p.getUniqueId();
        dataConfig.set(base + ".contents_b64", itemStackArrayToBase64(p.getInventory().getContents()));
        dataConfig.set(base + ".armor_b64", itemStackArrayToBase64(p.getInventory().getArmorContents()));
        dataConfig.set(base + ".contents", null);
        dataConfig.set(base + ".armor", null);
        saveDataFile();
    }

    private void loadInventoryFromShared(Player p) {
        String base = "worlds.shared.inventory." + p.getUniqueId();
        p.getInventory().clear();
        if (dataConfig.contains(base + ".contents_b64")) {
            p.getInventory().setContents(itemStackArrayFromBase64(dataConfig.getString(base + ".contents_b64")));
        } else if (dataConfig.contains(base + ".contents")) {
            List<ItemStack> contents = (List<ItemStack>) dataConfig.getList(base + ".contents");
            if (contents != null) {
                for (int i = 0; i < contents.size(); i++) {
                    if (i < p.getInventory().getSize()) p.getInventory().setItem(i, contents.get(i));
                }
            }
        }
        if (dataConfig.contains(base + ".armor_b64")) {
            p.getInventory().setArmorContents(itemStackArrayFromBase64(dataConfig.getString(base + ".armor_b64")));
        } else if (dataConfig.contains(base + ".armor")) {
            List<ItemStack> armor = (List<ItemStack>) dataConfig.getList(base + ".armor");
            if (armor != null && armor.size() == 4) p.getInventory().setArmorContents(armor.toArray(new ItemStack[0]));
        }
        ensurePlayerHasTool(p);
    }

    private void clearInventoryForWorld(Player p, String worldName) {
        String base = "worlds." + worldName + ".inventory." + p.getUniqueId();
        dataConfig.set(base + ".contents", null);
        dataConfig.set(base + ".armor", null);
        dataConfig.set(base + ".contents_b64", null);
        dataConfig.set(base + ".armor_b64", null);
        saveDataFile();
    }

    private void clearSharedInventory(Player p) {
        String base = "worlds.shared.inventory." + p.getUniqueId();
        dataConfig.set(base + ".contents", null);
        dataConfig.set(base + ".armor", null);
        dataConfig.set(base + ".contents_b64", null);
        dataConfig.set(base + ".armor_b64", null);
        saveDataFile();
    }

    private GameRule<?> resolveGameRule(String ruleName) {
        if (ruleName == null || ruleName.isBlank()) {
            return null;
        }

        String normalizedRuleName = normalizeGameRuleName(ruleName);

        for (Field field : GameRule.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !GameRule.class.isAssignableFrom(field.getType())) {
                continue;
            }

            try {
                GameRule<?> rule = (GameRule<?>) field.get(null);
                if (rule != null && normalizeGameRuleName(field.getName()).equals(normalizedRuleName)) {
                    return rule;
                }
            } catch (IllegalAccessException ignored) {
            }
        }

        return null;
    }

    private String normalizeGameRuleName(String ruleName) {
        return ruleName == null ? "" : ruleName.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private void applyWorldSettings(World world) {
        if (world == null) return;
        String worldName = world.getName();
        if (dataConfig.contains("worlds." + worldName + ".mobspawns")) {
            boolean mobspawns = dataConfig.getBoolean("worlds." + worldName + ".mobspawns", true);
            world.setSpawnFlags(true, mobspawns);
        }
        if (dataConfig.contains("worlds." + worldName + ".gamerules")) {
            ConfigurationSection rules = dataConfig.getConfigurationSection("worlds." + worldName + ".gamerules");
            for (String key : rules.getKeys(false)) {
                GameRule<?> rule = resolveGameRule(key);
                if (rule == null) continue;
                String value = String.valueOf(rules.get(key));
                try {
                    if (rule.getType() == Boolean.class) {
                        world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(value));
                    } else if (rule.getType() == Integer.class) {
                        world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(value));
                    } else if (rule.getType() == String.class) {
                        world.setGameRule((GameRule<String>) rule, value);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (dataConfig.contains("worlds." + worldName + ".gamemode")) {
            String gm = dataConfig.getString("worlds." + worldName + ".gamemode", "");
            try {
                if (!gm.isEmpty()) {
                    GameMode mode = GameMode.valueOf(gm.toUpperCase());
                    for (Player p : world.getPlayers()) {
                        p.setGameMode(mode);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // Locks are already handled elsewhere via worldlocks events.
    }

    private void applyPlayerGamemodeForWorld(Player p, World world) {
        if (p == null || world == null) return;
        String worldName = world.getName();
        if (!dataConfig.contains("worlds." + worldName + ".gamemode")) return;
        String gm = dataConfig.getString("worlds." + worldName + ".gamemode", "");
        if (gm == null || gm.isEmpty()) return;
        try {
            GameMode mode = GameMode.valueOf(gm.toUpperCase());
            p.setGameMode(mode);
        } catch (Exception ignored) {
        }
    }

    private String generateHologramId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String styleHologramText(String text, ChatColor color) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.BOLD).append(color);
        boolean inNumber = false;
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                if (!inNumber) {
                    sb.append(ChatColor.WHITE);
                    inNumber = true;
                }
                sb.append(c);
            } else {
                if (inNumber) {
                    sb.append(ChatColor.BOLD).append(color);
                    inNumber = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String normalizeVersion(String v) {
        if (v == null) return "";
        String[] parts = v.split(" ");
        if (parts.length == 0) return v;
        String candidate = parts[0];
        // Treat as dot-separated numbers, else preserve first token
        if (candidate.matches("[0-9]+(\\.[0-9]+)*")) {
            return candidate;
        }
        // fallback: keep numeric prefix if possible
        Matcher m = java.util.regex.Pattern.compile("([0-9]+(\\.[0-9]+)*)").matcher(v);
        if (m.find()) {
            return m.group(1);
        }
        return candidate;
    }

    private void saveTeleportHologram(String id, String title, String season, String worldName, boolean online, String version, Location location) {
        dataConfig.set("holograms." + id + ".type", "teleport");
        dataConfig.set("holograms." + id + ".title", title);
        dataConfig.set("holograms." + id + ".season", season);
        dataConfig.set("holograms." + id + ".world", worldName);
        String normalizedVersion = normalizeVersion(version);
        dataConfig.set("holograms." + id + ".online", online);
        dataConfig.set("holograms." + id + ".version", normalizedVersion);
        dataConfig.set("holograms." + id + ".location", location.getWorld().getName() + "," + location.getX() + "," + location.getY() + "," + location.getZ());
        List<String> lines = new ArrayList<>();
        lines.add(styleHologramText(title, ChatColor.AQUA));
        lines.add(styleHologramText(season, ChatColor.YELLOW));
        String statusPrefix = online ? styleHologramText("Online", ChatColor.GREEN) : styleHologramText("Offline", ChatColor.RED);
        lines.add(statusPrefix + ChatColor.WHITE + " (" + normalizedVersion + ")");
        dataConfig.set("holograms." + id + ".lines", lines);
        saveDataFile();

        // Force respawn updated formatted hologram where stored
        cleanupTeleportHologramEntities(id);
        Location loc = getSavedTeleportHologramLocation(id);
        if (loc != null) {
            spawnHologram(loc, lines);
        }
    }

    private Location getSavedTeleportHologramLocation(String id) {
        if (!dataConfig.contains("holograms." + id + ".location")) return null;
        String locationStr = dataConfig.getString("holograms." + id + ".location", "");
        if (locationStr.isEmpty()) return null;
        String[] parts = locationStr.split(",");
        if (parts.length != 4) return null;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void cleanupTeleportHologramEntities(String id) {
        Location loc = getSavedTeleportHologramLocation(id);
        if (loc != null) {
            cleanupTeleportHologramEntitiesAtLocation(loc);
        }
    }

    private void cleanupTeleportHologramEntitiesAtLocation(Location location) {
        if (location == null || location.getWorld() == null) return;
        for (Entity entity : location.getWorld().getNearbyEntities(location, 1.5, 3.0, 1.5)) {
            if (!(entity instanceof ArmorStand)) continue;
            ArmorStand as = (ArmorStand) entity;
            if (!as.isMarker() || as.isVisible()) continue;
            String name = as.getCustomName();
            if (name == null || name.isEmpty()) continue;
            as.remove();
        }
    }

    private boolean setCitizenNpcNameTagVisibility(Player p, String npcIdStr, boolean visible) {
        try {
            int npcId = Integer.parseInt(npcIdStr);
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Method getRegistry = citizensApi.getMethod("getNPCRegistry");
            Object registry = getRegistry.invoke(null);
            Class<?> registryClass = Class.forName("net.citizensnpcs.api.npc.NPCRegistry");
            Method getById = registryClass.getMethod("getById", int.class);
            Object npc = getById.invoke(registry, npcId);
            if (npc == null) return false;

            // Try using NametagTrait if available
            boolean applied = false;
            try {
                Class<?> nametagTraitClass = Class.forName("net.citizensnpcs.api.trait.trait.NametagTrait");
                Method getTrait = npc.getClass().getMethod("getTrait", Class.class);
                Object trait = getTrait.invoke(npc, nametagTraitClass);
                if (trait != null) {
                    try {
                        Method setVisible = nametagTraitClass.getMethod("setVisible", boolean.class);
                        setVisible.invoke(trait, visible);
                        applied = true;
                    } catch (NoSuchMethodException ignored) {
                    }
                    try {
                        Method setAlwaysVisible = nametagTraitClass.getMethod("setAlwaysVisible", boolean.class);
                        setAlwaysVisible.invoke(trait, visible);
                        applied = true;
                    } catch (NoSuchMethodException ignored) {
                    }
                    try {
                        Method setNameVisible = nametagTraitClass.getMethod("setNameVisible", boolean.class);
                        setNameVisible.invoke(trait, visible);
                        applied = true;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (Throwable ignored) {
                // trait not available, fallback to entity name visibility
            }

            try {
                Method getEntity = npc.getClass().getMethod("getEntity");
                Object ent = getEntity.invoke(npc);
                if (ent instanceof Entity) {
                    org.bukkit.entity.Entity entity = (org.bukkit.entity.Entity) ent;
                    entity.setCustomNameVisible(visible);
                    if (!visible) {
                        entity.setCustomName(null);
                    } else {
                        String npcName = null;
                        try {
                            Method getName = npc.getClass().getMethod("getName");
                            Object nameObj = getName.invoke(npc);
                            if (nameObj != null) npcName = nameObj.toString();
                        } catch (Throwable ignored) {
                        }
                        if (npcName != null) {
                            entity.setCustomName(npcName);
                        }
                    }
                    applied = true;
                }
            } catch (Throwable ignored) {
            }

            // Fallback via NPC data storage (Citizens 2.0+)
            try {
                Method dataMethod = npc.getClass().getMethod("data");
                Object dataObj = dataMethod.invoke(npc);
                if (dataObj != null) {
                    Method setMethod = null;
                    try {
                        setMethod = dataObj.getClass().getMethod("set", String.class, Object.class);
                    } catch (NoSuchMethodException ignored) {
                        // no setter available
                    }
                    if (setMethod != null) {
                        String[] keys = {"nametag-visible", "nametag-always-visible", "nametag", "nameplate-visible", "nametag-visibility"};
                        for (String key : keys) {
                            try {
                                setMethod.invoke(dataObj, key, visible);
                                applied = true;
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    try {
                        Method getMethod = dataObj.getClass().getMethod("get", String.class);
                        for (String key : new String[]{"nametag-visible", "nametag-always-visible", "nameplate-visible"}) {
                            try {
                                Object val = getMethod.invoke(dataObj, key);
                                if (val != null) {
                                    applied = true;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }

            if (!applied) {
                p.sendMessage(ChatColor.YELLOW + "Could not apply NPC nametag visibility using Citizens API. If using non-standard Citizens version, this may not be supported.");
            }

            return true;
        } catch (Exception ex) {
            p.sendMessage(ChatColor.RED + "Error setting NPC name visibility: " + ex.getMessage());
            getLogger().log(Level.WARNING, "Failed to set NPC name visibility for id " + npcIdStr, ex);
            return false;
        }
    }

    private void deleteTeleportHologram(String id, Player p) {
        if (id == null || id.isEmpty()) return;
        cleanupTeleportHologramEntities(id);
        dataConfig.set("holograms." + id, null);
        dataConfig.set("holograms." + id + ".lines", null);
        dataConfig.set("holograms." + id + ".type", null);
        dataConfig.set("holograms." + id + ".title", null);
        dataConfig.set("holograms." + id + ".world", null);
        dataConfig.set("holograms." + id + ".online", null);
        dataConfig.set("holograms." + id + ".version", null);
        dataConfig.set("holograms." + id + ".location", null);
        if (id.equals(selectedHologram.get(p.getUniqueId()))) {
            selectedHologram.remove(p.getUniqueId());
        }
        saveDataFile();
        p.sendMessage(ChatColor.GREEN + "Hologram " + id + " deleted (crouch+wand)." );
    }

    private String findTeleportHologramIdAtLocation(Location location) {
        if (location == null || !dataConfig.contains("holograms")) return null;
        for (String id : dataConfig.getConfigurationSection("holograms").getKeys(false)) {
            String type = dataConfig.getString("holograms." + id + ".type", "");
            if (!type.equalsIgnoreCase("teleport")) continue;
            String locStr = dataConfig.getString("holograms." + id + ".location", "");
            if (locStr.isEmpty()) continue;
            String[] parts = locStr.split(",");
            if (parts.length != 4) continue;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null || location.getWorld() == null || !world.equals(location.getWorld())) continue;
            try {
                double x = Double.parseDouble(parts[1]);
                double y = Double.parseDouble(parts[2]);
                double z = Double.parseDouble(parts[3]);
                Location saved = new Location(world, x, y, z);
                if (saved.distance(location) < 0.75) {
                    return id;
                }
            } catch (NumberFormatException ex) {
                // ignore malformed location
            }
        }
        return null;
    }

    private void spawnHologram(Location base, List<String> lines) {
        World world = base.getWorld();
        if (world == null || lines == null) return;
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

    private void spawnHologram(Player p, List<String> lines) {
        spawnHologram(p.getLocation().add(0, 1.6, 0), lines);
    }

    private void loadTeleportHologram(String id, Player p) {
        if (!dataConfig.contains("holograms." + id)) return;
        List<String> lines = dataConfig.getStringList("holograms." + id + ".lines");
        if (!lines.isEmpty()) {
            spawnHologram(p, lines);
        }
    }

    private void refreshTeleportHologramsForWorld(String worldName) {
        if (!dataConfig.contains("holograms")) return;
        boolean worldExists = Bukkit.getWorld(worldName) != null;
        boolean worldLocked = dataConfig.getBoolean("worldlocks." + worldName, false);
        boolean online = worldExists && !worldLocked;
        String worldVersion = normalizeVersion(Bukkit.getBukkitVersion());

        for (String hologramId : dataConfig.getConfigurationSection("holograms").getKeys(false)) {
            String type = dataConfig.getString("holograms." + hologramId + ".type", "");
            if (!type.equalsIgnoreCase("teleport")) continue;
            String targetWorld = dataConfig.getString("holograms." + hologramId + ".world", "");
            if (!worldName.equals(targetWorld)) continue;

            dataConfig.set("holograms." + hologramId + ".online", online);
            dataConfig.set("holograms." + hologramId + ".version", worldVersion);

            String title = dataConfig.getString("holograms." + hologramId + ".title", "");
            String season = dataConfig.getString("holograms." + hologramId + ".season", "");

            List<String> lines = new ArrayList<>();
            lines.add(styleHologramText(title, ChatColor.AQUA));
            lines.add(styleHologramText(season, ChatColor.YELLOW));
            String statusPrefix = online ? styleHologramText("Online", ChatColor.GREEN) : styleHologramText("Offline", ChatColor.RED);
            lines.add(statusPrefix + ChatColor.WHITE + " (" + worldVersion + ")");

            dataConfig.set("holograms." + hologramId + ".lines", lines);
        }
        saveDataFile();
    }

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

    public List<Map<String, Object>> getStaffHourSummaryData() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StaffHourSummary summary : buildStaffHourSummaries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", summary.uuid.toString());
            row.put("name", summary.name);
            row.put("minutes24h", summary.minutes24h);
            row.put("minutes7d", summary.minutes7d);
            row.put("minutes14d", summary.minutes14d);
            row.put("hours24h", Math.round((summary.minutes24h / 60.0D) * 100.0D) / 100.0D);
            row.put("hours7d", Math.round((summary.minutes7d / 60.0D) * 100.0D) / 100.0D);
            row.put("hours14d", Math.round((summary.minutes14d / 60.0D) * 100.0D) / 100.0D);
            rows.add(row);
        }
        return rows;
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
        addWarning(uuid, reason, null);
    }

    public void addWarning(UUID uuid, String reason, String issuedBy) {
        NetworkWarning warning = new NetworkWarning(
            uuid,
            0,
            reason == null || reason.isBlank() ? "No reason" : reason.trim(),
            issuedBy == null || issuedBy.isBlank() ? null : issuedBy.trim(),
            System.currentTimeMillis()
        );
        if (networkModerationService != null) {
            networkModerationService.addWarning(warning);
            invalidateModerationAggregateCaches();
            return;
        }
        addLocalWarning(warning);
        invalidateModerationAggregateCaches();
    }

    public List<NetworkWarning> getWarnings(UUID uuid) {
        return new ArrayList<>(networkModerationService != null ? networkModerationService.getWarnings(uuid) : getLocalWarnings(uuid));
    }

    public Map<UUID, List<NetworkWarning>> getAllWarnings() {
        long now = System.currentTimeMillis();
        CachedWarningsSnapshot cachedSnapshot = warningsSnapshotCache;
        if (cachedSnapshot != null && cachedSnapshot.expiresAt() > now) {
            return copyWarningsSnapshot(cachedSnapshot.warnings());
        }

        Map<UUID, List<NetworkWarning>> warnings = networkModerationService != null ? networkModerationService.getAllWarnings() : getAllLocalWarnings();
        Map<UUID, List<NetworkWarning>> snapshot = copyWarningsSnapshot(warnings);
        warningsSnapshotCache = new CachedWarningsSnapshot(snapshot, now + MODERATION_AGGREGATE_CACHE_TTL_MS);
        return copyWarningsSnapshot(snapshot);
    }

    private void invalidateModerationAggregateCaches() {
        warningsSnapshotCache = null;
        activePunishmentRecordsSnapshotCache = null;
    }

    private Map<UUID, List<NetworkWarning>> copyWarningsSnapshot(Map<UUID, List<NetworkWarning>> warnings) {
        Map<UUID, List<NetworkWarning>> copy = new HashMap<>();
        for (Map.Entry<UUID, List<NetworkWarning>> entry : warnings.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? List.of() : new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    public int getWarningCount(UUID uuid) {
        return networkModerationService != null ? networkModerationService.getWarningCount(uuid) : getLocalWarnings(uuid).size();
    }

    public List<NetworkWarning> getLocalWarnings(UUID uuid) {
        List<String> rawWarnings = dataConfig.getStringList("warnings." + uuid);
        List<NetworkWarning> warnings = new ArrayList<>();
        for (int index = 0; index < rawWarnings.size(); index++) {
            String rawWarning = rawWarnings.get(index);
            String reason = rawWarning;
            String issuedBy = null;
            long createdAt = 0L;

            if (rawWarning != null && rawWarning.contains(" | ")) {
                String[] parts = rawWarning.split(" \\| ", 3);
                if (parts.length == 3) {
                    createdAt = parseStoredWarningTimestamp(parts[0]);
                    issuedBy = parts[1].isBlank() ? null : parts[1].trim();
                    reason = parts[2].trim();
                } else if (parts.length == 2) {
                    createdAt = parseStoredWarningTimestamp(parts[0]);
                    reason = parts[1].trim();
                }
            }

            warnings.add(new NetworkWarning(uuid, index + 1, reason, issuedBy, createdAt));
        }
        return warnings;
    }

    public Map<UUID, List<NetworkWarning>> getAllLocalWarnings() {
        Map<UUID, List<NetworkWarning>> warningsByPlayer = new HashMap<>();
        ConfigurationSection section = dataConfig.getConfigurationSection("warnings");
        if (section == null) {
            return warningsByPlayer;
        }

        for (String uuidStr : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                List<NetworkWarning> warnings = getLocalWarnings(uuid);
                if (!warnings.isEmpty()) {
                    warningsByPlayer.put(uuid, warnings);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return warningsByPlayer;
    }

    public void addLocalWarning(NetworkWarning warning) {
        List<String> warns = new ArrayList<>(dataConfig.getStringList("warnings." + warning.uuid()));
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(warning.createdAt() > 0L ? warning.createdAt() : System.currentTimeMillis()));
        String entry = warning.issuedBy() == null || warning.issuedBy().isBlank()
            ? timestamp + " | " + warning.reason()
            : timestamp + " | " + warning.issuedBy().trim() + " | " + warning.reason();
        warns.add(entry);
        dataConfig.set("warnings." + warning.uuid(), warns);
        saveDataFile();
    }

    private long parseStoredWarningTimestamp(String rawTimestamp) {
        if (rawTimestamp == null || rawTimestamp.isBlank()) {
            return 0L;
        }

        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(rawTimestamp.trim()).getTime();
        } catch (Exception ignored) {
            return 0L;
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
            if (!chunksCornerBlocks.containsKey(key)) {
                chunksCornerBlocks.put(key, corner.getBlock().getType());
            }
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
        int baseLimit = getConfig().getInt("factions_world.claims.base_limit", 16);
        int perHour = getConfig().getInt("factions_world.claims.per_playtime_hour", 1);
        return Math.max(0, baseLimit + (hours * perHour));
    }

    private List<String> getClaimedChunks(UUID uuid) {
        return getClaimList(uuid);
    }

    private List<String> getClaimedChunks(UUID uuid, String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return getClaimedChunks(uuid);
        }

        String prefix = worldName + ":";
        return getClaimList(uuid).stream()
            .filter(chunkKey -> chunkKey != null && chunkKey.startsWith(prefix))
            .toList();
    }

    private String getChunkWorldName(String chunkKey) {
        if (chunkKey == null || chunkKey.isBlank()) {
            return "";
        }

        int separatorIndex = chunkKey.indexOf(':');
        if (separatorIndex <= 0) {
            return "";
        }

        return chunkKey.substring(0, separatorIndex);
    }

    private void claimChunk(UUID uuid, String chunkKey) {
        UUID owner = getChunkOwner(chunkKey);
        if (owner != null) {
            // Already claimed by someone
            if (owner.equals(uuid)) return; // no-op for already-owned chunk
            return;
        }

        UUID nearbyOwner = getNearbyClaimOwner(chunkKey, CLAIM_PROXIMITY_RADIUS);
        if (nearbyOwner != null && !nearbyOwner.equals(uuid)) {
            return; // no claim within radius from another player's claim
        }

        List<String> claimed = getClaimList(uuid);
        if (!claimed.contains(chunkKey)) {
            claimed.add(chunkKey);
            setClaimList(uuid, claimed);
        }
    }

    private UUID getNearbyClaimOwner(String chunkKey, int radius) {
        String[] parts = chunkKey.split(":");
        if (parts.length != 3) return null;

        String worldName = parts[0];
        int targetX = Integer.parseInt(parts[1]);
        int targetZ = Integer.parseInt(parts[2]);
        for (Map.Entry<String, UUID> entry : getClaimOwnerIndex().entrySet()) {
            String[] cParts = entry.getKey().split(":");
            if (cParts.length != 3) continue;
            if (!worldName.equals(cParts[0])) continue;

            int chunkX;
            int chunkZ;
            try {
                chunkX = Integer.parseInt(cParts[1]);
                chunkZ = Integer.parseInt(cParts[2]);
            } catch (NumberFormatException ignored) {
                continue;
            }

            int dx = Math.abs(chunkX - targetX);
            int dz = Math.abs(chunkZ - targetZ);
            if (dx <= radius && dz <= radius) {
                if (chunkX == targetX && chunkZ == targetZ) continue;
                return entry.getValue();
            }
        }

        return null;
    }

    private void unclaimChunk(UUID uuid, String chunkKey) {
        UUID owner = getChunkOwner(chunkKey);
        if (owner == null || !owner.equals(uuid)) {
            return; // only owner can unclaim this chunk
        }

        List<String> claimed = getClaimList(uuid);
        claimed.remove(chunkKey);
        setClaimList(uuid, claimed);
    }

    private void trustPlayer(UUID owner, String worldName, String trustedName) {
        List<String> trusted = new ArrayList<>(getTrustedList(owner, worldName));
        if (!trusted.contains(trustedName)) {
            trusted.add(trustedName);
            setTrustedList(owner, worldName, trusted);
        }
    }

    private void untrustPlayer(UUID owner, String worldName, String trustedName) {
        List<String> trusted = new ArrayList<>(getTrustedList(owner, worldName));
        trusted.remove(trustedName);
        setTrustedList(owner, worldName, trusted);
    }

    private boolean isChunkClaimed(String chunkKey) {
        return getClaimOwnerIndex().containsKey(chunkKey);
    }

    private UUID getChunkOwner(String chunkKey) {
        return getClaimOwnerIndex().get(chunkKey);
    }

    private boolean isTrustedInChunk(Player p, String chunkKey) {
        // admins should be able to bypass claim restrictions entirely
        if (p.hasPermission("dmt.admin") || p.hasPermission("claims.override")) {
            return true;
        }

        UUID owner = getChunkOwner(chunkKey);
        if (owner == null) return true;
        if (p.getUniqueId().equals(owner)) return true;

        List<String> trusted = getTrustedList(owner, getChunkWorldName(chunkKey));
        return trusted.contains(p.getName());
    }

    private void startPlaytimeTracker() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            long currentEpochHour = getCurrentEpochHour();
            pruneOldStaffHourBuckets(currentEpochHour);
            for (Player p : Bukkit.getOnlinePlayers()) {
                String path = "playtime." + p.getUniqueId();
                long currentMinutes = dataConfig.getLong(path, 0);
                dataConfig.set(path, currentMinutes + 1);
                recordStaffActivityMinute(p, currentEpochHour);
            }
            saveDataFile();
        }, 1200L, 1200L); // Run every 60 seconds
    }

    private void startPunishmentChecker() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (activePunishmentExpiries.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Long> entry : new HashMap<>(activePunishmentExpiries).entrySet()) {
                Long expiry = entry.getValue();
                if (expiry == null || now > expiry) {
                    removePunishment(entry.getKey());
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
        if (isInvisible(p)) {
            // maintain invisible tab state and do not expose via normal rank name updates
            removePlayerFromRankTeams(p, null);
            String invisibleListName = ChatColor.GRAY + p.getName();
            if (!invisibleListName.equals(p.getPlayerListName())) {
                p.setPlayerListName(invisibleListName);
            }
            return;
        }

        String rank = getPlayerRank(p.getUniqueId());
        String group = getPlayerGroup(p.getUniqueId());
        String displayRank = rank != null ? rank : group;

        if (displayRank == null) {
            // remove any existing rank teams
            removePlayerFromRankTeams(p, null);
            // reset tab name
            if (!p.getName().equals(p.getPlayerListName())) {
                p.setPlayerListName(p.getName());
            }
            return;
        }
        String teamName = "rank_" + displayRank.toLowerCase(Locale.ROOT);
        String prefix = resolveDisplayPrefix(displayRank);
        String formatted = ChatColor.translateAlternateColorCodes('&', prefix);
        ChatColor teamColor = ChatColor.WHITE;

        try {
            String lastColors = ChatColor.getLastColors(formatted);
            for (int i = lastColors.length() - 1; i >= 0; i--) {
                ChatColor cc = ChatColor.getByChar(lastColors.charAt(i));
                if (cc != null && cc.isColor()) {
                    teamColor = cc;
                    break;
                }
            }
        } catch (Exception ignored) {}

        for (Scoreboard activeBoard : getActiveRankScoreboards()) {
            Team team = activeBoard.getTeam(teamName);
            if (team == null) {
                team = activeBoard.registerNewTeam(teamName);
                team.setCanSeeFriendlyInvisibles(true);
                team.setAllowFriendlyFire(true);
            }

            if (!team.getPrefix().equals(formatted)) {
                team.setPrefix(formatted);
            }
            if (team.getColor() != teamColor) {
                team.setColor(teamColor);
            }

            removePlayerFromRankTeams(activeBoard, p, team);
            if (!team.hasEntry(p.getName())) {
                team.addEntry(p.getName());
            }
        }

        // Also set the tab list name
        String desiredName = formatted + p.getName();
        if (!desiredName.equals(p.getPlayerListName())) {
            p.setPlayerListName(desiredName);
        }
    }

    private void removePlayerFromRankTeams(Player p, org.bukkit.scoreboard.Team keepTeam) {
        for (Scoreboard activeBoard : getActiveRankScoreboards()) {
            removePlayerFromRankTeams(activeBoard, p, keepTeam);
        }
    }

    private void removePlayerFromRankTeams(Scoreboard activeBoard, Player p, org.bukkit.scoreboard.Team keepTeam) {
        for (org.bukkit.scoreboard.Team t : activeBoard.getTeams()) {
            if (t.getName().startsWith("rank_") && t.hasEntry(p.getName())) {
                if (keepTeam == null || !t.getName().equals(keepTeam.getName())) {
                    t.removeEntry(p.getName());
                }
            }
        }
    }

    private Set<Scoreboard> getActiveRankScoreboards() {
        Set<Scoreboard> boards = new LinkedHashSet<>();
        if (scoreboard != null) {
            boards.add(scoreboard);
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            Scoreboard activeBoard = online.getScoreboard();
            if (activeBoard != null) {
                boards.add(activeBoard);
            }
        }
        return boards;
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

        if (factionService != null && factionService.isEnabled()) {
            factionService.handlePlayerDeath(victim);
        }

        // Ensure only the Drowsy tool is kept on death (all other items drop normally)
        e.setKeepInventory(false);

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

        // Clear saved inventory for the death world to prevent repopulating pre-death items on teleport back.
        World deathWorld = victim.getWorld();
        if (deathWorld != null) {
            String deathWorldName = deathWorld.getName();
            if (isWorldSeparated(deathWorldName)) {
                clearInventoryForWorld(victim, deathWorldName);
            } else {
                clearSharedInventory(victim);
            }
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
        List<String> order = Arrays.asList("enchant_timber","enchant_veinminer","enchant_smelting","enchant_telepathy", "enchant_excavator");

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

            if (!(e.getEntity() instanceof Player) && e.getEntity() instanceof Monster) {
                if (dataConfig.getBoolean("mob_coins.enabled", true)) {
                    // Add a chance roll so mobs don't drop coins 100% of the time
                    if (Math.random() < dataConfig.getDouble("mob_coins.chance", 0.1)) {
                        int min = dataConfig.getInt("mob_coins.min", 1);
                        int max = dataConfig.getInt("mob_coins.max", 2);
                        int amount = min + (max > min ? new Random().nextInt(max - min + 1) : 0);
                        if (amount > 0) {
                            addCoins(killer.getUniqueId(), amount);
                            try {
                                killer.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(ChatColor.GOLD + "+" + amount + " Coins"));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
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
        if (!dataConfig.contains("quests.enchant_excavator")) {
            dataConfig.set("quests.enchant_excavator.name", "Excavator");
            dataConfig.set("quests.enchant_excavator.description", "Mine 1500 stone blocks");
            dataConfig.set("quests.enchant_excavator.type", "mine_stone");
            dataConfig.set("quests.enchant_excavator.goal", 1500);
            dataConfig.set("quests.enchant_excavator.reward_enchant", "Excavator");
            dataConfig.set("quests.enchant_excavator.active", true);
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
        // Give the player their Drowsy Tool at join (fallback for users who lost it).
        ensurePlayerHasTool(p);

        // determine first pending enchant quest and notify
        List<String> order = Arrays.asList("Timber", "Vein Miner", "Smelting Touch", "Telepathy", "Excavator");
        for (String ench : order) {
            if (!hasUnlockedEnchant(p, ench)) {
                p.sendMessage(ChatColor.AQUA + "Quest available: earn the " + ench + " enchant! Use /quest to view.");
                break;
            }
        }
    }

    @EventHandler
    public void onBlockBreakEnchant(BlockBreakEvent e) {
        if (e.isCancelled()) return;
        Player p = e.getPlayer();

        String chunkKey = getChunkKey(e.getBlock().getLocation());
        if (shouldProtectClaim(chunkKey) && !isTrustedInChunk(p, chunkKey)) {
            return;
        }

        if (isPunished(p.getUniqueId())) return;

        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || !held.hasItemMeta() || !held.getItemMeta().hasLore()) return;

        List<String> lore = held.getItemMeta().getLore();
        for (String line : lore) {
            String stripped = ChatColor.stripColor(line).trim();
            // Timber enchant - break logs/stems in column (including nether tree stems)
            Material brokenType = e.getBlock().getType();
            boolean isTimberTarget = brokenType.name().contains("LOG") || brokenType.name().contains("STEM");
            if (stripped.equalsIgnoreCase("Timber") && isTimberTarget) {
                Location loc = e.getBlock().getLocation();
                for (int y = 1; y <= 20; y++) {
                    Location above = loc.clone().add(0, y, 0);
                    Material aboveType = above.getBlock().getType();
                    if (aboveType.name().contains("LOG") || aboveType.name().contains("STEM")) {
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
            // Excavator enchant - 3x3 area for pickaxes
            if (stripped.equalsIgnoreCase("Excavator") && held.getType().name().endsWith("_PICKAXE")) {
                org.bukkit.util.RayTraceResult trace = p.rayTraceBlocks(6.0, org.bukkit.FluidCollisionMode.NEVER);
                BlockFace face;
                if (trace != null && trace.getHitBlockFace() != null) {
                    face = trace.getHitBlockFace();
                } else {
                    float pitch = p.getLocation().getPitch();
                    if (pitch < -45) face = BlockFace.UP;
                    else if (pitch > 45) face = BlockFace.DOWN;
                    else face = p.getFacing().getOppositeFace();
                }
                Location center = e.getBlock().getLocation();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int xOffset = 0, yOffset = 0, zOffset = 0;
                        if (face == BlockFace.UP || face == BlockFace.DOWN) { xOffset = dx; zOffset = dz; }
                        else if (face == BlockFace.NORTH || face == BlockFace.SOUTH) { xOffset = dx; yOffset = dz; }
                        else if (face == BlockFace.EAST || face == BlockFace.WEST) { zOffset = dx; yOffset = dz; }
                        Block b = center.clone().add(xOffset, yOffset, zOffset).getBlock();
                        if (b.getType() != Material.BEDROCK && b.getType() != Material.BARRIER && b.getType() != Material.END_PORTAL_FRAME && b.getType() != Material.END_PORTAL && b.getType() != Material.COMMAND_BLOCK && b.getType() != Material.AIR) {
                            String bChunk = getChunkKey(b.getLocation());
                            if (!shouldProtectClaim(bChunk) || isTrustedInChunk(p, bChunk)) {
                                b.breakNaturally(held);
                            }
                        }
                    }
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
        
        if (enchantName.equalsIgnoreCase("Excavator") && !item.getType().name().endsWith("_PICKAXE")) {
            return false;
        }
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

    @EventHandler
    public void onPlayerFish(org.bukkit.event.player.PlayerFishEvent e) {
        if (e.getState() == org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH) {
            UUID uuid = e.getPlayer().getUniqueId();
            int caught = dataConfig.getInt("fish_caught." + uuid, 0);
            dataConfig.set("fish_caught." + uuid, caught + 1);
            saveDataFile();
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
        enforcePwarpLimit(p.getUniqueId());

        Inventory gui = Bukkit.createInventory(null, 54, GUI_PWARP_LIST);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
        resetGridSlots();

        int limit = getPwarpLimit(p.getUniqueId());
        List<String> ownedWarps = new ArrayList<>();
        FileConfiguration pwarpCfg = getPwarpConfig();
        if (pwarpCfg.contains("pwarps")) {
            for (String wId : pwarpCfg.getConfigurationSection("pwarps").getKeys(false)) {
                String wPath = "pwarps." + wId;
                if (p.getUniqueId().toString().equals(pwarpCfg.getString(wPath + ".owner", ""))) {
                    ownedWarps.add(wId);
                }
            }
        }

        Collections.sort(ownedWarps);
        if (ownedWarps.size() > limit) {
            int removed = prunePwarpsToLimit(p.getUniqueId(), limit);
            if (removed > 0) {
                p.sendMessage(ChatColor.YELLOW + "Your player warp slots were reduced to " + limit + ", " + removed + " old warps were removed.");
            }
            // refresh list after pruning
            ownedWarps.clear();
            pwarpCfg = getPwarpConfig();
            if (pwarpCfg.contains("pwarps")) {
                for (String wId : pwarpCfg.getConfigurationSection("pwarps").getKeys(false)) {
                    String wPath = "pwarps." + wId;
                    if (p.getUniqueId().toString().equals(pwarpCfg.getString(wPath + ".owner", ""))) {
                        ownedWarps.add(wId);
                    }
                }
            }
            Collections.sort(ownedWarps);
        }

        int showCount = Math.min(limit, ownedWarps.size());
        for (int i = 0; i < showCount; i++) {
            String wId = ownedWarps.get(i);
            String wPath = "pwarps." + wId;
            String name = pwarpCfg.getString(wPath + ".name", wId);
            String owner = pwarpCfg.getString(wPath + ".ownerName", "Unknown");
            int visits = pwarpCfg.getInt(wPath + ".visits", 0);
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
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Close");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        p.openInventory(gui);
    }

    private void openPwarpManageGUI(Player p, String warpId) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_PWARP_MANAGE + warpId);
        fillGUIBorders(gui);
        fillGUIEmpty(gui);

        FileConfiguration pwarpCfg = getPwarpConfig();
        String warpName = pwarpCfg.getString("pwarps." + warpId + ".name", warpId);
        gui.setItem(10, createGuiItem(Material.ENDER_PEARL, ChatColor.GREEN + "Teleport to " + warpName));
        gui.setItem(13, createGuiItem(Material.REDSTONE, ChatColor.RED + "Delete Warp"));
        gui.setItem(16, createGuiItem(Material.BARRIER, ChatColor.YELLOW + "Back"));

        p.openInventory(gui);
    }

    private int getPwarpLimit(UUID uuid) {
        String key = "pwarp_slots." + uuid;
        return dataConfig.getInt(key, 3);
    }

    private void setPwarpLimit(UUID uuid, int limit) {
        if (limit < 0) limit = 0;
        dataConfig.set("pwarp_slots." + uuid, limit);
        saveDataFile();
    }

    private int getPwarpCount(UUID uuid) {
        int count = 0;
        FileConfiguration pwarpCfg = getPwarpConfig();
        if (!pwarpCfg.contains("pwarps")) return 0;
        for (String id : pwarpCfg.getConfigurationSection("pwarps").getKeys(false)) {
            if (pwarpCfg.getString("pwarps." + id + ".owner", "").equals(uuid.toString())) count++;
        }
        return count;
    }

    private void enforcePwarpLimit(UUID uuid) {
        int limit = getPwarpLimit(uuid);
        int count = getPwarpCount(uuid);
        if (count <= limit) return;
        int removed = prunePwarpsToLimit(uuid, limit);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && removed > 0) {
            player.sendMessage(ChatColor.RED + "Your playerwarp slots were reduced to " + limit + ". " + removed + " warps were removed.");
        }
    }

    private int prunePwarpsToLimit(UUID uuid, int limit) {
        if (limit < 0) limit = 0;
        FileConfiguration pwarpCfg = getPwarpConfig();
        List<String> owned = new ArrayList<>();
        if (pwarpCfg.contains("pwarps")) {
            for (String id : pwarpCfg.getConfigurationSection("pwarps").getKeys(false)) {
                if (pwarpCfg.getString("pwarps." + id + ".owner", "").equals(uuid.toString())) {
                    owned.add(id);
                }
            }
        }
        if (owned.size() <= limit) return 0;
        int removed = 0;
        Collections.sort(owned);
        for (int i = 0; i < owned.size() - limit; i++) {
            pwarpCfg.set("pwarps." + owned.get(i), null);
            if (pwarpCfg == dataConfig) {
                saveDataFile();
            } else {
                savePlayersFile();
            }
            removed++;
        }
        return removed;
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
            
            List<Map<String, Object>> cleanList = new ArrayList<>();
            for (Map<?, ?> entry : raw) {
                Map<String, Object> cleanEntry = new HashMap<>();
                for (Map.Entry<?, ?> e : entry.entrySet()) {
                    if (e.getValue() != null) cleanEntry.put(String.valueOf(e.getKey()), e.getValue());
                }
                Object sentObj = cleanEntry.get("sent");
                boolean sent = sentObj instanceof Boolean && (Boolean) sentObj;
                if (!sent) {
                    String timeStr = String.valueOf(cleanEntry.get("time"));
                    try {
                        // Parse ISO local datetime (yyyy-MM-ddTHH:mm)
                        long targetMs = java.time.LocalDateTime.parse(timeStr).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                        if (now >= targetMs) {
                            String message = ChatColor.translateAlternateColorCodes('&', String.valueOf(cleanEntry.get("message")));
                            Bukkit.broadcastMessage(prefix + message);
                            cleanEntry.put("sent", true);
                            changed = true;
                        }
                    } catch (Exception ignored) {}
                }
                cleanList.add(cleanEntry);
            }
            if (changed) {
                dataConfig.set("announcements.scheduled", cleanList);
                saveDataFile();
            }
        }, 600L, 600L);

        // Command scheduler checker (every 30 seconds = 600 ticks)
        scheduledCommandsTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            List<Map<?, ?>> raw = (List<Map<?, ?>>) dataConfig.getList("scheduler.commands", new ArrayList<>());
            if (raw.isEmpty()) return;
            boolean changed = false;
            long now = System.currentTimeMillis();
            
            List<Map<String, Object>> cleanList = new ArrayList<>();
            for (Map<?, ?> entry : raw) {
                Map<String, Object> cleanEntry = new HashMap<>();
                for (Map.Entry<?, ?> e : entry.entrySet()) {
                    if (e.getValue() != null) cleanEntry.put(String.valueOf(e.getKey()), e.getValue());
                }
                Object sentObj = cleanEntry.get("sent");
                boolean sent = sentObj instanceof Boolean && (Boolean) sentObj;
                if (!sent) {
                    String timeStr = String.valueOf(cleanEntry.get("time"));
                    try {
                        long targetMs = java.time.LocalDateTime.parse(timeStr).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                        if (now >= targetMs) {
                            String command = String.valueOf(cleanEntry.get("command"));
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                            cleanEntry.put("sent", true);
                            changed = true;
                            logAction("System", "scheduled_cmd", command);
                        }
                    } catch (Exception ignored) {}
                }
                cleanList.add(cleanEntry);
            }
            if (changed) {
                dataConfig.set("scheduler.commands", cleanList);
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

    private void startLeaderboardUpdater() {
        if (leaderboardUpdaterTaskId != -1) {
            Bukkit.getScheduler().cancelTask(leaderboardUpdaterTaskId);
            leaderboardUpdaterTaskId = -1;
        }
        leaderboardUpdaterTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::updateLeaderboards, 100L, 6000L); // delay 5s, repeat 5m
    }

    private void updateLeaderboards() {
        if (!dataConfig.contains("leaderboards")) return;
        for (String lbId : dataConfig.getConfigurationSection("leaderboards").getKeys(false)) {
            String type = dataConfig.getString("leaderboards." + lbId + ".type");
            Location loc = getLoc("leaderboards." + lbId + ".location");
            if (loc == null || loc.getWorld() == null) continue;

            // Skip if chunk not loaded
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                continue;
            }

            List<String> lines = generateLeaderboardLines(type);

            List<String> oldStandUuids = dataConfig.getStringList("leaderboards." + lbId + ".stands");
            for (String uuidStr : oldStandUuids) {
                try {
                    Entity e = Bukkit.getEntity(UUID.fromString(uuidStr));
                    if (e != null) e.remove();
                } catch (Exception ignored) {}
            }
            
            // Cleanup any orphaned armor stands nearby with our specific leaderboard names to be safe
            for (Entity e : loc.getWorld().getNearbyEntities(loc, 2.0, 4.0, 2.0)) {
                if (e instanceof ArmorStand && e.getCustomName() != null) {
                    if (e.getCustomName().contains("Top 10") || e.getCustomName().contains("#1.") || e.getCustomName().contains("#2.")) {
                        e.remove();
                    }
                }
            }

            List<String> newStandUuids = new ArrayList<>();
            Location base = loc.clone().add(0, 2.5, 0); // Give it some height
            double spacing = 0.3;
            for (int i = 0; i < lines.size(); i++) {
                Location lineLoc = base.clone().add(0, -i * spacing, 0);
                ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(lineLoc, org.bukkit.entity.EntityType.ARMOR_STAND);
                as.setVisible(false);
                as.setGravity(false);
                as.setMarker(true);
                as.setInvulnerable(true);
                as.setCustomName(ChatColor.translateAlternateColorCodes('&', lines.get(i)));
                as.setCustomNameVisible(true);
                newStandUuids.add(as.getUniqueId().toString());
            }
            dataConfig.set("leaderboards." + lbId + ".stands", newStandUuids);
        }
        saveDataFile();
    }

    private List<String> generateLeaderboardLines(String type) {
        List<String> lines = new ArrayList<>();
        Map<String, Long> scores = new HashMap<>();

        if (type.equals("coins")) {
            lines.add("&e&l★ Top 10 Wealthiest Players ★");
            if (economyConfig.contains("coins")) {
                for (String uuidStr : economyConfig.getConfigurationSection("coins").getKeys(false)) {
                    long coins = economyConfig.getLong("coins." + uuidStr, 0);
                    String name = dataConfig.getString("last_seen_name." + uuidStr, "Unknown");
                    scores.put(name, coins);
                }
            }
        } else if (type.equals("playtime")) {
            lines.add("&b&l★ Top 10 Most Playtime ★");
            if (dataConfig.contains("playtime")) {
                for (String uuidStr : dataConfig.getConfigurationSection("playtime").getKeys(false)) {
                    long mins = dataConfig.getLong("playtime." + uuidStr, 0);
                    String name = dataConfig.getString("last_seen_name." + uuidStr, "Unknown");
                    scores.put(name, mins / 60); // hours
                }
            }
        } else if (type.equals("kills")) {
            lines.add("&c&l★ Top 10 Most Kills ★");
            if (dataConfig.contains("pvpstats")) {
                for (String uuidStr : dataConfig.getConfigurationSection("pvpstats").getKeys(false)) {
                    long kills = dataConfig.getLong("pvpstats." + uuidStr + ".kills", 0);
                    String name = dataConfig.getString("last_seen_name." + uuidStr, "Unknown");
                    scores.put(name, kills);
                }
            }
        } else if (type.equals("fish")) {
            lines.add("&9&l★ Top 10 Most Fish Caught ★");
            if (dataConfig.contains("fish_caught")) {
                for (String uuidStr : dataConfig.getConfigurationSection("fish_caught").getKeys(false)) {
                    long caught = dataConfig.getLong("fish_caught." + uuidStr, 0);
                    String name = dataConfig.getString("last_seen_name." + uuidStr, "Unknown");
                    scores.put(name, caught);
                }
            }
        }

        List<Map.Entry<String, Long>> sortedList = new ArrayList<>(scores.entrySet());
        sortedList.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        int rank = 1;
        for (int i = 0; i < Math.min(10, sortedList.size()); i++) {
            Map.Entry<String, Long> entry = sortedList.get(i);
            String valStr = entry.getValue().toString();
            if (type.equals("playtime")) valStr += " hrs";
            if (type.equals("coins")) valStr += " coins";
            if (type.equals("kills")) valStr += " kills";
            if (type.equals("fish")) valStr += " fish";
            
            String color = rank == 1 ? "&6" : rank == 2 ? "&7" : rank == 3 ? "&c" : "&f";
            String prefix = color + "#" + rank + ". &e" + entry.getKey() + " &8- &a" + valStr;
            lines.add(prefix);
            rank++;
        }

        if (scores.isEmpty()) {
            lines.add("&7No data yet.");
        }

        return lines;
    }

}

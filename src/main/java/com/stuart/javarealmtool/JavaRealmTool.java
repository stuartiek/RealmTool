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
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class JavaRealmTool extends JavaPlugin implements Listener {

    private File dataFile;
    private FileConfiguration dataConfig;
    private Scoreboard scoreboard;
    private Team punishTeam;
    private WebServer webServer;
    private String apiKey;
    private final Map<UUID, PunishmentContext> pendingActions = new HashMap<>();
    private final Map<String, Integer> pendingNoteEdit = new HashMap<>();
    private final Map<UUID, UUID> tpaRequests = new HashMap<>();
    private final Map<UUID, String> pendingWarpDelete = new HashMap<>();
    private final Map<UUID, String> pendingClaimAction = new HashMap<>();
    private final Map<UUID, String> pendingTrustAction = new HashMap<>();
    private final Map<UUID, Integer> menuPages = new HashMap<>();
    private final Map<UUID, String> currentChunk = new HashMap<>();
    private final Map<String, Material> chunksCornerBlocks = new HashMap<>();
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
    private final String GUI_WARP_MANAGEMENT = ChatColor.BLUE + "Manage Warp: ";
    private final String GUI_CLAIMS = ChatColor.BLUE + "Chunk Claims";
    private final String GUI_CLAIM_CONFIRM = ChatColor.YELLOW + "Confirm Claim";
    private final String GUI_UNCLAIM_CONFIRM = ChatColor.YELLOW + "Confirm Unclaim";
    private final String GUI_TRUST_PLAYER = ChatColor.BLUE + "Trust Player";
    private final String GUI_UNTRUST_PLAYER = ChatColor.BLUE + "Remove Trusted";

    private enum ActionType { KICK, BAN, WARN, ANNOUNCE, ADD_NOTE, SET_WARP }
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

        webServer = new WebServer(this);
        webServer.start();

        // Start playtime tracker (every 60 seconds = 1200 ticks)
        startPlaytimeTracker();
        
        // Start punishment expiry checker (every 1 second = 20 ticks)
        startPunishmentChecker();

        getLogger().info("Drowsy Management Tool Fully Loaded!");
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        saveDataFile();
    }

    public FileConfiguration getDataConfig() { return dataConfig; }
    public String fetchApiKey() { return apiKey; }
    
    public void saveDataConfig() { saveDataFile(); }

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
        try { dataConfig.save(dataFile); } catch (IOException ignored) {}
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
            if (!p.hasPermission("dmt.admin")) {
                p.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }

            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelpMessage(p);
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
                default:
                    p.sendMessage(ChatColor.RED + "Unknown subcommand. Use /dmt help");
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("ticket")) {
            if (args.length < 2 || !args[0].equalsIgnoreCase("new")) {
                p.sendMessage(ChatColor.RED + "Usage: /ticket new <message>");
                return true;
            }
            int id = dataConfig.getInt("tickets.next_id", 1);
            String path = "tickets." + id;
            dataConfig.set(path + ".player", p.getName());
            dataConfig.set(path + ".message", String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            dataConfig.set(path + ".status", "open");
            dataConfig.set(path + ".priority", "medium");
            dataConfig.set(path + ".category", "other");
            dataConfig.set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
            dataConfig.set("tickets.next_id", id + 1);
            saveDataFile();
            p.sendMessage(ChatColor.GREEN + "Ticket #" + id + " created.");
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
        return true;
    }

    private void sendHelpMessage(Player p) {
        p.sendMessage(ChatColor.GOLD + "=== " + ChatColor.AQUA + "Drowsy Management Tool" + ChatColor.GOLD + " ===");
        p.sendMessage(ChatColor.AQUA + "/dmt menu" + ChatColor.WHITE + " - Opens the management GUI");
        p.sendMessage(ChatColor.AQUA + "/dmt setpunishloc" + ChatColor.WHITE + " - Set punishment location");
        p.sendMessage(ChatColor.AQUA + "/dmt setjailloc" + ChatColor.WHITE + " - Set jail location");
        p.sendMessage(ChatColor.AQUA + "/dmt tpjail" + ChatColor.WHITE + " - Teleport to jail location");
        p.sendMessage(ChatColor.AQUA + "/dmt help" + ChatColor.WHITE + " - Show this help message");
        p.sendMessage(ChatColor.AQUA + "/ticket new <message>" + ChatColor.WHITE + " - Submit a ticket");
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
                    dataConfig.set("warps." + reason + ".x", p.getLocation().getX());
                    dataConfig.set("warps." + reason + ".y", p.getLocation().getY());
                    dataConfig.set("warps." + reason + ".z", p.getLocation().getZ());
                    dataConfig.set("warps." + reason + ".world", p.getWorld().getName());
                    dataConfig.set("warps." + reason + ".yaw", p.getLocation().getYaw());
                    dataConfig.set("warps." + reason + ".pitch", p.getLocation().getPitch());
                    saveDataFile();
                    p.sendMessage(ChatColor.GREEN + "Warp '" + reason + "' created!");
                    break;
                case WARN: if (target != null) target.sendMessage(ChatColor.RED + "WARNING: " + ChatColor.YELLOW + reason); break;
                case KICK: if (target != null) target.kickPlayer(ChatColor.RED + "Kicked: " + reason); break;
                case BAN: 
                    Bukkit.getBanList(BanList.Type.NAME).addBan(ctx.targetName, reason, null, null);
                    if (target != null) target.kickPlayer(ChatColor.RED + "Banned: " + reason);
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
        
        // Back button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "Back");
        back.setItemMeta(bMeta);
        gui.setItem(53, back);
        
        p.openInventory(gui);
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
                if ("open".equals(dataConfig.getString("tickets." + key + ".status"))) {
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.YELLOW + "By: " + dataConfig.getString("tickets." + key + ".player"));
                    lore.add(ChatColor.GRAY + dataConfig.getString("tickets." + key + ".message"));
                    lore.add(ChatColor.RED + "Click to close");
                    ItemStack ticketItem = createGuiItem(Material.PAPER, ChatColor.GOLD + "Ticket #" + key, lore);
                    int slot = getNextGridSlot();
                    if (slot != -1) gui.setItem(slot, ticketItem);
                }
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
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
                wMeta.setLore(Arrays.asList(
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
        if (!title.equals(GUI_MAIN) && !title.equals(GUI_PLAYER_LIST) && !title.equals(GUI_TICKET_LIST) 
            && !title.startsWith(GUI_PLAYER_ACTION) && !title.startsWith(GUI_NOTES_VIEW) && !title.equals(ChatColor.RED + "Punished Players")
            && !title.startsWith(ChatColor.YELLOW + "Note:") && !title.equals(GUI_MENU_SELECTOR) && !title.equals(GUI_PLAYER_MENU)
            && !title.equals(GUI_PLAYER_LIST_TPA) && !title.equals(ChatColor.BLUE + "Warps") && !title.startsWith(GUI_WARP_MANAGEMENT)
            && !title.startsWith(ChatColor.RED + "Delete:") && !title.equals(GUI_CLAIMS) && !title.equals(GUI_CLAIM_CONFIRM)
            && !title.equals(GUI_UNCLAIM_CONFIRM) && !title.equals(GUI_TRUST_PLAYER) && !title.equals(GUI_UNTRUST_PLAYER)) return;

        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;
        Player p = (Player) e.getWhoClicked();
        Material type = e.getCurrentItem().getType();
        String itemName = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());

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
            } else if (type == Material.BARRIER) {
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

            int slot = e.getSlot();
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
                case 16: openPunishedPlayersMenu(p); break;
                case 43: p.closeInventory(); break;
            }
        } else if (title.equals(GUI_TICKET_LIST)) {
            if (type == Material.PAPER) {
                String id = itemName.replace("Ticket #", "");
                dataConfig.set("tickets." + id + ".status", "closed");
                saveDataFile();
                openTicketListMenu(p);
            } else if (type == Material.REDSTONE) openMainMenu(p);
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
    }


    // --- LISTENERS ---
    @EventHandler
    public void onPunishMove(PlayerMoveEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) {
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) e.setCancelled(true);
        }
    }
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        World.Environment env = to.getWorld().getEnvironment();
        if (env == World.Environment.NETHER && dataConfig.getBoolean("locks.nether", false)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Nether access is currently locked.");
            return;
        }
        if (env == World.Environment.THE_END && dataConfig.getBoolean("locks.end", false)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "The End access is currently locked.");
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL || c == PlayerTeleportEvent.TeleportCause.END_PORTAL || c == PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
            Location to = e.getTo();
            if (to == null) return;
            World.Environment env = to.getWorld().getEnvironment();
            if (env == World.Environment.NETHER && dataConfig.getBoolean("locks.nether", false)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "Nether access is currently locked.");
            }
            if (env == World.Environment.THE_END && dataConfig.getBoolean("locks.end", false)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "The End access is currently locked.");
            }
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent e) {
        World.Environment env = e.getPlayer().getWorld().getEnvironment();
        if (env == World.Environment.NETHER && dataConfig.getBoolean("locks.nether", false)) {
            // Move player back to their previous world's spawn
            Player p = e.getPlayer();
            World overworld = Bukkit.getWorlds().get(0);
            Bukkit.getScheduler().runTask(this, () -> {
                p.teleport(overworld.getSpawnLocation());
                p.sendMessage(ChatColor.RED + "Nether access is locked. You have been returned.");
            });
        }
        if (env == World.Environment.THE_END && dataConfig.getBoolean("locks.end", false)) {
            Player p = e.getPlayer();
            World overworld = Bukkit.getWorlds().get(0);
            Bukkit.getScheduler().runTask(this, () -> {
                p.teleport(overworld.getSpawnLocation());
                p.sendMessage(ChatColor.RED + "The End access is locked. You have been returned.");
            });
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
    public void onPunishDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isPunished(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
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
        }
    }
    @EventHandler
    public void onChestAccess(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.CHEST) saveLog(e.getInventory().getLocation(), ChatColor.YELLOW + "Opened by " + e.getPlayer().getName());
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

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        
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
        if (e.getPlayer().hasPermission("dmt.admin")) {
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
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.trackSession(e.getPlayer().getUniqueId(), e.getPlayer().getName(), false);
        this.logAction("System", "player_left", e.getPlayer().getName());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        this.addChatLog(e.getPlayer().getName(), e.getMessage());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        String newChunk = getChunkKey(e.getTo());
        String oldChunk = currentChunk.getOrDefault(p.getUniqueId(), newChunk);
        
        if (!newChunk.equals(oldChunk)) {
            // Check if old chunk is claimed
            if (!oldChunk.equals(newChunk) && isChunkClaimed(oldChunk)) {
                UUID owner = getChunkOwner(oldChunk);
                if (owner != null && !p.getUniqueId().equals(owner)) {
                    Player ownerPlayer = Bukkit.getPlayer(owner);
                    String ownerName = ownerPlayer != null ? ownerPlayer.getName() : "Unknown";
                    p.sendMessage(ChatColor.YELLOW + "You have left " + ownerName + "'s claim!");
                }
            }
            
            // Check if new chunk is claimed
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
        m.put("responses", new ArrayList<>(dataConfig.getStringList(base + ".responses")));
        return m;
    }

    public void addTicketResponse(int id, String admin, String message) {
        String path = "tickets." + id + ".responses";
        List<String> responses = dataConfig.getStringList(path);
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + admin + " | " + message;
        responses.add(entry);
        dataConfig.set(path, responses);
        saveDataFile();
    }

    public void updateTicketField(int id, String field, String value) {
        dataConfig.set("tickets." + id + "." + field, value);
        saveDataFile();
    }

    public void resolveTicket(int id, String reason) {
        dataConfig.set("tickets." + id + ".status", "resolved");
        dataConfig.set("tickets." + id + ".resolution", reason);
        saveDataFile();
    }

    public void mutePlayer(UUID u, String reason) {
        List<String> m = dataConfig.getStringList("muted");
        String id = u.toString();
        if (!m.contains(id)) m.add(id);
        dataConfig.set("muted", m);
        saveDataFile();
    }

    public void unmutePlayer(UUID u) {
        List<String> m = dataConfig.getStringList("muted");
        m.remove(u.toString());
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
        for (int i = 0; i < 9; i++) {
            if (gui.getItem(i) == null) gui.setItem(i, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
            if (gui.getItem(45 + i) == null) gui.setItem(45 + i, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        for (int i = 1; i < 5; i++) {
            if (gui.getItem(i * 9) == null) gui.setItem(i * 9, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
            if (gui.getItem(i * 9 + 8) == null) gui.setItem(i * 9 + 8, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
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

}

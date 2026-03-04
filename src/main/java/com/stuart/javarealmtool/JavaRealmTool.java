package com.stuart.javarealmtool;

import org.bukkit.*;
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

public class JavaRealmTool extends JavaPlugin implements Listener, CommandExecutor {

    private File dataFile;
    private FileConfiguration dataConfig;
    private Scoreboard scoreboard;
    private Team punishTeam;
    private WebServer webServer;
    private String apiKey;
    private final Map<UUID, PunishmentContext> pendingActions = new HashMap<>();

    // --- GUI STRINGS ---
    private final String GUI_MAIN = ChatColor.AQUA + "Drowsy Management Tool";
    private final String GUI_PLAYER_LIST = ChatColor.AQUA + "Player Directory";
    private final String GUI_TICKET_LIST = ChatColor.GOLD + "Ticket Viewer";
    private final String GUI_NOTES_VIEW = ChatColor.GOLD + "Player Notes: ";
    private final String GUI_PLAYER_ACTION = ChatColor.AQUA + "Manage: ";
    private final String INSPECTOR_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "Inspector Wand";
    private final String TOOL_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "Drowsy Tool";
    
    private final String GUI_MENU_SELECTOR = ChatColor.AQUA + "Menu Selection";
    private final String GUI_PLAYER_MENU = ChatColor.GREEN + "Player Menu";
    private final String GUI_PLAYER_LIST_TPA = ChatColor.GREEN + "Players (TPA)";
    private final String GUI_WARP_MANAGEMENT = ChatColor.BLUE + "Manage Warp: ";

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

        getLogger().info("Drowsy Management Tool Fully Loaded!");
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        saveDataFile();
    }

    public FileConfiguration getDataConfig() { return dataConfig; }
    public String getApiKey() { return apiKey; }
    
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
                case "menu":
                    openMainMenu(p);
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
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_LIST);
        resetGridSlots();
        for (Player target : Bukkit.getOnlinePlayers()) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.AQUA + target.getName());
            head.setItemMeta(meta);
            int slot = getNextGridSlot();
            if (slot != -1) gui.setItem(slot, head);
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu"));
        fillGUIBorders(gui);
        fillGUIEmpty(gui);
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
            && !title.startsWith(ChatColor.RED + "Delete:")) return;

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


        if (title.equals(GUI_MAIN)) {

            int slot = e.getSlot();
            switch(slot) {
                case 11: p.getWorld().setStorm(false); break;
                case 12: p.getWorld().setThundering(false); p.getWorld().setStorm(true); break;
                case 13: p.getWorld().setThundering(true); p.getWorld().setStorm(true); break;
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
    public void onPunishDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isPunished(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) e.setCancelled(true);
        else saveLog(e.getBlock().getLocation(), ChatColor.GREEN + "Placed by " + e.getPlayer().getName());
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) e.setCancelled(true);
        else saveLog(e.getBlock().getLocation(), ChatColor.RED + "Broken by " + e.getPlayer().getName());
    }
    @EventHandler
    public void onChestAccess(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.CHEST) saveLog(e.getInventory().getLocation(), ChatColor.YELLOW + "Opened by " + e.getPlayer().getName());
    }
    @EventHandler
    public void onWandUse(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getItem() != null && e.getItem().hasItemMeta() 
            && e.getItem().getItemMeta().getDisplayName().equals(INSPECTOR_NAME)) {
            e.setCancelled(true);
            List<String> logs = getLogs(e.getClickedBlock().getLocation());
            e.getPlayer().sendMessage(ChatColor.AQUA + "--- Block History ---");
            if (logs.isEmpty()) e.getPlayer().sendMessage(ChatColor.RED + "No history.");
            else logs.forEach(l -> e.getPlayer().sendMessage(ChatColor.GRAY + "- " + l));
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
        if (isPunished(e.getPlayer().getUniqueId())) {
            punishTeam.addEntry(e.getPlayer().getName());
            long min = (dataConfig.getLong("punishments." + e.getPlayer().getUniqueId()) - System.currentTimeMillis()) / 60000;
            e.getPlayer().sendMessage(ChatColor.RED + "You are punished for " + min + " more minutes.");
        }
    }

    // --- HELPERS ---
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
        if (p != null) {
            punishTeam.removeEntry(p.getName());
            p.sendMessage(ChatColor.GREEN + "Punishment lifted.");
        }
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
        String[] s = dataConfig.getString(p).split(",");
        return new Location(Bukkit.getWorld(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3]), Float.parseFloat(s[4]), Float.parseFloat(s[5]));
    }

    private ItemStack createGuiItem(Material m, String n) { ItemStack i = new ItemStack(m); ItemMeta im = i.getItemMeta(); im.setDisplayName(n); i.setItemMeta(im); return i; }
    private ItemStack createGuiItem(Material m, String n, List<String> l) { ItemStack i = createGuiItem(m, n); ItemMeta im = i.getItemMeta(); im.setLore(l); i.setItemMeta(im); return i; }
}
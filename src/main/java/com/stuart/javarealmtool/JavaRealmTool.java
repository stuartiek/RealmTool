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

// FIX 1: Single, correct class declaration
public class JavaRealmTool extends JavaPlugin implements Listener, CommandExecutor {

    private File dataFile;
    private FileConfiguration dataConfig;
    private Scoreboard scoreboard;
    private Team punishTeam;
    private final Map<UUID, PunishmentContext> pendingActions = new HashMap<>();
    private WebServer webServer;
    private String apiKey;

    // --- GUI STRINGS (AQUA THEME) ---
    private final String GUI_MAIN = ChatColor.AQUA + "Drowsy Management Tool";
    private final String GUI_PLAYER_LIST = ChatColor.AQUA + "Player Directory";
    private final String GUI_TICKET_LIST = ChatColor.GOLD + "Ticket Viewer";
    private final String GUI_NOTES_VIEW = ChatColor.GOLD + "Player Notes: ";
    private final String GUI_PLAYER_ACTION = ChatColor.AQUA + "Manage: ";
    private final String INSPECTOR_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "Inspector Wand";

    private enum ActionType { KICK, BAN, WARN, ANNOUNCE, ADD_NOTE }
    private static class PunishmentContext {
        String targetName;
        ActionType type;
        PunishmentContext(String n, ActionType t) { this.targetName = n; this.type = t; }
    }

    @Override
    public void onEnable() {
        this.getCommand("rmt").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        createDataFile();
        setupConfig();
        setupPunishTeam();
        
        // Note: Ensure you have a 'WebServer.java' class in the same package!
        // If not, comment out these two lines below to fix 'Cannot find symbol' errors.
        webServer = new WebServer(this);
        webServer.start();
        
        getLogger().info("Drowsy Management Tool Fully Loaded!");
    } 
    // FIX 2: I removed the extra '}' that was here.

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        getLogger().info("Drowsy Management Tool has been disabled.");
    }

    private void setupConfig() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        if (!config.contains("api-key")) {
            config.set("api-key", UUID.randomUUID().toString());
            saveConfig();
        }
        this.apiKey = config.getString("api-key");
    }

    public String getApiKey() {
        return apiKey;
    }

    private void setupPunishTeam() {
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        punishTeam = scoreboard.getTeam("DrowsyPunish");
        if (punishTeam == null) punishTeam = scoreboard.registerNewTeam("DrowsyPunish");
        punishTeam.setPrefix(ChatColor.RED + "" + ChatColor.BOLD + "PUNISHMENT " + ChatColor.RESET);
        punishTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
    }

    // ==========================================
    //          COMMANDS & JAIL
    // ==========================================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("rmt")) {
            if (!p.hasPermission("rmt.admin")) {
                p.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            if (args.length == 0) { openMainMenu(p); return true; }

            if (args[0].equalsIgnoreCase("setjailloc")) {
                saveLoc("settings.jail_location", p.getLocation());
                p.sendMessage(ChatColor.AQUA + "Jail location set!");
            } else if (args[0].equalsIgnoreCase("tpjail")) {
                Location jail = getLoc("settings.jail_location");
                if (jail != null) p.teleport(jail);
                else p.sendMessage(ChatColor.RED + "No jail set! Use /rmt setjailloc");
            }
        } else if (cmd.getName().equalsIgnoreCase("ticket")) {
            if (args.length < 2 || !args[0].equalsIgnoreCase("new")) {
                p.sendMessage(ChatColor.RED + "Usage: /ticket new <message>");
                return true;
            }

            String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            int nextId = dataConfig.getInt("tickets.next_id", 1);
            String path = "tickets." + nextId;

            dataConfig.set(path + ".player", p.getUniqueId().toString());
            dataConfig.set(path + ".name", p.getName());
            dataConfig.set(path + ".message", message);
            dataConfig.set(path + ".status", "open");
            dataConfig.set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            dataConfig.set("tickets.next_id", nextId + 1);
            saveDataFile();

            p.sendMessage(ChatColor.GREEN + "Ticket #" + nextId + " submitted successfully!");
            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (admin.hasPermission("rmt.admin")) {
                    admin.sendMessage(ChatColor.GOLD + "[RMT] New ticket #" + nextId + " from " + p.getName() + ".");
                }
            }
        }
        return true;
    }

    // ==========================================
    //          CHAT REASON SYSTEM
    // ==========================================
    @EventHandler
    public void onChatReason(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!pendingActions.containsKey(p.getUniqueId())) return;

        e.setCancelled(true);
        PunishmentContext ctx = pendingActions.remove(p.getUniqueId());
        String reason = e.getMessage();
        
        final Player target = (ctx.targetName != null) ? Bukkit.getPlayer(ctx.targetName) : null;

        Bukkit.getScheduler().runTask(this, () -> {
            switch (ctx.type) {
                case ANNOUNCE:
                    Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[Announcement] " + ChatColor.RESET + ChatColor.YELLOW + reason);
                    p.sendMessage(ChatColor.AQUA + "Broadcast sent!");
                    return;
                case WARN: if (target != null) target.sendMessage(ChatColor.RED + "WARNING: " + ChatColor.YELLOW + reason); break;
                case KICK: if (target != null) target.kickPlayer(ChatColor.RED + "Kicked: " + reason); break;
                case BAN: 
                    Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(ctx.targetName, reason, null, null);
                    if (target != null) target.kickPlayer(ChatColor.RED + "Banned: " + reason);
                    break;
            }
            if (ctx.targetName != null && ctx.type != ActionType.ADD_NOTE) {
                p.sendMessage(ChatColor.AQUA + "Action applied to " + ctx.targetName + " for: " + reason);
            }
            // Add Note Logic
            if (ctx.type == ActionType.ADD_NOTE) {
                UUID targetUUID = Bukkit.getOfflinePlayer(ctx.targetName).getUniqueId();
                List<String> notes = dataConfig.getStringList("notes." + targetUUID);
                String note = "[" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + " - " + p.getName() + "] " + reason;
                notes.add(note);
                dataConfig.set("notes." + targetUUID, notes);
                saveDataFile();
                p.sendMessage(ChatColor.GREEN + "Note added for " + ctx.targetName);
            }
        });
    }

    // ==========================================
    //          BLOCK & CHEST LOGGING
    // ==========================================
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) { e.setCancelled(true); return; }
        saveLog(e.getBlock().getLocation(), ChatColor.GREEN + "Placed" + ChatColor.GRAY + " by " + e.getPlayer().getName());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) { e.setCancelled(true); return; }
        saveLog(e.getBlock().getLocation(), ChatColor.RED + "Broken" + ChatColor.GRAY + " by " + e.getPlayer().getName());
    }

    @EventHandler
    public void onChestAccess(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.CHEST) {
            saveLog(e.getInventory().getLocation(), ChatColor.YELLOW + "Opened" + ChatColor.GRAY + " by " + e.getPlayer().getName());
        }
    }

    @EventHandler
    public void onWandUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getItem() == null) return;
        if (e.getItem().hasItemMeta() && e.getItem().getItemMeta().getDisplayName().equals(INSPECTOR_NAME)) {
            e.setCancelled(true);
            List<String> logs = getLogs(e.getClickedBlock().getLocation());
            e.getPlayer().sendMessage(ChatColor.AQUA + "--- Block History (Last 20) ---");
            if (logs.isEmpty()) e.getPlayer().sendMessage(ChatColor.RED + "No history found.");
            else logs.forEach(line -> e.getPlayer().sendMessage(ChatColor.GRAY + "- " + line));
        }
    }

    // ==========================================
    //          PUNISHMENT & CONTAINMENT
    // ==========================================
    @EventHandler
    public void onPunishMove(PlayerMoveEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) {
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getZ() != e.getTo().getZ()) {
                e.getPlayer().teleport(new Location(e.getPlayer().getWorld(), 25, -60, 43));
            }
        }
    }

    @EventHandler
    public void onPunishDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isPunished(p.getUniqueId())) e.setCancelled(true);
    }

    private void setPunished(UUID uuid, long durationMillis) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            saveLoc("last_location." + uuid, p.getLocation());
            p.teleport(new Location(p.getWorld(), 25, -60, 43));
            punishTeam.addEntry(p.getName());
        }
        dataConfig.set("punishments." + uuid, System.currentTimeMillis() + durationMillis);
        saveDataFile();
    }

    private void removePunishment(UUID uuid) {
        dataConfig.set("punishments." + uuid, null);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            Location back = getLoc("last_location." + uuid);
            if (back != null) p.teleport(back);
            punishTeam.removeEntry(p.getName());
        }
        saveDataFile();
    }

    private boolean isPunished(UUID u) {
        if (!dataConfig.contains("punishments." + u)) return false;
        if (System.currentTimeMillis() > dataConfig.getLong("punishments." + u)) { removePunishment(u); return false; }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) {
            punishTeam.addEntry(e.getPlayer().getName());
            long diff = dataConfig.getLong("punishments." + e.getPlayer().getUniqueId()) - System.currentTimeMillis();
            e.getPlayer().sendMessage(ChatColor.RED + "Punishment ends in: " + (diff / 60000) + " minutes.");
        }
    }

    // ==========================================
    //          GUI MENUS
    // ==========================================
    private void openMainMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_MAIN);
        // --- Time & Weather ---
        gui.setItem(1, createGuiItem(Material.SUNFLOWER, ChatColor.GOLD + "Set Weather: Clear"));
        gui.setItem(3, createGuiItem(Material.WATER_BUCKET, ChatColor.AQUA + "Set Weather: Rain"));
        gui.setItem(5, createGuiItem(Material.BEACON, ChatColor.DARK_GRAY + "Set Weather: Thunder"));
        gui.setItem(20, createGuiItem(Material.CLOCK, ChatColor.AQUA + "Set Day"));
        gui.setItem(29, createGuiItem(Material.COAL, ChatColor.DARK_AQUA + "Set Night"));

        gui.setItem(22, createGuiItem(Material.PLAYER_HEAD, ChatColor.AQUA + "Player Directory"));
        gui.setItem(24, createGuiItem(Material.GRASS_BLOCK, ChatColor.AQUA + "Creative Mode"));
        gui.setItem(33, createGuiItem(Material.BEEF, ChatColor.AQUA + "Survival Mode"));
        gui.setItem(35, createGuiItem(Material.GOLDEN_APPLE, ChatColor.GOLD + "Heal & Feed All"));
        gui.setItem(40, createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME));
        gui.setItem(42, createGuiItem(Material.WRITABLE_BOOK, ChatColor.GOLD + "Broadcast Message"));
        gui.setItem(44, createGuiItem(Material.PAPER, ChatColor.GOLD + "View Tickets"));
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Close Menu"));

        // --- Stats Panel ---
        int totalPlayers = Bukkit.getOnlinePlayers().size();
        int punishedCount = 0;
        if (dataConfig.getConfigurationSection("punishments") != null) {
            for (String key : dataConfig.getConfigurationSection("punishments").getKeys(false)) {
                if (key.equals("next_id")) continue;
                if (isPunished(UUID.fromString(key))) {
                    punishedCount++;
                }
            }
        }
        gui.setItem(17, createGuiItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, ChatColor.AQUA + "Total Players: " + ChatColor.WHITE + totalPlayers));
        gui.setItem(26, createGuiItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, ChatColor.RED + "Punished: " + ChatColor.WHITE + punishedCount));

        player.openInventory(gui);
    }

    private void openPlayerListMenu(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_LIST);
        for (Player target : Bukkit.getOnlinePlayers()) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD); SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target); meta.setDisplayName(ChatColor.AQUA + target.getName()); head.setItemMeta(meta);
            gui.addItem(head);
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        admin.openInventory(gui);
    }

    private void openTicketListMenu(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_TICKET_LIST);
        if (dataConfig.getConfigurationSection("tickets") != null) {
            for (String key : dataConfig.getConfigurationSection("tickets").getKeys(false)) {
                if (key.equals("next_id")) continue;
                if ("open".equals(dataConfig.getString("tickets." + key + ".status"))) {
                    String name = dataConfig.getString("tickets." + key + ".name");
                    String msg = dataConfig.getString("tickets." + key + ".message");
                    String time = dataConfig.getString("tickets." + key + ".timestamp");
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.YELLOW + "From: " + ChatColor.WHITE + name);
                    lore.add(ChatColor.YELLOW + "Date: " + ChatColor.WHITE + time);
                    lore.add("");
                    lore.add(ChatColor.GRAY + msg);
                    lore.add("");
                    lore.add(ChatColor.RED + "Click to close ticket.");
                    gui.addItem(createGuiItem(Material.PAPER, ChatColor.GOLD + "Ticket #" + key, lore));
                }
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        admin.openInventory(gui);
    }

    private void openPlayerNotesMenu(Player admin, String targetName) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_NOTES_VIEW + targetName);
        UUID targetUUID = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        List<String> notes = dataConfig.getStringList("notes." + targetUUID);

        for (String note : notes) {
            gui.addItem(createGuiItem(Material.PAPER, ChatColor.YELLOW + note));
        }

        gui.setItem(45, createGuiItem(Material.WRITABLE_BOOK, ChatColor.GREEN + "Add Note"));
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        admin.openInventory(gui);
    }

    private void openPlayerActionMenu(Player admin, String targetName) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_ACTION + targetName);
        gui.setItem(10, createGuiItem(Material.COMPASS, ChatColor.AQUA + "Teleport to Player"));
        gui.setItem(12, createGuiItem(Material.CHEST, ChatColor.AQUA + "See Inventory"));
        gui.setItem(14, createGuiItem(Material.ENDER_CHEST, ChatColor.AQUA + "See Enderchest"));
        gui.setItem(16, createGuiItem(Material.BOOK, ChatColor.GOLD + "View Notes"));
        
        gui.setItem(28, createGuiItem(Material.GOAT_HORN, ChatColor.YELLOW + "Warn Player"));
        gui.setItem(29, createGuiItem(Material.NETHERITE_SWORD, ChatColor.RED + "Kick Player"));
        gui.setItem(30, createGuiItem(Material.MACE, ChatColor.DARK_RED + "Ban Player"));
        
        gui.setItem(37, createGuiItem(Material.IRON_BARS, ChatColor.RED + "Punish 1hr"));
        gui.setItem(38, createGuiItem(Material.IRON_BARS, ChatColor.RED + "Punish 3hr"));
        gui.setItem(39, createGuiItem(Material.IRON_BARS, ChatColor.DARK_RED + "Punish 24hr"));
        gui.setItem(40, createGuiItem(Material.MILK_BUCKET, ChatColor.GREEN + "Unpunish"));
        
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        admin.openInventory(gui);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (!title.equals(GUI_MAIN) && !title.equals(GUI_PLAYER_LIST) && !title.equals(GUI_TICKET_LIST) && !title.startsWith(GUI_PLAYER_ACTION) && !title.startsWith(GUI_NOTES_VIEW)) return;

        e.setCancelled(true);
        ItemStack item = e.getCurrentItem(); if (item == null) return;
        Player p = (Player) e.getWhoClicked();

        if (title.equals(GUI_MAIN)) {
            switch(item.getType()) {
                // Weather
                case SUNFLOWER: p.getWorld().setStorm(false); break;
                case WATER_BUCKET: p.getWorld().setThundering(false); p.getWorld().setStorm(true); break;
                case BEACON: p.getWorld().setThundering(true); p.getWorld().setStorm(true); break;
                // Time
                case CLOCK: p.getWorld().setTime(1000); break;
                case COAL: p.getWorld().setTime(13000); break;
                // Main Actions
                case PLAYER_HEAD: openPlayerListMenu(p); break;
                case PAPER: openTicketListMenu(p); break;
                case GRASS_BLOCK: p.setGameMode(GameMode.CREATIVE); break;
                case BEEF: p.setGameMode(GameMode.SURVIVAL); break;
                case GOLDEN_APPLE:
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        onlinePlayer.setHealth(20.0);
                        onlinePlayer.setFoodLevel(20);
                    }
                    p.sendMessage(ChatColor.AQUA + "Healed and fed all online players.");
                    break;
                case BLAZE_ROD: p.getInventory().addItem(createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME)); break;
                case WRITABLE_BOOK:
                    p.closeInventory();
                    pendingActions.put(p.getUniqueId(), new PunishmentContext(null, ActionType.ANNOUNCE));
                    p.sendMessage(ChatColor.GOLD + "Enter message to broadcast:");
                    break;
                case REDSTONE: p.closeInventory(); break;
            }
        } else if (title.equals(GUI_TICKET_LIST)) {
            if (item.getType() == Material.PAPER) {
                String ticketId = ChatColor.stripColor(item.getItemMeta().getDisplayName()).replace("Ticket #", "");
                dataConfig.set("tickets." + ticketId + ".status", "closed");
                saveDataFile();
                p.sendMessage(ChatColor.GOLD + "Ticket #" + ticketId + " closed.");
                openTicketListMenu(p); // Refresh
            } else if (item.getType() == Material.REDSTONE) {
                openMainMenu(p);
            }
        } else if (title.equals(GUI_PLAYER_LIST)) {
            if (item.getType() == Material.PLAYER_HEAD) openPlayerActionMenu(p, ChatColor.stripColor(item.getItemMeta().getDisplayName()));
            else if (item.getType() == Material.REDSTONE) openMainMenu(p);
        } else if (title.startsWith(GUI_NOTES_VIEW)) {
            String targetName = title.replace(GUI_NOTES_VIEW, "");
            if (item.getType() == Material.WRITABLE_BOOK) {
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(targetName, ActionType.ADD_NOTE));
                p.sendMessage(ChatColor.GOLD + "Enter note for " + targetName + ":");
            } else if (item.getType() == Material.REDSTONE) {
                openPlayerActionMenu(p, targetName);
            }
        } else if (title.startsWith(GUI_PLAYER_ACTION)) {
            String targetName = title.replace(GUI_PLAYER_ACTION, "");
            Player target = Bukkit.getPlayer(targetName);
            UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();

            if (item.getType() == Material.REDSTONE) { openPlayerListMenu(p); return; }
            
            p.closeInventory();
            switch(item.getType()) {
                case COMPASS: if(target != null) p.teleport(target); break;
                case CHEST: if(target != null) p.openInventory(target.getInventory()); break;
                case ENDER_CHEST: if(target != null) p.openInventory(target.getEnderChest()); break;
                case BOOK: openPlayerNotesMenu(p, targetName); break;
                case GOAT_HORN: pendingActions.put(p.getUniqueId(), new PunishmentContext(targetName, ActionType.WARN)); p.sendMessage(ChatColor.AQUA + "Enter WARN reason:"); break;
                case NETHERITE_SWORD: pendingActions.put(p.getUniqueId(), new PunishmentContext(targetName, ActionType.KICK)); p.sendMessage(ChatColor.AQUA + "Enter KICK reason:"); break;
                case MACE: pendingActions.put(p.getUniqueId(), new PunishmentContext(targetName, ActionType.BAN)); p.sendMessage(ChatColor.AQUA + "Enter BAN reason:"); break;
                case IRON_BARS: 
                    long d = item.getItemMeta().getDisplayName().contains("1hr") ? 3600000 : item.getItemMeta().getDisplayName().contains("3hr") ? 10800000 : 86400000;
                    setPunished(uuid, d); break;
                case MILK_BUCKET: removePunishment(uuid); break;
            }
        }
    }

    // --- DATA HELPERS ---
    private void saveLoc(String path, Location loc) { dataConfig.set(path, loc.getWorld().getName()+","+loc.getX()+","+loc.getY()+","+loc.getZ()+","+loc.getYaw()+","+loc.getPitch()); saveDataFile(); }
    private Location getLoc(String path) {
        if (!dataConfig.contains(path)) return null; String[] s = dataConfig.getString(path).split(",");
        return new Location(Bukkit.getWorld(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3]), Float.parseFloat(s[4]), Float.parseFloat(s[5]));
    }
    private void createDataFile() { dataFile = new File(getDataFolder(), "data.yml"); if (!dataFile.exists()) { dataFile.getParentFile().mkdirs(); try { dataFile.createNewFile(); } catch (IOException e) {} } dataConfig = YamlConfiguration.loadConfiguration(dataFile); }
    private void saveDataFile() { try { dataConfig.save(dataFile); } catch (IOException e) {} }
    private void saveLog(Location loc, String msg) {
        if (loc == null) return;
        String key = "logs." + loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        List<String> h = dataConfig.getStringList(key); h.add(msg + " (" + new SimpleDateFormat("HH:mm").format(new Date()) + ")");
        if (h.size() > 20) h.remove(0);
        dataConfig.set(key, h); saveDataFile();
    }
    private List<String> getLogs(Location loc) { return dataConfig.getStringList("logs." + loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()); }
    private ItemStack createGuiItem(Material m, String name) { ItemStack i = new ItemStack(m); ItemMeta meta = i.getItemMeta(); meta.setDisplayName(name); i.setItemMeta(meta); return i; }
    private ItemStack createGuiItem(Material m, String name, List<String> lore) {
        ItemStack i = createGuiItem(m, name);
        ItemMeta meta = i.getItemMeta();
        meta.setLore(lore);
        i.setItemMeta(meta);
        return i;
    }
}
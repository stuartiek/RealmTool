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

    private enum ActionType { KICK, BAN, WARN, ANNOUNCE, ADD_NOTE }
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
        if (getCommand("rmt") != null) getCommand("rmt").setExecutor(this);
        if (getCommand("ticket") != null) getCommand("ticket").setExecutor(this);

        webServer = new WebServer(this);
        webServer.start();

        getLogger().info("Drowsy Management Tool Fully Loaded!");
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        saveDataFile();
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

    public org.bukkit.configuration.file.FileConfiguration getDataConfig() { return dataConfig; }

    public void saveDataConfig() { saveDataFile(); }

    public boolean isPunished(UUID u) {
        if (!dataConfig.contains("punishments." + u)) return false;
        long expiry = dataConfig.getLong("punishments." + u);
        if (System.currentTimeMillis() > expiry) {
            dataConfig.set("punishments." + u, null);
            saveDataFile();
            return false;
        }
        return true;
    }

    private void createDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException ignored) {}
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveDataFile() {
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

        if (cmd.getName().equalsIgnoreCase("rmt")) {
            if (!p.hasPermission("rmt.admin")) {
                p.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }
            openMainMenu(p);
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
            dataConfig.set(path + ".timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
            dataConfig.set("tickets.next_id", id + 1);
            saveDataFile();
            p.sendMessage(ChatColor.GREEN + "Ticket #" + id + " created.");
            return true;
        }
        return true;
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
                    }
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
    private void openMainMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_MAIN);
        
        gui.setItem(1, createGuiItem(Material.SUNFLOWER, ChatColor.GOLD + "Weather: Clear"));
        gui.setItem(3, createGuiItem(Material.WATER_BUCKET, ChatColor.AQUA + "Weather: Rain"));
        gui.setItem(5, createGuiItem(Material.BEACON, ChatColor.DARK_GRAY + "Weather: Thunder"));
        
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

        int total = Bukkit.getOnlinePlayers().size();
        int punished = 0;
        if (dataConfig.contains("punishments")) {
            for (String key : dataConfig.getConfigurationSection("punishments").getKeys(false)) {
                if (isPunished(UUID.fromString(key))) punished++;
            }
        }
        gui.setItem(17, createGuiItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, ChatColor.AQUA + "Players: " + ChatColor.WHITE + total));
        gui.setItem(26, createGuiItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, ChatColor.RED + "Punished: " + ChatColor.WHITE + punished));

        p.openInventory(gui);
    }

    private void openPlayerListMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PLAYER_LIST);
        for (Player target : Bukkit.getOnlinePlayers()) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.AQUA + target.getName());
            head.setItemMeta(meta);
            gui.addItem(head);
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu"));
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
        p.openInventory(gui);
    }

    private void openTicketListMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_TICKET_LIST);
        if (dataConfig.contains("tickets")) {
            for (String key : dataConfig.getConfigurationSection("tickets").getKeys(false)) {
                if (key.equals("next_id")) continue;
                if ("open".equals(dataConfig.getString("tickets." + key + ".status"))) {
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.YELLOW + "By: " + dataConfig.getString("tickets." + key + ".player"));
                    lore.add(ChatColor.GRAY + dataConfig.getString("tickets." + key + ".message"));
                    lore.add(ChatColor.RED + "Click to close");
                    gui.addItem(createGuiItem(Material.PAPER, ChatColor.GOLD + "Ticket #" + key, lore));
                }
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu"));
        p.openInventory(gui);
    }

    private void openPlayerNotesMenu(Player p, String targetName) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_NOTES_VIEW + targetName);
        UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        if (dataConfig.contains("notes." + uuid)) {
            for (String note : dataConfig.getStringList("notes." + uuid)) {
                gui.addItem(createGuiItem(Material.PAPER, ChatColor.YELLOW + note));
            }
        }
        gui.setItem(45, createGuiItem(Material.WRITABLE_BOOK, ChatColor.GREEN + "Add Note"));
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back"));
        p.openInventory(gui);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (!title.equals(GUI_MAIN) && !title.equals(GUI_PLAYER_LIST) && !title.equals(GUI_TICKET_LIST) 
            && !title.startsWith(GUI_PLAYER_ACTION) && !title.startsWith(GUI_NOTES_VIEW)) return;

        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;
        Player p = (Player) e.getWhoClicked();
        Material type = e.getCurrentItem().getType();
        String itemName = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());

        if (title.equals(GUI_MAIN)) {
            switch(type) {
                case SUNFLOWER: p.getWorld().setStorm(false); break;
                case WATER_BUCKET: p.getWorld().setThundering(false); p.getWorld().setStorm(true); break;
                case BEACON: p.getWorld().setThundering(true); p.getWorld().setStorm(true); break;
                case CLOCK: p.getWorld().setTime(1000); break;
                case COAL: p.getWorld().setTime(13000); break;
                case PLAYER_HEAD: openPlayerListMenu(p); break;
                case GRASS_BLOCK: p.setGameMode(GameMode.CREATIVE); break;
                case BEEF: p.setGameMode(GameMode.SURVIVAL); break;
                case GOLDEN_APPLE: 
                    for (Player o : Bukkit.getOnlinePlayers()) { o.setHealth(20); o.setFoodLevel(20); }
                    p.sendMessage(ChatColor.GREEN + "Healed all."); break;
                case BLAZE_ROD: p.getInventory().addItem(createGuiItem(Material.BLAZE_ROD, INSPECTOR_NAME)); break;
                case WRITABLE_BOOK: 
                    p.closeInventory();
                    pendingActions.put(p.getUniqueId(), new PunishmentContext(null, ActionType.ANNOUNCE));
                    p.sendMessage(ChatColor.GOLD + "Enter broadcast message:"); break;
                case PAPER: openTicketListMenu(p); break;
                case REDSTONE: p.closeInventory(); break;
            }
        } else if (title.equals(GUI_TICKET_LIST)) {
            if (type == Material.PAPER) {
                String id = itemName.replace("Ticket #", "");
                dataConfig.set("tickets." + id + ".status", "closed");
                saveDataFile();
                openTicketListMenu(p);
            } else if (type == Material.REDSTONE) openMainMenu(p);
        } else if (title.equals(GUI_PLAYER_LIST)) {
            if (type == Material.PLAYER_HEAD) openPlayerActionMenu(p, itemName);
            else if (type == Material.REDSTONE) openMainMenu(p);
        } else if (title.startsWith(GUI_NOTES_VIEW)) {
            String target = title.replace(GUI_NOTES_VIEW, "");
            if (type == Material.WRITABLE_BOOK) {
                p.closeInventory();
                pendingActions.put(p.getUniqueId(), new PunishmentContext(target, ActionType.ADD_NOTE));
                p.sendMessage(ChatColor.GOLD + "Enter note:");
            } else if (type == Material.REDSTONE) openPlayerActionMenu(p, target);
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
    public void onJoin(PlayerJoinEvent e) {
        if (isPunished(e.getPlayer().getUniqueId())) {
            punishTeam.addEntry(e.getPlayer().getName());
            long min = (dataConfig.getLong("punishments." + e.getPlayer().getUniqueId()) - System.currentTimeMillis()) / 60000;
            e.getPlayer().sendMessage(ChatColor.RED + "You are punished for " + min + " more minutes.");
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        addChatLog(e.getPlayer().getName(), e.getMessage());
    }

    // --- HELPERS ---
    private void setPunished(UUID u, long d) {
        dataConfig.set("punishments." + u, System.currentTimeMillis() + d);
        saveDataFile();
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            punishTeam.addEntry(p.getName());
            p.sendMessage(ChatColor.RED + "You are punished! You cannot move/build.");
        }
    }
    private void removePunishment(UUID u) {
        dataConfig.set("punishments." + u, null);
        saveDataFile();
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            punishTeam.removeEntry(p.getName());
            p.sendMessage(ChatColor.GREEN + "Punishment lifted.");
        }
    }

    public void logAction(String actor, String action, String target) {
        if (!dataConfig.contains("action_history")) dataConfig.set("action_history", new ArrayList<>());
        List<String> history = dataConfig.getStringList("action_history");
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        history.add(ts + " | " + actor + " " + action + " " + target);
        if (history.size() > 500) history.remove(0);
        dataConfig.set("action_history", history);
        saveDataFile();
    }

    public void addWarning(UUID u, String reason) {
        String key = "warnings." + u;
        if (!dataConfig.contains(key)) dataConfig.set(key, new ArrayList<>());
        List<String> warnings = dataConfig.getStringList(key);
        String ts = new SimpleDateFormat("HH:mm").format(new Date());
        warnings.add(reason + " (" + ts + ")");
        dataConfig.set(key, warnings);
        saveDataFile();
    }

    public void addChatLog(String player, String message) {
        if (!dataConfig.contains("chat_history")) dataConfig.set("chat_history", new ArrayList<>());
        List<String> history = dataConfig.getStringList("chat_history");
        String ts = new SimpleDateFormat("HH:mm").format(new Date());
        history.add(ts + " " + player + ": " + message);
        if (history.size() > 100) history.remove(0);
        dataConfig.set("chat_history", history);
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
        String[] s = dataConfig.getString(p).split(",");
        return new Location(Bukkit.getWorld(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3]), Float.parseFloat(s[4]), Float.parseFloat(s[5]));
    }
    private ItemStack createGuiItem(Material m, String n) { ItemStack i = new ItemStack(m); ItemMeta im = i.getItemMeta(); im.setDisplayName(n); i.setItemMeta(im); return i; }
    private ItemStack createGuiItem(Material m, String n, List<String> l) { ItemStack i = createGuiItem(m, n); ItemMeta im = i.getItemMeta(); im.setLore(l); i.setItemMeta(im); return i; }
}
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
    private final String GUI_PUNISHED_LIST = ChatColor.RED + "Punished Directory";
    private final String GUI_TICKET_LIST = ChatColor.GOLD + "Ticket Viewer";
    private final String GUI_NOTES_VIEW = ChatColor.GOLD + "Player Notes: ";
    private final String GUI_PLAYER_ACTION = ChatColor.AQUA + "Manage: ";
    private final String INSPECTOR_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "Inspector Wand";
    private final String TOOL_NAME = ChatColor.AQUA + "" + ChatColor.BOLD + "Drowsy Tool";

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

    private boolean isPunished(UUID u) {
        if (!dataConfig.contains("punishments." + u)) return false;
        if (System.currentTimeMillis() > dataConfig.getLong("punishments." + u)) {
            removePunishment(u);
            return false;
        }
        return true;
    }

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
        });
    }

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

        int totalOnline = Bukkit.getOnlinePlayers().size();
        int punishedOnline = 0;
        int totalPunished = 0;

        if (dataConfig.contains("punishments")) {
            for (String key : dataConfig.getConfigurationSection("punishments").getKeys(false)) {
                UUID u = UUID.fromString(key);
                if (isPunished(u)) {
                    totalPunished++;
                    if (Bukkit.getPlayer(u) != null) punishedOnline++;
                }
            }
        }

        int normalPlayers = totalOnline - punishedOnline;
        gui.setItem(17, createGuiItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, ChatColor.AQUA + "Players: " + ChatColor.WHITE + normalPlayers));
        gui.setItem(26, createGuiItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, ChatColor.RED + "Punished: " + ChatColor.WHITE + totalPunished));

        p.openInventory(gui);
    }

    private void openPunishedListMenu(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, GUI_PUNISHED_LIST);
        if (dataConfig.contains("punishments")) {
            for (String key : dataConfig.getConfigurationSection("punishments").getKeys(false)) {
                UUID u = UUID.fromString(key);
                if (isPunished(u)) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                    ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta meta = (SkullMeta) head.getItemMeta();
                    meta.setOwningPlayer(op);
                    meta.setDisplayName(ChatColor.RED + op.getName());
                    head.setItemMeta(meta);
                    gui.addItem(head);
                }
            }
        }
        gui.setItem(53, createGuiItem(Material.REDSTONE, ChatColor.RED + "Back to Main Menu"));
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

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (!title.equals(GUI_MAIN) && !title.equals(GUI_PLAYER_LIST) && !title.equals(GUI_PUNISHED_LIST) 
            && !title.equals(GUI_TICKET_LIST) && !title.startsWith(GUI_PLAYER_ACTION) && !title.startsWith(GUI_NOTES_VIEW)) return;

        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;
        Player p = (Player) e.getWhoClicked();
        Material type = e.getCurrentItem().getType();
        String itemName = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());

        if (title.equals(GUI_MAIN)) {
            if (e.getRawSlot() == 26) { openPunishedListMenu(p); return; }
            switch(type) {
                case PLAYER_HEAD: openPlayerListMenu(p); break;
                case REDSTONE: p.closeInventory(); break;
                // ... Existing logic for weather/time/gamemode stays here
            }
        } else if (title.equals(GUI_PUNISHED_LIST)) {
            if (type == Material.PLAYER_HEAD) openPlayerActionMenu(p, itemName);
            else if (type == Material.REDSTONE) openMainMenu(p);
        } else if (title.equals(GUI_PLAYER_LIST)) {
            if (type == Material.PLAYER_HEAD) openPlayerActionMenu(p, itemName);
            else if (type == Material.REDSTONE) openMainMenu(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isPunished(p.getUniqueId())) {
            punishTeam.addEntry(p.getName());
        }

        if (p.hasPermission("rmt.admin")) {
            boolean hasTool = false;
            for (ItemStack item : p.getInventory().getContents()) {
                if (item != null && item.hasItemMeta() && item.getItemMeta().getDisplayName().equals(TOOL_NAME)) {
                    hasTool = true; break;
                }
            }
            if (!hasTool) {
                ItemStack tool = new ItemStack(Material.DIAMOND);
                ItemMeta meta = tool.getItemMeta();
                meta.setDisplayName(TOOL_NAME);
                meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                tool.setItemMeta(meta);
                p.getInventory().addItem(tool);
            }
        }
    }

    @EventHandler
    public void onToolUse(PlayerInteractEvent e) {
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) &&
            e.getItem() != null && e.getItem().hasItemMeta() && e.getItem().getItemMeta().getDisplayName().equals(TOOL_NAME)) {
            if (e.getPlayer().hasPermission("rmt.admin")) openMainMenu(e.getPlayer());
        }
    }

    private void setPunished(UUID u, long d) {
        dataConfig.set("punishments." + u, System.currentTimeMillis() + d);
        saveDataFile();
        Player p = Bukkit.getPlayer(u);
        if (p != null) punishTeam.addEntry(p.getName());
    }

    private void removePunishment(UUID u) {
        dataConfig.set("punishments." + u, null);
        saveDataFile();
        Player p = Bukkit.getPlayer(u);
        if (p != null) punishTeam.removeEntry(p.getName());
    }

    private ItemStack createGuiItem(Material m, String n) { ItemStack i = new ItemStack(m); ItemMeta im = i.getItemMeta(); im.setDisplayName(n); i.setItemMeta(im); return i; }
}
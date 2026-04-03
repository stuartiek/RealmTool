package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RankService {
    private final JavaRealmTool plugin;
    private static final String RANKS_PATH = "ranks";
    private static final String PLAYER_RANK_PATH = "player_rank";

    public RankService(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    public void handleRankCommand(Player p, String[] args) {
        if (args.length == 0) {
            p.sendMessage(ChatColor.RED + "Usage: /dmt rank <create|remove|list|info|add|addprefix|addperm> ...");
            return;
        }

        String sub = args[0].toLowerCase();
        String rank = null;
        String action = null;
        int actionIndex = 1;

        String maybeRankKey = RANKS_PATH + "." + args[0];
        if (args.length >= 2 && plugin.getRankConfig().contains(maybeRankKey)) {
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

    public void createRank(Player p, String rank) {
        if (rank == null || rank.trim().isEmpty()) {
            p.sendMessage(ChatColor.RED + "Invalid rank name.");
            return;
        }
        String key = RANKS_PATH + "." + rank;
        if (plugin.getRankConfig().contains(key)) {
            p.sendMessage(ChatColor.YELLOW + "Rank already exists: " + rank);
            return;
        }
        plugin.getRankConfig().set(key + ".prefix", "&7[" + rank + "] ");
        plugin.getRankConfig().set(key + ".color", "#aaaaaa");
        plugin.getRankConfig().set(key + ".permissions", new ArrayList<>());
        plugin.getRankConfig().set(key + ".members", new ArrayList<>());
        plugin.saveRankFile();
        p.sendMessage(ChatColor.GREEN + "Rank created: " + rank);
    }

    public void removeRank(Player p, String rank) {
        String key = RANKS_PATH + "." + rank;
        if (!plugin.getRankConfig().contains(key)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }
        plugin.getRankConfig().set(key, null);
        if (plugin.getRankConfig().contains(PLAYER_RANK_PATH)) {
            for (String uuidKey : plugin.getRankConfig().getConfigurationSection(PLAYER_RANK_PATH).getKeys(false)) {
                String assigned = plugin.getRankConfig().getString(PLAYER_RANK_PATH + "." + uuidKey);
                if (assigned != null && assigned.equals(rank)) {
                    plugin.getRankConfig().set(PLAYER_RANK_PATH + "." + uuidKey, null);
                }
            }
        }
        plugin.saveRankFile();
        p.sendMessage(ChatColor.GREEN + "Rank removed: " + rank);
    }

    public void addPlayerToRank(Player p, String rank, String playerName) {
        String key = RANKS_PATH + "." + rank;
        if (!plugin.getRankConfig().contains(key)) {
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
            plugin.applyPermissionGroup(online);
        }
        p.sendMessage(ChatColor.GREEN + "Added " + playerName + " to rank " + rank);
    }

    public void addRankPrefix(Player p, String rank, String prefix) {
        String key = RANKS_PATH + "." + rank;
        if (!plugin.getRankConfig().contains(key)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }
        plugin.getRankConfig().set(key + ".prefix", prefix);
        plugin.getRankConfig().set(key + ".color", plugin.inferHexColorFromPrefix(prefix));
        plugin.saveRankFile();
        p.sendMessage(ChatColor.GREEN + "Set prefix for " + rank + " to: " + prefix);
    }

    public void addRankPermission(Player p, String rank, String perm) {
        String key = RANKS_PATH + "." + rank + ".permissions";
        if (!plugin.getRankConfig().contains(RANKS_PATH + "." + rank)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }
        List<String> perms = new ArrayList<>(plugin.getRankConfig().getStringList(key));
        if (perms.contains(perm)) {
            p.sendMessage(ChatColor.YELLOW + "Rank already has permission: " + perm);
            return;
        }
        perms.add(perm);
        plugin.getRankConfig().set(key, perms);
        plugin.saveRankFile();
        p.sendMessage(ChatColor.GREEN + "Added permission " + perm + " to rank " + rank);
    }

    public void showRankList(Player p) {
        if (!plugin.getRankConfig().contains(RANKS_PATH)) {
            p.sendMessage(ChatColor.YELLOW + "No ranks defined yet.");
            return;
        }
        p.sendMessage(ChatColor.GOLD + "--- Defined Ranks ---");
        for (String rank : plugin.getRankConfig().getConfigurationSection(RANKS_PATH).getKeys(false)) {
            String prefix = plugin.getRankConfig().getString(RANKS_PATH + "." + rank + ".prefix", "");
            List<String> perms = plugin.getRankConfig().getStringList(RANKS_PATH + "." + rank + ".permissions");
            List<String> members = plugin.getRankConfig().getStringList(RANKS_PATH + "." + rank + ".members");
            p.sendMessage(ChatColor.AQUA + rank + ChatColor.GRAY + " " + prefix);
            p.sendMessage(ChatColor.GRAY + "  Permissions: " + (perms.isEmpty() ? "<none>" : String.join(", ", perms)));
            p.sendMessage(ChatColor.GRAY + "  Members: " + (members.isEmpty() ? "<none>" : String.join(", ", members)));
        }
    }

    public void showRankInfo(Player p, String rank) {
        String key = RANKS_PATH + "." + rank;
        if (!plugin.getRankConfig().contains(key)) {
            p.sendMessage(ChatColor.RED + "Rank not found: " + rank);
            return;
        }
        String prefix = plugin.getRankConfig().getString(key + ".prefix", "");
        List<String> perms = plugin.getRankConfig().getStringList(key + ".permissions");
        List<String> members = plugin.getRankConfig().getStringList(key + ".members");
        p.sendMessage(ChatColor.GOLD + "--- Rank: " + rank + " ---");
        p.sendMessage(ChatColor.AQUA + "Prefix: " + ChatColor.RESET + prefix);
        p.sendMessage(ChatColor.AQUA + "Permissions: " + ChatColor.RESET + (perms.isEmpty() ? "<none>" : String.join(", ", perms)));
        p.sendMessage(ChatColor.AQUA + "Members: " + ChatColor.RESET + (members.isEmpty() ? "<none>" : String.join(", ", members)));
    }

    public String getPlayerGroup(UUID uuid) {
        if (!plugin.getRankConfig().contains("groups")) return null;
        for (String groupName : plugin.getRankConfig().getConfigurationSection("groups").getKeys(false)) {
            List<String> members = plugin.getRankConfig().getStringList("groups." + groupName + ".members");
            if (members.contains(uuid.toString())) return groupName;
        }
        return null;
    }

    public String getPlayerRank(UUID uuid) {
        if (plugin.getRankConfig().contains(PLAYER_RANK_PATH + "." + uuid)) {
            return plugin.getRankConfig().getString(PLAYER_RANK_PATH + "." + uuid);
        }
        if (plugin.getRankConfig().contains(RANKS_PATH)) {
            for (String rank : plugin.getRankConfig().getConfigurationSection(RANKS_PATH).getKeys(false)) {
                List<String> members = plugin.getRankConfig().getStringList(RANKS_PATH + "." + rank + ".members");
                if (members.contains(uuid.toString())) return rank;
            }
        }
        return null;
    }

    public void setPlayerRank(UUID uuid, String rank) {
        if (plugin.getRankConfig().contains(RANKS_PATH)) {
            for (String existing : plugin.getRankConfig().getConfigurationSection(RANKS_PATH).getKeys(false)) {
                List<String> members = new ArrayList<>(plugin.getRankConfig().getStringList(RANKS_PATH + "." + existing + ".members"));
                if (members.remove(uuid.toString())) {
                    plugin.getRankConfig().set(RANKS_PATH + "." + existing + ".members", members);
                }
            }
        }
        if (rank != null && !rank.isEmpty()) {
            List<String> members = new ArrayList<>(plugin.getRankConfig().getStringList(RANKS_PATH + "." + rank + ".members"));
            if (!members.contains(uuid.toString())) {
                members.add(uuid.toString());
                plugin.getRankConfig().set(RANKS_PATH + "." + rank + ".members", members);
            }
            plugin.getRankConfig().set(PLAYER_RANK_PATH + "." + uuid, rank);
        } else {
            plugin.getRankConfig().set(PLAYER_RANK_PATH + "." + uuid, null);
        }
        plugin.saveRankFile();
    }
}

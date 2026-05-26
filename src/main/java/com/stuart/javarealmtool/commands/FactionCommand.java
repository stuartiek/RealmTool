package com.stuart.javarealmtool.commands;

import com.stuart.javarealmtool.services.FactionService;
import com.stuart.javarealmtool.services.FactionService.CommandResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class FactionCommand implements CommandExecutor, TabCompleter {

    private final FactionService factionService;

    public FactionCommand(FactionService factionService) {
        this.factionService = factionService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
            return true;
        }
        if (!player.hasPermission("factions.use")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use factions.");
            return true;
        }

        if (args.length == 0) {
            sendInfoOrHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /f create <name>");
                    return true;
                }
                send(player, factionService.createFaction(player, args[1]));
            }
            case "disband" -> send(player, factionService.disbandFaction(player));
            case "invite" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /f invite <player>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                send(player, factionService.invitePlayer(player, target));
            }
            case "join" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /f join <faction>");
                    return true;
                }
                send(player, factionService.joinFaction(player, args[1]));
            }
            case "leave" -> send(player, factionService.leaveFaction(player));
            case "kick" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /f kick <player>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                send(player, factionService.kickMember(player, target));
            }
            case "promote" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /f promote <player>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                send(player, factionService.promoteMember(player, target));
            }
            case "demote" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /f demote <player>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                send(player, factionService.demoteMember(player, target));
            }
            case "claim" -> send(player, factionService.claimChunk(player, player.getLocation()));
            case "unclaim" -> send(player, factionService.unclaimChunk(player, player.getLocation()));
            case "sethome" -> send(player, factionService.setFactionHome(player));
            case "home" -> send(player, factionService.teleportFactionHome(player));
            case "delhome", "clearhome" -> send(player, factionService.clearFactionHome(player));
            case "ally" -> setRelation(player, args, FactionService.Relation.ALLY);
            case "enemy" -> setRelation(player, args, FactionService.Relation.ENEMY);
            case "neutral" -> setRelation(player, args, FactionService.Relation.NEUTRAL);
            case "chat" -> send(player, factionService.toggleFactionChat(player));
            case "log", "logs" -> {
                List<String> logs = factionService.getRecentLogs(player.getUniqueId());
                if (logs.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Your faction has no logs yet.");
                } else {
                    player.sendMessage(ChatColor.GOLD + "Faction Logs");
                    logs.forEach(line -> player.sendMessage(ChatColor.GRAY + line));
                }
            }
            case "power" -> {
                if (args.length == 1) {
                    player.sendMessage(factionService.getPowerSummary(player.getUniqueId()));
                } else {
                    player.sendMessage(factionService.getPowerSummaryForTarget(args[1]));
                }
            }
            case "info", "show", "who" -> factionService.getFactionInfoLines(args.length > 1 ? args[1] : null, player.getUniqueId())
                .forEach(player::sendMessage);
            case "help", "?" -> sendHelp(player);
            default -> sendHelp(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("create", "disband", "invite", "join", "leave", "kick", "promote", "demote", "claim", "unclaim", "sethome", "home", "delhome", "ally", "enemy", "neutral", "chat", "log", "power", "info", "help"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (Arrays.asList("join", "ally", "enemy", "neutral", "info").contains(sub)) {
                return filter(factionService.getFactionNames(), args[1]);
            }
            if (Arrays.asList("invite", "kick", "promote", "demote").contains(sub)) {
                List<String> candidates = new ArrayList<>();
                if (sender instanceof Player player) {
                    if (sub.equals("kick") || sub.equals("promote") || sub.equals("demote")) {
                        candidates.addAll(factionService.getMemberNames(player.getUniqueId()));
                    } else {
                        Bukkit.getOnlinePlayers().forEach(online -> candidates.add(online.getName()));
                    }
                }
                return filter(candidates, args[1]);
            }
        }
        return Collections.emptyList();
    }

    private void setRelation(Player player, String[] args, FactionService.Relation relation) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /f " + relation.name().toLowerCase(Locale.ROOT) + " <faction>");
            return;
        }
        send(player, factionService.setRelation(player, args[1], relation));
    }

    private void send(Player player, CommandResult result) {
        player.sendMessage(result.message());
    }

    private void sendInfoOrHelp(Player player) {
        if (factionService.isInFaction(player.getUniqueId())) {
            factionService.getFactionInfoLines(null, player.getUniqueId()).forEach(player::sendMessage);
            player.sendMessage(ChatColor.GRAY + "Use /f help for commands.");
        } else {
            sendHelp(player);
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "Factions Commands");
        player.sendMessage(ChatColor.YELLOW + "/f create <name>" + ChatColor.GRAY + " - Create a faction");
        player.sendMessage(ChatColor.YELLOW + "/f invite <player>" + ChatColor.GRAY + " - Invite a player");
        player.sendMessage(ChatColor.YELLOW + "/f join <faction>" + ChatColor.GRAY + " - Accept an invite");
        player.sendMessage(ChatColor.YELLOW + "/f claim" + ChatColor.GRAY + " - Claim the current chunk");
        player.sendMessage(ChatColor.YELLOW + "/f sethome" + ChatColor.GRAY + " - Set the faction home inside your claim");
        player.sendMessage(ChatColor.YELLOW + "/f home" + ChatColor.GRAY + " - Teleport to the faction home");
        player.sendMessage(ChatColor.YELLOW + "/f power [player|faction]" + ChatColor.GRAY + " - View power");
        player.sendMessage(ChatColor.YELLOW + "/f ally|enemy|neutral <faction>" + ChatColor.GRAY + " - Set relations");
        player.sendMessage(ChatColor.YELLOW + "/f chat" + ChatColor.GRAY + " - Toggle faction chat");
        player.sendMessage(ChatColor.YELLOW + "/f log" + ChatColor.GRAY + " - View faction logs");
    }

    private List<String> filter(List<String> values, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
            .filter(value -> value != null && value.toLowerCase(Locale.ROOT).startsWith(lowered))
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .collect(Collectors.toList());
    }
}
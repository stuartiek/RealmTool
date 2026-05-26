package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class FactionService {

    public record CommandResult(boolean success, String message) {}

    public enum FactionRole {
        LEADER,
        OFFICER,
        MEMBER,
        RECRUIT;

        public boolean canInvite() {
            return this == LEADER || this == OFFICER;
        }

        public boolean canKick() {
            return this == LEADER || this == OFFICER;
        }

        public boolean canClaim() {
            return this == LEADER || this == OFFICER;
        }

        public boolean canManageRelations() {
            return this == LEADER || this == OFFICER;
        }

        public boolean canSetHome() {
            return this == LEADER || this == OFFICER;
        }

        public boolean isHigherThan(FactionRole other) {
            return this.ordinal() < other.ordinal();
        }

        public FactionRole promote() {
            return switch (this) {
                case RECRUIT -> MEMBER;
                case MEMBER -> OFFICER;
                case OFFICER, LEADER -> this;
            };
        }

        public FactionRole demote() {
            return switch (this) {
                case LEADER, RECRUIT -> this;
                case OFFICER -> MEMBER;
                case MEMBER -> RECRUIT;
            };
        }

        public static FactionRole fromString(String value) {
            if (value == null || value.isBlank()) {
                return RECRUIT;
            }
            try {
                return FactionRole.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return RECRUIT;
            }
        }
    }

    public enum Relation {
        ALLY,
        ENEMY,
        NEUTRAL
    }

    private static final String ROOT = "factions_core";
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final DecimalFormat POWER_FORMAT = new DecimalFormat("0.0");

    private final JavaRealmTool plugin;

    public FactionService(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    public void ensureConfigDefaults() {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        changed |= ensureConfig(config, ROOT + ".enabled", true);
        changed |= ensureConfig(config, ROOT + ".min_name_length", 3);
        changed |= ensureConfig(config, ROOT + ".max_name_length", 12);
        changed |= ensureConfig(config, ROOT + ".power.max_per_player", 10.0D);
        changed |= ensureConfig(config, ROOT + ".power.death_penalty", 2.0D);
        changed |= ensureConfig(config, ROOT + ".power.regen_per_hour", 1.0D);
        changed |= ensureConfig(config, ROOT + ".power.min_power", 0.0D);
        changed |= ensureConfig(config, ROOT + ".raid.require_overclaim_for_explosions", true);
        changed |= ensureConfig(config, ROOT + ".raid.alert_cooldown_seconds", 300);
        changed |= ensureConfig(config, ROOT + ".raid.web_push", true);
        changed |= ensureConfig(config, ROOT + ".logs.max_entries", 30);
        changed |= ensureConfig(config, ROOT + ".homes.allow_teleport", true);
        changed |= ensureConfig(config, ROOT + ".homes.require_safe_claim", true);
        changed |= ensureConfig(config, ROOT + ".discord.enabled", false);
        changed |= ensureConfig(config, ROOT + ".discord.webhook_override", "");
        if (changed) {
            plugin.saveConfig();
        }
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(ROOT + ".enabled", true);
    }

    public CommandResult createFaction(Player player, String factionName) {
        if (!isEnabled()) {
            return fail("Factions are currently disabled.");
        }
        if (getPlayerFactionId(player.getUniqueId()) != null) {
            return fail("You are already in a faction.");
        }

        String trimmed = factionName == null ? "" : factionName.trim();
        int min = plugin.getConfig().getInt(ROOT + ".min_name_length", 3);
        int max = plugin.getConfig().getInt(ROOT + ".max_name_length", 12);
        if (trimmed.length() < min || trimmed.length() > max) {
            return fail("Faction names must be between " + min + " and " + max + " characters.");
        }
        if (!VALID_NAME.matcher(trimmed).matches()) {
            return fail("Faction names can only use letters, numbers, and underscores.");
        }
        if (findFactionIdByName(trimmed) != null) {
            return fail("That faction name is already taken.");
        }

        String factionId = UUID.randomUUID().toString();
        FileConfiguration data = data();
        String base = factionPath(factionId);
        data.set(base + ".name", trimmed);
        data.set(base + ".nameLower", trimmed.toLowerCase(Locale.ROOT));
        data.set(base + ".members." + player.getUniqueId(), FactionRole.LEADER.name());
        data.set(base + ".claims", new ArrayList<String>());
        data.set(base + ".invites", new ArrayList<String>());
        data.set(base + ".relations.allies", new ArrayList<String>());
        data.set(base + ".relations.enemies", new ArrayList<String>());
        data.set(base + ".logs", new ArrayList<String>());
        data.set(ROOT + ".players." + player.getUniqueId() + ".faction", factionId);
        addFactionLog(factionId, player.getName() + " created the faction.");
        plugin.logAction(player.getName(), "faction_create", trimmed);
        plugin.saveDataFile();
        return ok(ChatColor.GREEN + "Created faction " + ChatColor.AQUA + trimmed + ChatColor.GREEN + ".");
    }

    public CommandResult disbandFaction(Player player) {
        String factionId = getPlayerFactionId(player.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        if (getRole(player.getUniqueId()) != FactionRole.LEADER) {
            return fail("Only the faction leader can disband the faction.");
        }

        String factionName = getFactionName(factionId);
        for (String memberId : getMemberIds(factionId)) {
            data().set(ROOT + ".players." + memberId + ".faction", null);
            Player online = Bukkit.getPlayer(UUID.fromString(memberId));
            if (online != null && !online.getUniqueId().equals(player.getUniqueId())) {
                online.sendMessage(ChatColor.RED + "Your faction " + ChatColor.AQUA + factionName + ChatColor.RED + " has been disbanded.");
            }
        }
        for (String otherFactionId : getFactionIds()) {
            if (!otherFactionId.equals(factionId)) {
                removeRelation(otherFactionId, factionId);
            }
        }
        data().set(factionPath(factionId), null);
        plugin.logAction(player.getName(), "faction_disband", factionName);
        plugin.saveDataFile();
        return ok(ChatColor.RED + "Disbanded faction " + ChatColor.AQUA + factionName + ChatColor.RED + ".");
    }

    public CommandResult invitePlayer(Player actor, OfflinePlayer target) {
        String factionId = getPlayerFactionId(actor.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        FactionRole role = getRole(actor.getUniqueId());
        if (role == null || !role.canInvite()) {
            return fail("Your faction rank cannot invite players.");
        }
        if (target.getUniqueId() == null) {
            return fail("That player could not be resolved.");
        }
        if (getPlayerFactionId(target.getUniqueId()) != null) {
            return fail("That player is already in a faction.");
        }

        List<String> invites = data().getStringList(factionPath(factionId) + ".invites");
        String targetId = target.getUniqueId().toString();
        if (!invites.contains(targetId)) {
            invites.add(targetId);
            data().set(factionPath(factionId) + ".invites", invites);
            addFactionLog(factionId, actor.getName() + " invited " + safePlayerName(target) + ".");
            plugin.logAction(actor.getName(), "faction_invite", safePlayerName(target) + " -> " + getFactionName(factionId));
            plugin.saveDataFile();
        }

        Player onlineTarget = Bukkit.getPlayer(target.getUniqueId());
        if (onlineTarget != null) {
            onlineTarget.sendMessage(ChatColor.AQUA + actor.getName() + ChatColor.YELLOW + " invited you to join "
                + ChatColor.AQUA + getFactionName(factionId) + ChatColor.YELLOW + ". Use /f join " + getFactionName(factionId));
        }
        return ok(ChatColor.GREEN + "Invited " + ChatColor.AQUA + safePlayerName(target) + ChatColor.GREEN + ".");
    }

    public CommandResult joinFaction(Player player, String factionName) {
        if (getPlayerFactionId(player.getUniqueId()) != null) {
            return fail("Leave your current faction first.");
        }

        String factionId = findFactionIdByName(factionName);
        if (factionId == null) {
            return fail("Faction not found.");
        }
        List<String> invites = data().getStringList(factionPath(factionId) + ".invites");
        String playerId = player.getUniqueId().toString();
        if (!invites.contains(playerId)) {
            return fail("You do not have an invite to that faction.");
        }

        invites.remove(playerId);
        data().set(factionPath(factionId) + ".invites", invites);
        data().set(factionPath(factionId) + ".members." + playerId, FactionRole.RECRUIT.name());
        data().set(ROOT + ".players." + playerId + ".faction", factionId);
        addFactionLog(factionId, player.getName() + " joined the faction.");
        plugin.logAction(player.getName(), "faction_join", getFactionName(factionId));
        plugin.saveDataFile();
        broadcastToFaction(factionId, ChatColor.AQUA + player.getName() + ChatColor.GREEN + " joined the faction.");
        return ok(ChatColor.GREEN + "Joined faction " + ChatColor.AQUA + getFactionName(factionId) + ChatColor.GREEN + ".");
    }

    public CommandResult leaveFaction(Player player) {
        String factionId = getPlayerFactionId(player.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }

        List<String> members = getMemberIds(factionId);
        FactionRole role = getRole(player.getUniqueId());
        if (role == FactionRole.LEADER && members.size() > 1) {
            return fail("Transfer leadership or kick members before leaving the faction.");
        }

        String factionName = getFactionName(factionId);
        data().set(factionPath(factionId) + ".members." + player.getUniqueId(), null);
        data().set(ROOT + ".players." + player.getUniqueId() + ".faction", null);
        if (members.size() <= 1) {
            data().set(factionPath(factionId), null);
        } else {
            addFactionLog(factionId, player.getName() + " left the faction.");
        }
        plugin.logAction(player.getName(), "faction_leave", factionName);
        plugin.saveDataFile();
        return ok(ChatColor.YELLOW + "You left faction " + ChatColor.AQUA + factionName + ChatColor.YELLOW + ".");
    }

    public CommandResult kickMember(Player actor, OfflinePlayer target) {
        String factionId = getPlayerFactionId(actor.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        if (target.getUniqueId() == null) {
            return fail("That player could not be resolved.");
        }
        if (actor.getUniqueId().equals(target.getUniqueId())) {
            return fail("Use /f leave to remove yourself.");
        }
        if (!factionId.equals(getPlayerFactionId(target.getUniqueId()))) {
            return fail("That player is not in your faction.");
        }

        FactionRole actorRole = getRole(actor.getUniqueId());
        FactionRole targetRole = getRole(target.getUniqueId());
        if (actorRole == null || !actorRole.canKick()) {
            return fail("Your faction rank cannot kick players.");
        }
        if (targetRole == null || !actorRole.isHigherThan(targetRole)) {
            return fail("You can only kick players below your rank.");
        }

        data().set(factionPath(factionId) + ".members." + target.getUniqueId(), null);
        data().set(ROOT + ".players." + target.getUniqueId() + ".faction", null);
        addFactionLog(factionId, actor.getName() + " kicked " + safePlayerName(target) + ".");
        plugin.logAction(actor.getName(), "faction_kick", safePlayerName(target));
        plugin.saveDataFile();
        Player onlineTarget = Bukkit.getPlayer(target.getUniqueId());
        if (onlineTarget != null) {
            onlineTarget.sendMessage(ChatColor.RED + "You were kicked from " + ChatColor.AQUA + getFactionName(factionId) + ChatColor.RED + ".");
        }
        return ok(ChatColor.GREEN + "Kicked " + ChatColor.AQUA + safePlayerName(target) + ChatColor.GREEN + ".");
    }

    public CommandResult promoteMember(Player actor, OfflinePlayer target) {
        return changeRole(actor, target, true);
    }

    public CommandResult demoteMember(Player actor, OfflinePlayer target) {
        return changeRole(actor, target, false);
    }

    public CommandResult claimChunk(Player player, Location location) {
        String factionId = getPlayerFactionId(player.getUniqueId());
        if (factionId == null) {
            return fail("Join a faction before claiming land.");
        }
        FactionRole role = getRole(player.getUniqueId());
        if (role == null || !role.canClaim()) {
            return fail("Your faction rank cannot claim land.");
        }
        if (!isClaimingAllowedInWorld(location)) {
            return fail("Claims are not enabled in this world.");
        }

        String claimKey = toClaimKey(location);
        String ownerFactionId = getClaimFactionId(claimKey);
        if (factionId.equals(ownerFactionId)) {
            return fail("Your faction already owns this chunk.");
        }
        if (ownerFactionId != null) {
            return fail("This chunk is already claimed by " + getFactionName(ownerFactionId) + ".");
        }

        int currentClaims = getClaimCount(factionId);
        int maxClaims = getMaxClaims(factionId);
        if (currentClaims >= maxClaims) {
            return fail("Your faction cannot hold more land than its power. Current: " + currentClaims + "/" + maxClaims);
        }

        List<String> claims = data().getStringList(factionPath(factionId) + ".claims");
        claims.add(claimKey);
        data().set(factionPath(factionId) + ".claims", claims);
        addFactionLog(factionId, player.getName() + " claimed chunk " + formatChunk(claimKey) + ".");
        plugin.logAction(player.getName(), "faction_claim", getFactionName(factionId) + " " + formatChunk(claimKey));
        plugin.saveDataFile();
        return ok(ChatColor.GREEN + "Claimed chunk for " + ChatColor.AQUA + getFactionName(factionId)
            + ChatColor.GREEN + ". Claims: " + (currentClaims + 1) + "/" + maxClaims);
    }

    public CommandResult unclaimChunk(Player player, Location location) {
        String factionId = getPlayerFactionId(player.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        FactionRole role = getRole(player.getUniqueId());
        if (role == null || !role.canClaim()) {
            return fail("Your faction rank cannot unclaim land.");
        }

        String claimKey = toClaimKey(location);
        String ownerFactionId = getClaimFactionId(claimKey);
        if (!factionId.equals(ownerFactionId)) {
            return fail("Your faction does not own this chunk.");
        }

        List<String> claims = data().getStringList(factionPath(factionId) + ".claims");
        claims.remove(claimKey);
        data().set(factionPath(factionId) + ".claims", claims);
        addFactionLog(factionId, player.getName() + " unclaimed chunk " + formatChunk(claimKey) + ".");
        plugin.logAction(player.getName(), "faction_unclaim", getFactionName(factionId) + " " + formatChunk(claimKey));
        plugin.saveDataFile();
        return ok(ChatColor.YELLOW + "Unclaimed chunk for " + ChatColor.AQUA + getFactionName(factionId) + ChatColor.YELLOW + ".");
    }

    public CommandResult setRelation(Player actor, String targetFactionName, Relation relation) {
        String factionId = getPlayerFactionId(actor.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        FactionRole role = getRole(actor.getUniqueId());
        if (role == null || !role.canManageRelations()) {
            return fail("Your faction rank cannot change relations.");
        }

        String targetFactionId = findFactionIdByName(targetFactionName);
        if (targetFactionId == null) {
            return fail("Faction not found.");
        }
        if (factionId.equals(targetFactionId)) {
            return fail("You cannot change relations with your own faction.");
        }

        removeRelation(factionId, targetFactionId);
        removeRelation(targetFactionId, factionId);
        if (relation == Relation.ALLY) {
            addRelation(factionId, targetFactionId, true);
            addRelation(targetFactionId, factionId, true);
        } else if (relation == Relation.ENEMY) {
            addRelation(factionId, targetFactionId, false);
            addRelation(targetFactionId, factionId, false);
        }

        String relationText = relation.name().toLowerCase(Locale.ROOT);
        addFactionLog(factionId, actor.getName() + " set relation with " + getFactionName(targetFactionId) + " to " + relationText + ".");
        addFactionLog(targetFactionId, getFactionName(factionId) + " set relation to " + relationText + ".");
        plugin.logAction(actor.getName(), "faction_relation", getFactionName(factionId) + " -> " + relationText + " " + getFactionName(targetFactionId));
        plugin.saveDataFile();
        return ok(ChatColor.GREEN + "Relation with " + ChatColor.AQUA + getFactionName(targetFactionId)
            + ChatColor.GREEN + " set to " + relationText + ".");
    }

    public CommandResult toggleFactionChat(Player player) {
        String factionId = getPlayerFactionId(player.getUniqueId());
        if (factionId == null) {
            return fail("Join a faction before using faction chat.");
        }
        boolean enabled = !isFactionChatEnabled(player.getUniqueId());
        data().set(ROOT + ".chat." + player.getUniqueId(), enabled);
        plugin.saveDataFile();
        return ok(enabled
            ? ChatColor.GREEN + "Faction chat enabled. Your normal chat will go to faction members."
            : ChatColor.YELLOW + "Faction chat disabled.");
    }

    public CommandResult setFactionHome(Player player) {
        String factionId = getPlayerFactionId(player.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        FactionRole role = getRole(player.getUniqueId());
        if (role == null || !role.canSetHome()) {
            return fail("Your faction rank cannot set the faction home.");
        }
        if (plugin.getConfig().getBoolean(ROOT + ".homes.require_safe_claim", true)) {
            String claimFactionId = getClaimFactionId(player.getLocation());
            if (!factionId.equals(claimFactionId) || isOverclaimed(factionId)) {
                return fail("Faction home must be set inside a protected claim owned by your faction.");
            }
        }
        setHomeLocation(factionId, player.getLocation());
        addFactionLog(factionId, player.getName() + " updated the faction home.");
        plugin.logAction(player.getName(), "faction_home_set", getFactionName(factionId));
        plugin.saveDataFile();
        return ok(ChatColor.GREEN + "Faction home set for " + ChatColor.AQUA + getFactionName(factionId) + ChatColor.GREEN + ".");
    }

    public CommandResult teleportFactionHome(Player player) {
        if (!plugin.getConfig().getBoolean(ROOT + ".homes.allow_teleport", true)) {
            return fail("Faction home teleport is disabled.");
        }
        String factionId = getPlayerFactionId(player.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        Location home = getHomeLocation(factionId);
        if (home == null) {
            return fail("Your faction has not set a home yet.");
        }
        player.teleport(home);
        return ok(ChatColor.GREEN + "Teleported to the faction home for " + ChatColor.AQUA + getFactionName(factionId) + ChatColor.GREEN + ".");
    }

    public CommandResult clearFactionHome(Player player) {
        String factionId = getPlayerFactionId(player.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        FactionRole role = getRole(player.getUniqueId());
        if (role == null || !role.canSetHome()) {
            return fail("Your faction rank cannot clear the faction home.");
        }
        if (getHomeLocation(factionId) == null) {
            return fail("Your faction does not have a home set.");
        }
        data().set(factionPath(factionId) + ".home", null);
        addFactionLog(factionId, player.getName() + " cleared the faction home.");
        plugin.logAction(player.getName(), "faction_home_clear", getFactionName(factionId));
        plugin.saveDataFile();
        return ok(ChatColor.YELLOW + "Faction home cleared for " + ChatColor.AQUA + getFactionName(factionId) + ChatColor.YELLOW + ".");
    }

    public boolean isFactionChatEnabled(UUID uuid) {
        return data().getBoolean(ROOT + ".chat." + uuid, false);
    }

    public void sendFactionChat(Player sender, String message) {
        String factionId = getPlayerFactionId(sender.getUniqueId());
        if (factionId == null) {
            sender.sendMessage(ChatColor.RED + "You are not in a faction.");
            return;
        }
        String formatted = ChatColor.DARK_AQUA + "[F] " + ChatColor.AQUA + sender.getName() + ChatColor.WHITE + ": " + message;
        broadcastToFaction(factionId, formatted);
        addFactionLog(factionId, "[chat] " + sender.getName() + ": " + message);
        plugin.addChatLog("Faction:" + getFactionName(factionId), sender.getName() + ": " + message);
    }

    public void handlePlayerDeath(Player victim) {
        double currentPower = getPlayerPower(victim.getUniqueId());
        double minPower = plugin.getConfig().getDouble(ROOT + ".power.min_power", 0.0D);
        double penalty = plugin.getConfig().getDouble(ROOT + ".power.death_penalty", 2.0D);
        double updated = Math.max(minPower, currentPower - penalty);
        setPlayerPower(victim.getUniqueId(), updated);
        String factionId = getPlayerFactionId(victim.getUniqueId());
        if (factionId != null) {
            broadcastToFaction(factionId, ChatColor.RED + victim.getName() + " died. Faction power is now " + POWER_FORMAT.format(getFactionPower(factionId)) + ".");
            addFactionLog(factionId, victim.getName() + " died and lost " + POWER_FORMAT.format(penalty) + " power.");
        }
    }

    public boolean canBuild(Player player, Location location) {
        String ownerFactionId = getClaimFactionId(location);
        if (ownerFactionId == null) {
            return true;
        }
        String playerFactionId = getPlayerFactionId(player.getUniqueId());
        return ownerFactionId.equals(playerFactionId);
    }

    public boolean isFactionClaimed(Location location) {
        return getClaimFactionId(location) != null;
    }

    public void filterExplosionBlocks(List<Block> blocks) {
        handleExplosion(blocks, "Explosion");
    }

    public void handleExplosion(List<Block> blocks, String sourceName) {
        boolean requireOverclaim = plugin.getConfig().getBoolean(ROOT + ".raid.require_overclaim_for_explosions", true);
        Map<String, Location> impactedFactions = new HashMap<>();

        blocks.removeIf(block -> {
            String factionId = getClaimFactionId(block.getLocation());
            if (factionId == null) {
                return false;
            }
            if (requireOverclaim && !isOverclaimed(factionId)) {
                return true;
            }
            impactedFactions.putIfAbsent(factionId, block.getLocation());
            return false;
        });

        for (Map.Entry<String, Location> entry : impactedFactions.entrySet()) {
            triggerRaidAlert(entry.getKey(), entry.getValue(), sourceName);
        }
    }

    public String describeClaim(Location location) {
        String factionId = getClaimFactionId(location);
        if (factionId == null) {
            return ChatColor.GRAY + "Unclaimed";
        }
        String state = isOverclaimed(factionId) ? ChatColor.RED + "Raidable" : ChatColor.GREEN + "Protected";
        return ChatColor.YELLOW + "Claimed by " + ChatColor.AQUA + getFactionName(factionId)
            + ChatColor.YELLOW + " | " + state;
    }

    public List<String> getFactionNames() {
        List<String> names = new ArrayList<>();
        for (String factionId : getFactionIds()) {
            names.add(getFactionName(factionId));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public List<String> getRecentLogs(UUID playerId) {
        String factionId = getPlayerFactionId(playerId);
        if (factionId == null) {
            return Collections.emptyList();
        }
        List<String> logs = new ArrayList<>(data().getStringList(factionPath(factionId) + ".logs"));
        Collections.reverse(logs);
        return logs;
    }

    public List<String> getFactionInfoLines(String factionNameOrNull, UUID requester) {
        String factionId = factionNameOrNull == null ? getPlayerFactionId(requester) : findFactionIdByName(factionNameOrNull);
        if (factionId == null) {
            return Collections.singletonList(ChatColor.RED + "Faction not found.");
        }

        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GOLD + "Faction: " + ChatColor.AQUA + getFactionName(factionId));
        lines.add(ChatColor.YELLOW + "Power: " + ChatColor.WHITE + POWER_FORMAT.format(getFactionPower(factionId))
            + ChatColor.GRAY + " | Claims: " + getClaimCount(factionId) + "/" + getMaxClaims(factionId));
        lines.add(ChatColor.YELLOW + "Status: " + (isOverclaimed(factionId) ? ChatColor.RED + "Overclaimed / Raidable" : ChatColor.GREEN + "Stable"));
        lines.add(ChatColor.YELLOW + "Home: " + ChatColor.WHITE + describeHome(factionId));
        lines.add(ChatColor.YELLOW + "Members: " + ChatColor.WHITE + String.join(", ", getFormattedMemberNames(factionId)));
        lines.add(ChatColor.YELLOW + "Allies: " + ChatColor.WHITE + formatFactionList(data().getStringList(factionPath(factionId) + ".relations.allies")));
        lines.add(ChatColor.YELLOW + "Enemies: " + ChatColor.WHITE + formatFactionList(data().getStringList(factionPath(factionId) + ".relations.enemies")));
        return lines;
    }

    public List<Map<String, Object>> getFactionSummaries() {
        List<Map<String, Object>> factions = new ArrayList<>();
        for (String factionId : getFactionIds()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", factionId);
            entry.put("name", getFactionName(factionId));
            entry.put("power", Double.parseDouble(POWER_FORMAT.format(getFactionPower(factionId))));
            entry.put("claims", getClaimCount(factionId));
            entry.put("claimCapacity", getMaxClaims(factionId));
            entry.put("overclaimed", isOverclaimed(factionId));
            entry.put("members", getFormattedMemberNames(factionId));
            entry.put("allies", mapFactionIdsToNames(data().getStringList(factionPath(factionId) + ".relations.allies")));
            entry.put("enemies", mapFactionIdsToNames(data().getStringList(factionPath(factionId) + ".relations.enemies")));
            entry.put("home", describeHome(factionId));
            entry.put("logs", getRecentLogsForFaction(factionId, 10));
            entry.put("lastRaidAlert", data().getLong(factionPath(factionId) + ".lastRaidAlert", 0L));
            factions.add(entry);
        }
        factions.sort(Comparator.comparing(entry -> String.valueOf(entry.get("name")), String.CASE_INSENSITIVE_ORDER));
        return factions;
    }

    public List<String> getFactionLogsByName(String factionName, int limit) {
        String factionId = findFactionIdByName(factionName);
        if (factionId == null) {
            return Collections.emptyList();
        }
        return getRecentLogsForFaction(factionId, limit);
    }

    public List<String> getRecentRaidAlerts(int limit) {
        List<String> alerts = new ArrayList<>(data().getStringList(ROOT + ".raid.alerts"));
        Collections.reverse(alerts);
        if (limit > 0 && alerts.size() > limit) {
            return new ArrayList<>(alerts.subList(0, limit));
        }
        return alerts;
    }

    public String getPowerSummary(UUID playerId) {
        String factionId = getPlayerFactionId(playerId);
        String ownPower = POWER_FORMAT.format(getPlayerPower(playerId));
        if (factionId == null) {
            return ChatColor.YELLOW + "Your power: " + ChatColor.AQUA + ownPower;
        }
        return ChatColor.YELLOW + "Your power: " + ChatColor.AQUA + ownPower + ChatColor.GRAY + " | Faction power: "
            + ChatColor.AQUA + POWER_FORMAT.format(getFactionPower(factionId)) + ChatColor.GRAY + " | Claims: "
            + getClaimCount(factionId) + "/" + getMaxClaims(factionId);
    }

    public String getPowerSummaryForTarget(String target) {
        String factionId = findFactionIdByName(target);
        if (factionId != null) {
            return ChatColor.YELLOW + "Faction power for " + ChatColor.AQUA + getFactionName(factionId) + ChatColor.YELLOW + ": "
                + ChatColor.AQUA + POWER_FORMAT.format(getFactionPower(factionId)) + ChatColor.GRAY + " | Claims: "
                + getClaimCount(factionId) + "/" + getMaxClaims(factionId);
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(target);
        return ChatColor.YELLOW + "Power for " + ChatColor.AQUA + safePlayerName(player) + ChatColor.YELLOW + ": "
            + ChatColor.AQUA + POWER_FORMAT.format(getPlayerPower(player.getUniqueId()));
    }

    public List<String> getMemberNames(UUID requester) {
        String factionId = getPlayerFactionId(requester);
        if (factionId == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (String memberId : getMemberIds(factionId)) {
            OfflinePlayer member = Bukkit.getOfflinePlayer(UUID.fromString(memberId));
            names.add(safePlayerName(member));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public FactionRole getRole(UUID playerId) {
        String factionId = getPlayerFactionId(playerId);
        if (factionId == null) {
            return null;
        }
        return FactionRole.fromString(data().getString(factionPath(factionId) + ".members." + playerId));
    }

    public boolean isInFaction(UUID playerId) {
        return getPlayerFactionId(playerId) != null;
    }

    private CommandResult changeRole(Player actor, OfflinePlayer target, boolean promote) {
        String factionId = getPlayerFactionId(actor.getUniqueId());
        if (factionId == null) {
            return fail("You are not in a faction.");
        }
        if (target.getUniqueId() == null) {
            return fail("That player could not be resolved.");
        }
        if (!factionId.equals(getPlayerFactionId(target.getUniqueId()))) {
            return fail("That player is not in your faction.");
        }
        if (actor.getUniqueId().equals(target.getUniqueId())) {
            return fail("You cannot change your own rank with this command.");
        }

        FactionRole actorRole = getRole(actor.getUniqueId());
        FactionRole targetRole = getRole(target.getUniqueId());
        if (actorRole == null || targetRole == null) {
            return fail("Faction data is incomplete for that player.");
        }
        if (!actorRole.isHigherThan(targetRole) && actorRole != FactionRole.LEADER) {
            return fail("You can only change ranks below your own.");
        }

        if (promote) {
            if (targetRole == FactionRole.OFFICER && actorRole == FactionRole.LEADER) {
                setRole(actor.getUniqueId(), FactionRole.OFFICER);
                setRole(target.getUniqueId(), FactionRole.LEADER);
                addFactionLog(factionId, actor.getName() + " transferred leadership to " + safePlayerName(target) + ".");
                plugin.saveDataFile();
                return ok(ChatColor.GREEN + "Transferred leadership to " + ChatColor.AQUA + safePlayerName(target) + ChatColor.GREEN + ".");
            }
            if (targetRole == FactionRole.LEADER) {
                return fail("That player is already the leader.");
            }
            FactionRole newRole = targetRole.promote();
            if (newRole == targetRole) {
                return fail("That player cannot be promoted further.");
            }
            setRole(target.getUniqueId(), newRole);
            addFactionLog(factionId, actor.getName() + " promoted " + safePlayerName(target) + " to " + newRole.name().toLowerCase(Locale.ROOT) + ".");
            plugin.saveDataFile();
            return ok(ChatColor.GREEN + "Promoted " + ChatColor.AQUA + safePlayerName(target) + ChatColor.GREEN + " to " + newRole.name().toLowerCase(Locale.ROOT) + ".");
        }

        if (targetRole == FactionRole.LEADER) {
            return fail("Leaders cannot be demoted. Transfer leadership instead.");
        }
        FactionRole newRole = targetRole.demote();
        if (newRole == targetRole) {
            return fail("That player cannot be demoted further.");
        }
        if (!actorRole.isHigherThan(newRole) && actorRole != FactionRole.LEADER) {
            return fail("You cannot set a rank equal to or above your own.");
        }
        setRole(target.getUniqueId(), newRole);
        addFactionLog(factionId, actor.getName() + " demoted " + safePlayerName(target) + " to " + newRole.name().toLowerCase(Locale.ROOT) + ".");
        plugin.saveDataFile();
        return ok(ChatColor.YELLOW + "Demoted " + ChatColor.AQUA + safePlayerName(target) + ChatColor.YELLOW + " to " + newRole.name().toLowerCase(Locale.ROOT) + ".");
    }

    private void setRole(UUID playerId, FactionRole role) {
        String factionId = getPlayerFactionId(playerId);
        if (factionId != null) {
            data().set(factionPath(factionId) + ".members." + playerId, role.name());
            data().set(ROOT + ".players." + playerId + ".faction", factionId);
        }
    }

    private boolean ensureConfig(FileConfiguration config, String path, Object value) {
        if (config.contains(path)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private FileConfiguration data() {
        return plugin.getDataConfig();
    }

    private String factionPath(String factionId) {
        return ROOT + ".factions." + factionId;
    }

    private String getPlayerFactionId(UUID playerId) {
        String factionId = data().getString(ROOT + ".players." + playerId + ".faction");
        if (factionId == null || factionId.isBlank()) {
            return null;
        }
        if (!data().contains(factionPath(factionId))) {
            data().set(ROOT + ".players." + playerId + ".faction", null);
            plugin.saveDataFile();
            return null;
        }
        return factionId;
    }

    private String findFactionIdByName(String factionName) {
        if (factionName == null || factionName.isBlank()) {
            return null;
        }
        String expected = factionName.trim().toLowerCase(Locale.ROOT);
        for (String factionId : getFactionIds()) {
            if (expected.equals(data().getString(factionPath(factionId) + ".nameLower", ""))) {
                return factionId;
            }
        }
        return null;
    }

    private List<String> getFactionIds() {
        ConfigurationSection section = data().getConfigurationSection(ROOT + ".factions");
        if (section == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(section.getKeys(false));
    }

    private String getFactionName(String factionId) {
        return data().getString(factionPath(factionId) + ".name", "Unknown");
    }

    private List<String> getMemberIds(String factionId) {
        ConfigurationSection section = data().getConfigurationSection(factionPath(factionId) + ".members");
        if (section == null) {
            return Collections.emptyList();
        }
        List<String> memberIds = new ArrayList<>(section.getKeys(false));
        memberIds.sort(Comparator.naturalOrder());
        return memberIds;
    }

    private List<String> getFormattedMemberNames(String factionId) {
        List<String> members = new ArrayList<>();
        for (String memberId : getMemberIds(factionId)) {
            OfflinePlayer member = Bukkit.getOfflinePlayer(UUID.fromString(memberId));
            FactionRole role = FactionRole.fromString(data().getString(factionPath(factionId) + ".members." + memberId));
            members.add(safePlayerName(member) + " (" + role.name().toLowerCase(Locale.ROOT) + ")");
        }
        return members;
    }

    private String formatFactionList(List<String> factionIds) {
        if (factionIds.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (String factionId : factionIds) {
            names.add(getFactionName(factionId));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", names);
    }

    private List<String> mapFactionIdsToNames(List<String> factionIds) {
        List<String> names = new ArrayList<>();
        for (String factionId : factionIds) {
            names.add(getFactionName(factionId));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void addRelation(String sourceFactionId, String targetFactionId, boolean ally) {
        String path = factionPath(sourceFactionId) + ".relations." + (ally ? "allies" : "enemies");
        List<String> relations = data().getStringList(path);
        if (!relations.contains(targetFactionId)) {
            relations.add(targetFactionId);
            data().set(path, relations);
        }
    }

    private void removeRelation(String sourceFactionId, String targetFactionId) {
        for (String path : Arrays.asList(".relations.allies", ".relations.enemies")) {
            List<String> relations = data().getStringList(factionPath(sourceFactionId) + path);
            if (relations.remove(targetFactionId)) {
                data().set(factionPath(sourceFactionId) + path, relations);
            }
        }
    }

    private String toClaimKey(Location location) {
        return location.getWorld().getName() + ":" + location.getChunk().getX() + ":" + location.getChunk().getZ();
    }

    private String formatChunk(String claimKey) {
        String[] parts = claimKey.split(":");
        if (parts.length != 3) {
            return claimKey;
        }
        return parts[0] + " (" + parts[1] + ", " + parts[2] + ")";
    }

    private boolean isClaimingAllowedInWorld(Location location) {
        if (!plugin.getConfig().getBoolean("factions_world.claims_enabled", true)) {
            return false;
        }
        if (location == null || location.getWorld() == null) {
            return false;
        }
        List<String> allowedWorlds = plugin.getConfig().getStringList("factions_world.claims_allowed_worlds");
        String configuredWorld = plugin.getConfig().getString("factions_world.name", "").trim();
        if (!configuredWorld.isEmpty() && configuredWorld.equalsIgnoreCase(location.getWorld().getName())) {
            return true;
        }
        for (String worldName : allowedWorlds) {
            if (worldName != null && location.getWorld().getName().equalsIgnoreCase(worldName.trim())) {
                return true;
            }
        }
        return false;
    }

    private String getClaimFactionId(Location location) {
        return getClaimFactionId(toClaimKey(location));
    }

    private String getClaimFactionId(String claimKey) {
        for (String factionId : getFactionIds()) {
            List<String> claims = data().getStringList(factionPath(factionId) + ".claims");
            if (claims.contains(claimKey)) {
                return factionId;
            }
        }
        return null;
    }

    private int getClaimCount(String factionId) {
        return data().getStringList(factionPath(factionId) + ".claims").size();
    }

    private String describeHome(String factionId) {
        Location home = getHomeLocation(factionId);
        if (home == null) {
            return "not set";
        }
        return home.getWorld().getName() + " (" + home.getBlockX() + ", " + home.getBlockY() + ", " + home.getBlockZ() + ")";
    }

    private Location getHomeLocation(String factionId) {
        String serialized = data().getString(factionPath(factionId) + ".home");
        if (serialized == null || serialized.isBlank()) {
            return null;
        }
        String[] parts = serialized.split(",");
        if (parts.length != 6) {
            return null;
        }
        try {
            org.bukkit.World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                return null;
            }
            return new Location(
                world,
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Float.parseFloat(parts[4]),
                Float.parseFloat(parts[5])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void setHomeLocation(String factionId, Location location) {
        data().set(factionPath(factionId) + ".home",
            location.getWorld().getName() + "," + location.getX() + "," + location.getY() + "," + location.getZ() + "," + location.getYaw() + "," + location.getPitch());
    }

    private int getMaxClaims(String factionId) {
        return Math.max(0, (int) Math.floor(getFactionPower(factionId)));
    }

    private double getFactionPower(String factionId) {
        double power = 0.0D;
        for (String memberId : getMemberIds(factionId)) {
            power += getPlayerPower(UUID.fromString(memberId));
        }
        return power;
    }

    private boolean isOverclaimed(String factionId) {
        return getClaimCount(factionId) > getMaxClaims(factionId);
    }

    private double getPlayerPower(UUID playerId) {
        double max = plugin.getConfig().getDouble(ROOT + ".power.max_per_player", 10.0D);
        double regenPerHour = plugin.getConfig().getDouble(ROOT + ".power.regen_per_hour", 1.0D);
        long now = System.currentTimeMillis();

        String base = ROOT + ".power.players." + playerId;
        double current = data().contains(base + ".current") ? data().getDouble(base + ".current") : max;
        long lastUpdated = data().getLong(base + ".lastUpdated", now);
        if (regenPerHour > 0 && now > lastUpdated && current < max) {
            double elapsedHours = (now - lastUpdated) / 3600000.0D;
            current = Math.min(max, current + (elapsedHours * regenPerHour));
            data().set(base + ".current", current);
            data().set(base + ".lastUpdated", now);
            plugin.saveDataFile();
        } else if (!data().contains(base + ".current")) {
            data().set(base + ".current", current);
            data().set(base + ".lastUpdated", now);
            plugin.saveDataFile();
        }
        return current;
    }

    private void setPlayerPower(UUID playerId, double value) {
        String base = ROOT + ".power.players." + playerId;
        data().set(base + ".current", value);
        data().set(base + ".lastUpdated", System.currentTimeMillis());
        plugin.saveDataFile();
    }

    private void addFactionLog(String factionId, String message) {
        String path = factionPath(factionId) + ".logs";
        List<String> logs = data().getStringList(path);
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + message;
        logs.add(entry);
        int maxEntries = plugin.getConfig().getInt(ROOT + ".logs.max_entries", 30);
        while (logs.size() > maxEntries) {
            logs.remove(0);
        }
        data().set(path, logs);
    }

    private List<String> getRecentLogsForFaction(String factionId, int limit) {
        List<String> logs = new ArrayList<>(data().getStringList(factionPath(factionId) + ".logs"));
        Collections.reverse(logs);
        if (limit > 0 && logs.size() > limit) {
            return new ArrayList<>(logs.subList(0, limit));
        }
        return logs;
    }

    private void triggerRaidAlert(String factionId, Location location, String sourceName) {
        long now = System.currentTimeMillis();
        long lastAlert = data().getLong(factionPath(factionId) + ".lastRaidAlert", 0L);
        long cooldownMillis = plugin.getConfig().getLong(ROOT + ".raid.alert_cooldown_seconds", 300L) * 1000L;
        if (cooldownMillis > 0 && now - lastAlert < cooldownMillis) {
            return;
        }

        String factionName = getFactionName(factionId);
        String chunk = formatChunk(toClaimKey(location));
        String detail = sourceName == null || sourceName.isBlank() ? "Explosion" : sourceName;
        String message = detail + " hit faction land at " + chunk + ".";

        data().set(factionPath(factionId) + ".lastRaidAlert", now);
        addFactionLog(factionId, "[raid] " + message);
        recordRaidAlert(factionName + " | " + message);
        plugin.saveDataFile();

        broadcastToFaction(factionId, ChatColor.RED + "Raid alert: " + ChatColor.YELLOW + message);
        if (plugin.getConfig().getBoolean(ROOT + ".raid.web_push", true)) {
            plugin.sendWebPush("Raid Alert: " + factionName, message);
        }
        if (plugin.getConfig().getBoolean(ROOT + ".discord.enabled", false)) {
            String webhook = plugin.getConfig().getString(ROOT + ".discord.webhook_override", "");
            if (webhook != null && !webhook.isBlank()) {
                final String url = webhook;
                Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> plugin.sendDiscordWebhook(url, "Faction Raid Alert", "**" + factionName + "**: " + message, 0xe74c3c));
            }
        }
        plugin.fireDiscordEvent("factions", "Faction Raid Alert", "**" + factionName + "**\n" + message, 0xe74c3c);
    }

    private void recordRaidAlert(String alert) {
        List<String> alerts = data().getStringList(ROOT + ".raid.alerts");
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + alert;
        alerts.add(entry);
        while (alerts.size() > 50) {
            alerts.remove(0);
        }
        data().set(ROOT + ".raid.alerts", alerts);
    }

    private void broadcastToFaction(String factionId, String message) {
        for (String memberId : getMemberIds(factionId)) {
            Player online = Bukkit.getPlayer(UUID.fromString(memberId));
            if (online != null) {
                online.sendMessage(message);
            }
        }
    }

    private String safePlayerName(OfflinePlayer player) {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString();
    }

    private CommandResult ok(String message) {
        return new CommandResult(true, message);
    }

    private CommandResult fail(String message) {
        return new CommandResult(false, ChatColor.RED + message);
    }
}
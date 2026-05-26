package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;

import java.util.UUID;

public class LocalNetworkProfileService implements NetworkProfileService {
    private final JavaRealmTool plugin;

    public LocalNetworkProfileService(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    @Override
    public NetworkPlayerProfile getProfile(UUID uuid) {
        String uuidKey = uuid.toString();
        String lastSeenName = plugin.getDataConfig().getString("last_seen_name." + uuidKey, uuidKey);
        return new NetworkPlayerProfile(
            uuid,
            lastSeenName,
            plugin.getPlayerRank(uuid),
            plugin.getPlayerGroup(uuid),
            plugin.getDiscordLink(uuid),
            plugin.getPlaytimeHours(uuid)
        );
    }

    @Override
    public NetworkPlayerProfile refreshProfile(UUID uuid) {
        return getProfile(uuid);
    }

    @Override
    public void updateLastSeenName(UUID uuid, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        plugin.getDataConfig().set("last_seen_name." + uuid, playerName.trim());
        plugin.saveDataFile();
    }

    @Override
    public void updateDiscordLink(UUID uuid, String discordTag) {
        plugin.getDataConfig().set("discord_links." + uuid, discordTag == null ? null : discordTag.trim());
        plugin.saveDataFile();
    }

    @Override
    public boolean isSharedBackendEnabled() {
        return false;
    }

    @Override
    public String getBackendName() {
        return "local-yaml";
    }
}
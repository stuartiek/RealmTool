package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;

import java.sql.SQLException;
import java.util.UUID;

public class SharedNetworkProfileService implements NetworkProfileService {
    private final JavaRealmTool plugin;
    private final SharedNetworkDatabase sharedDatabase;
    private final NetworkProfileService localFallback;
    private boolean loggedFailure;

    public SharedNetworkProfileService(JavaRealmTool plugin, SharedNetworkDatabase sharedDatabase, NetworkProfileService localFallback) {
        this.plugin = plugin;
        this.sharedDatabase = sharedDatabase;
        this.localFallback = localFallback;
    }

    @Override
    public NetworkPlayerProfile getProfile(UUID uuid) {
        try {
            return sharedDatabase.loadOrCreateProfile(uuid);
        } catch (SQLException exception) {
            logFallback("read shared profile data", exception);
            return localFallback.getProfile(uuid);
        }
    }

    @Override
    public void updateLastSeenName(UUID uuid, String playerName) {
        localFallback.updateLastSeenName(uuid, playerName);
        if (playerName == null || playerName.isBlank()) {
            return;
        }

        try {
            NetworkPlayerProfile currentProfile = sharedDatabase.loadOrCreateProfile(uuid);
            sharedDatabase.saveProfile(new NetworkPlayerProfile(
                uuid,
                playerName.trim(),
                currentProfile.rank(),
                currentProfile.group(),
                currentProfile.discordLink(),
                currentProfile.playtimeHours()
            ));
        } catch (SQLException exception) {
            logFallback("write shared profile name", exception);
        }
    }

    @Override
    public void updateDiscordLink(UUID uuid, String discordTag) {
        localFallback.updateDiscordLink(uuid, discordTag);
        try {
            NetworkPlayerProfile currentProfile = sharedDatabase.loadOrCreateProfile(uuid);
            sharedDatabase.saveProfile(new NetworkPlayerProfile(
                uuid,
                currentProfile.lastSeenName(),
                currentProfile.rank(),
                currentProfile.group(),
                discordTag == null || discordTag.isBlank() ? null : discordTag.trim(),
                currentProfile.playtimeHours()
            ));
        } catch (SQLException exception) {
            logFallback("write shared profile discord link", exception);
        }
    }

    @Override
    public boolean isSharedBackendEnabled() {
        return true;
    }

    @Override
    public String getBackendName() {
        return sharedDatabase.getBackendName();
    }

    private void logFallback(String action, SQLException exception) {
        if (loggedFailure) {
            return;
        }
        loggedFailure = true;
        plugin.getLogger().warning("Failed to " + action + "; falling back to local profile storage for this runtime: " + exception.getMessage());
    }
}
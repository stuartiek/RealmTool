package com.stuart.javarealmtool.services;

import java.util.UUID;

public interface NetworkProfileService {
    NetworkPlayerProfile getProfile(UUID uuid);

    void updateLastSeenName(UUID uuid, String playerName);

    void updateDiscordLink(UUID uuid, String discordTag);

    boolean isSharedBackendEnabled();

    String getBackendName();
}
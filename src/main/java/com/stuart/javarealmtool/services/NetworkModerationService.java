package com.stuart.javarealmtool.services;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface NetworkModerationService {
    long getPunishmentExpiry(UUID uuid);

    Map<UUID, Long> getPunishmentExpiries();

    void savePunishmentExpiry(UUID uuid, long expiryTimestamp);

    List<String> getNotes(UUID uuid);

    Map<UUID, List<String>> getAllNotes();

    void saveNotes(UUID uuid, List<String> notes);

    boolean isSharedBackendEnabled();

    String getBackendName();
}
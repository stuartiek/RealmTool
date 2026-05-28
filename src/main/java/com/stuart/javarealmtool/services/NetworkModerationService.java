package com.stuart.javarealmtool.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface NetworkModerationService {
    NetworkPunishment getPunishment(UUID uuid);

    Map<UUID, NetworkPunishment> getPunishments();

    void savePunishment(NetworkPunishment punishment);

    default long getPunishmentExpiry(UUID uuid) {
        NetworkPunishment punishment = getPunishment(uuid);
        return punishment != null ? Math.max(0L, punishment.expiresAt()) : 0L;
    }

    default Map<UUID, Long> getPunishmentExpiries() {
        Map<UUID, Long> expiries = new LinkedHashMap<>();
        for (Map.Entry<UUID, NetworkPunishment> entry : getPunishments().entrySet()) {
            NetworkPunishment punishment = entry.getValue();
            if (punishment != null) {
                expiries.put(entry.getKey(), Math.max(0L, punishment.expiresAt()));
            }
        }
        return expiries;
    }

    default void savePunishmentExpiry(UUID uuid, long expiryTimestamp) {
        NetworkPunishment existing = getPunishment(uuid);
        if (expiryTimestamp <= 0L) {
            savePunishment(new NetworkPunishment(uuid, 0L, null, null, 0L));
            return;
        }

        savePunishment(new NetworkPunishment(
            uuid,
            expiryTimestamp,
            existing != null ? existing.reason() : null,
            existing != null ? existing.actor() : null,
            existing != null && existing.createdAt() > 0L ? existing.createdAt() : System.currentTimeMillis()
        ));
    }

    List<NetworkWarning> getWarnings(UUID uuid);

    Map<UUID, List<NetworkWarning>> getAllWarnings();

    void addWarning(NetworkWarning warning);

    default int getWarningCount(UUID uuid) {
        return getWarnings(uuid).size();
    }

    List<String> getNotes(UUID uuid);

    Map<UUID, List<String>> getAllNotes();

    void saveNotes(UUID uuid, List<String> notes);

    boolean isSharedBackendEnabled();

    String getBackendName();
}
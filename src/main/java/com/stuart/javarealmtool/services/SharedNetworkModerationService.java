package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SharedNetworkModerationService implements NetworkModerationService {
    private final JavaRealmTool plugin;
    private final SharedNetworkDatabase sharedDatabase;
    private final NetworkModerationService localFallback;
    private boolean loggedFailure;

    public SharedNetworkModerationService(JavaRealmTool plugin, SharedNetworkDatabase sharedDatabase, NetworkModerationService localFallback) {
        this.plugin = plugin;
        this.sharedDatabase = sharedDatabase;
        this.localFallback = localFallback;
    }

    @Override
    public long getPunishmentExpiry(UUID uuid) {
        try {
            Long sharedExpiry = sharedDatabase.loadPunishmentExpiry(uuid);
            if (sharedExpiry != null) {
                return sharedExpiry;
            }

            long localExpiry = localFallback.getPunishmentExpiry(uuid);
            if (localExpiry > 0L) {
                sharedDatabase.savePunishmentExpiry(uuid, localExpiry);
            }
            return localExpiry;
        } catch (SQLException exception) {
            logFallback("read shared punishment data", exception);
            return localFallback.getPunishmentExpiry(uuid);
        }
    }

    @Override
    public Map<UUID, Long> getPunishmentExpiries() {
        try {
            Map<UUID, Long> punishments = new HashMap<>(sharedDatabase.loadAllPunishmentExpiries());
            for (Map.Entry<UUID, Long> entry : localFallback.getPunishmentExpiries().entrySet()) {
                if (punishments.containsKey(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0L) {
                    continue;
                }
                punishments.put(entry.getKey(), entry.getValue());
                sharedDatabase.savePunishmentExpiry(entry.getKey(), entry.getValue());
            }
            return punishments;
        } catch (SQLException exception) {
            logFallback("read shared punishment list", exception);
            return localFallback.getPunishmentExpiries();
        }
    }

    @Override
    public void savePunishmentExpiry(UUID uuid, long expiryTimestamp) {
        localFallback.savePunishmentExpiry(uuid, expiryTimestamp);
        try {
            sharedDatabase.savePunishmentExpiry(uuid, expiryTimestamp);
        } catch (SQLException exception) {
            logFallback("write shared punishment data", exception);
        }
    }

    @Override
    public List<String> getNotes(UUID uuid) {
        try {
            List<String> sharedNotes = sharedDatabase.loadNotes(uuid);
            if (!sharedNotes.isEmpty()) {
                return sharedNotes;
            }

            List<String> localNotes = localFallback.getNotes(uuid);
            if (!localNotes.isEmpty()) {
                sharedDatabase.saveNotes(uuid, localNotes);
            }
            return localNotes;
        } catch (SQLException exception) {
            logFallback("read shared notes", exception);
            return localFallback.getNotes(uuid);
        }
    }

    @Override
    public Map<UUID, List<String>> getAllNotes() {
        try {
            Map<UUID, List<String>> notes = new HashMap<>(sharedDatabase.loadAllNotes());
            for (Map.Entry<UUID, List<String>> entry : localFallback.getAllNotes().entrySet()) {
                if (notes.containsKey(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                notes.put(entry.getKey(), entry.getValue());
                sharedDatabase.saveNotes(entry.getKey(), entry.getValue());
            }
            return notes;
        } catch (SQLException exception) {
            logFallback("read shared note list", exception);
            return localFallback.getAllNotes();
        }
    }

    @Override
    public void saveNotes(UUID uuid, List<String> notes) {
        localFallback.saveNotes(uuid, notes);
        try {
            sharedDatabase.saveNotes(uuid, notes);
        } catch (SQLException exception) {
            logFallback("write shared notes", exception);
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
        plugin.getLogger().warning("Failed to " + action + "; falling back to local moderation storage for this runtime: " + exception.getMessage());
    }
}
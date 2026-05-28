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
    public NetworkPunishment getPunishment(UUID uuid) {
        try {
            NetworkPunishment sharedPunishment = sharedDatabase.loadPunishment(uuid);
            if (sharedPunishment != null) {
                return sharedPunishment;
            }

            NetworkPunishment localPunishment = localFallback.getPunishment(uuid);
            if (localPunishment != null && localPunishment.expiresAt() > 0L) {
                NetworkPunishment seededPunishment = localPunishment.createdAt() > 0L
                    ? localPunishment
                    : new NetworkPunishment(
                        localPunishment.uuid(),
                        localPunishment.expiresAt(),
                        localPunishment.reason(),
                        localPunishment.actor(),
                        System.currentTimeMillis()
                    );
                sharedDatabase.savePunishment(seededPunishment);
                return seededPunishment;
            }
            return localPunishment;
        } catch (SQLException exception) {
            logFallback("read shared punishment data", exception);
            return localFallback.getPunishment(uuid);
        }
    }

    @Override
    public Map<UUID, NetworkPunishment> getPunishments() {
        try {
            Map<UUID, NetworkPunishment> punishments = new HashMap<>(sharedDatabase.loadAllPunishments());
            for (Map.Entry<UUID, NetworkPunishment> entry : localFallback.getPunishments().entrySet()) {
                NetworkPunishment punishment = entry.getValue();
                if (punishments.containsKey(entry.getKey()) || punishment == null || punishment.expiresAt() <= 0L) {
                    continue;
                }
                NetworkPunishment seededPunishment = punishment.createdAt() > 0L
                    ? punishment
                    : new NetworkPunishment(
                        punishment.uuid(),
                        punishment.expiresAt(),
                        punishment.reason(),
                        punishment.actor(),
                        System.currentTimeMillis()
                    );
                punishments.put(entry.getKey(), seededPunishment);
                sharedDatabase.savePunishment(seededPunishment);
            }
            return punishments;
        } catch (SQLException exception) {
            logFallback("read shared punishment list", exception);
            return localFallback.getPunishments();
        }
    }

    @Override
    public void savePunishment(NetworkPunishment punishment) {
        localFallback.savePunishment(punishment);
        try {
            sharedDatabase.savePunishment(punishment);
        } catch (SQLException exception) {
            logFallback("write shared punishment data", exception);
        }
    }

    @Override
    public List<NetworkWarning> getWarnings(UUID uuid) {
        try {
            List<NetworkWarning> sharedWarnings = sharedDatabase.loadWarnings(uuid);
            if (!sharedWarnings.isEmpty()) {
                return sharedWarnings;
            }

            List<NetworkWarning> localWarnings = localFallback.getWarnings(uuid);
            if (!localWarnings.isEmpty()) {
                sharedDatabase.saveWarnings(uuid, localWarnings);
            }
            return localWarnings;
        } catch (SQLException exception) {
            logFallback("read shared warnings", exception);
            return localFallback.getWarnings(uuid);
        }
    }

    @Override
    public Map<UUID, List<NetworkWarning>> getAllWarnings() {
        try {
            Map<UUID, List<NetworkWarning>> warnings = new HashMap<>(sharedDatabase.loadAllWarnings());
            for (Map.Entry<UUID, List<NetworkWarning>> entry : localFallback.getAllWarnings().entrySet()) {
                if (warnings.containsKey(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                warnings.put(entry.getKey(), entry.getValue());
                sharedDatabase.saveWarnings(entry.getKey(), entry.getValue());
            }
            return warnings;
        } catch (SQLException exception) {
            logFallback("read shared warning list", exception);
            return localFallback.getAllWarnings();
        }
    }

    @Override
    public void addWarning(NetworkWarning warning) {
        localFallback.addWarning(warning);
        try {
            sharedDatabase.insertWarning(warning);
        } catch (SQLException exception) {
            logFallback("write shared warning data", exception);
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
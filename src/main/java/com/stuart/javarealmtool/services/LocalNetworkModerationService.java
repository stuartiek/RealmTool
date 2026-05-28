package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LocalNetworkModerationService implements NetworkModerationService {
    private final JavaRealmTool plugin;

    public LocalNetworkModerationService(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    @Override
    public long getPunishmentExpiry(UUID uuid) {
        return plugin.getLocalPunishmentExpiry(uuid);
    }

    @Override
    public Map<UUID, Long> getPunishmentExpiries() {
        return plugin.getAllLocalPunishments();
    }

    @Override
    public void savePunishmentExpiry(UUID uuid, long expiryTimestamp) {
        plugin.setLocalPunishmentExpiry(uuid, expiryTimestamp);
    }

    @Override
    public List<String> getNotes(UUID uuid) {
        return plugin.getLocalNotes(uuid);
    }

    @Override
    public Map<UUID, List<String>> getAllNotes() {
        return plugin.getAllLocalNotes();
    }

    @Override
    public void saveNotes(UUID uuid, List<String> notes) {
        plugin.setLocalNotes(uuid, notes);
    }

    @Override
    public boolean isSharedBackendEnabled() {
        return false;
    }

    @Override
    public String getBackendName() {
        return "local-moderation";
    }
}
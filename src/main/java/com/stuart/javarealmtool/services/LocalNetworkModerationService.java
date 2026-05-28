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
    public NetworkPunishment getPunishment(UUID uuid) {
        return plugin.getLocalPunishment(uuid);
    }

    @Override
    public Map<UUID, NetworkPunishment> getPunishments() {
        return plugin.getAllLocalPunishmentRecords();
    }

    @Override
    public void savePunishment(NetworkPunishment punishment) {
        plugin.saveLocalPunishment(punishment);
    }

    @Override
    public List<NetworkWarning> getWarnings(UUID uuid) {
        return plugin.getLocalWarnings(uuid);
    }

    @Override
    public Map<UUID, List<NetworkWarning>> getAllWarnings() {
        return plugin.getAllLocalWarnings();
    }

    @Override
    public void addWarning(NetworkWarning warning) {
        plugin.addLocalWarning(warning);
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
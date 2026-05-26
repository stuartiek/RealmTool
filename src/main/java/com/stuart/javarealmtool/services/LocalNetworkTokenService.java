package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;

import java.util.UUID;

public class LocalNetworkTokenService implements NetworkTokenService {
    private final JavaRealmTool plugin;

    public LocalNetworkTokenService(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    @Override
    public long getTokens(UUID uuid) {
        return plugin.getCoins(uuid);
    }

    @Override
    public void setTokens(UUID uuid, long amount) {
        plugin.setCoins(uuid, amount);
    }

    @Override
    public void addTokens(UUID uuid, long delta) {
        plugin.addCoins(uuid, delta);
    }

    @Override
    public boolean isSharedBackendEnabled() {
        return false;
    }

    @Override
    public String getBackendName() {
        return "local-economy";
    }
}
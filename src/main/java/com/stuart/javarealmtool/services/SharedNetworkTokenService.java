package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;

import java.sql.SQLException;
import java.util.UUID;

public class SharedNetworkTokenService implements NetworkTokenService {
    private final JavaRealmTool plugin;
    private final SharedNetworkDatabase sharedDatabase;
    private final NetworkTokenService localFallback;
    private boolean loggedFailure;

    public SharedNetworkTokenService(JavaRealmTool plugin, SharedNetworkDatabase sharedDatabase, NetworkTokenService localFallback) {
        this.plugin = plugin;
        this.sharedDatabase = sharedDatabase;
        this.localFallback = localFallback;
    }

    @Override
    public long getTokens(UUID uuid) {
        try {
            return sharedDatabase.loadOrCreateTokens(uuid);
        } catch (SQLException exception) {
            logFallback("read shared token data", exception);
            return localFallback.getTokens(uuid);
        }
    }

    @Override
    public void setTokens(UUID uuid, long amount) {
        localFallback.setTokens(uuid, amount);
        try {
            sharedDatabase.saveTokens(uuid, amount);
        } catch (SQLException exception) {
            logFallback("write shared token data", exception);
        }
    }

    @Override
    public void addTokens(UUID uuid, long delta) {
        long nextBalance = getTokens(uuid) + delta;
        setTokens(uuid, nextBalance);
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
        plugin.getLogger().warning("Failed to " + action + "; falling back to local token storage for this runtime: " + exception.getMessage());
    }
}
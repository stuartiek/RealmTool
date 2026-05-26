package com.stuart.javarealmtool.services;

import java.util.UUID;

public interface NetworkTokenService {
    long getTokens(UUID uuid);

    void setTokens(UUID uuid, long amount);

    void addTokens(UUID uuid, long delta);

    boolean isSharedBackendEnabled();

    String getBackendName();
}
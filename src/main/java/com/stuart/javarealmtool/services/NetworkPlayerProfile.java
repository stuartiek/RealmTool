package com.stuart.javarealmtool.services;

import java.util.UUID;

public record NetworkPlayerProfile(
    UUID uuid,
    String lastSeenName,
    String rank,
    String group,
    String discordLink,
    double playtimeHours
) {}
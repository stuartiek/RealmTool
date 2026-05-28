package com.stuart.javarealmtool.services;

import java.util.UUID;

public record NetworkPunishment(
    UUID uuid,
    long expiresAt,
    String reason,
    String actor,
    long createdAt
) {
}
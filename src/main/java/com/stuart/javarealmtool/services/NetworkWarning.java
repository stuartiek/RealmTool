package com.stuart.javarealmtool.services;

import java.util.UUID;

public record NetworkWarning(
    UUID uuid,
    int warningNumber,
    String reason,
    String issuedBy,
    long createdAt
) {
}
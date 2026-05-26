package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

public final class SharedNetworkDatabase implements AutoCloseable {
    private final JavaRealmTool plugin;
    private final String provider;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String profilesTableName;
    private final String tokensTableName;

    public SharedNetworkDatabase(JavaRealmTool plugin) throws SQLException {
        this.plugin = plugin;
        this.provider = normalizeProvider(plugin.getNetworkSharedDatabaseProvider());
        this.jdbcUrl = buildJdbcUrl();
        this.username = Objects.requireNonNullElse(plugin.getNetworkSharedDatabaseUsername(), "").trim();
        this.password = Objects.requireNonNullElse(plugin.getNetworkSharedDatabasePassword(), "");

        String tablePrefix = sanitizeTablePrefix(plugin.getNetworkSharedDatabaseTablePrefix());
        this.profilesTableName = tablePrefix + "network_profiles";
        this.tokensTableName = tablePrefix + "network_tokens";

        ensureDriver();
        ensureSchema();
    }

    public synchronized NetworkPlayerProfile loadOrCreateProfile(UUID uuid) throws SQLException {
        try (Connection connection = openConnection()) {
            NetworkPlayerProfile existingProfile = loadProfile(connection, uuid);
            if (existingProfile != null) {
                return existingProfile;
            }

            NetworkPlayerProfile seededProfile = createSeedProfile(uuid);
            upsertProfile(connection, seededProfile);
            return seededProfile;
        }
    }

    public synchronized void saveProfile(NetworkPlayerProfile profile) throws SQLException {
        try (Connection connection = openConnection()) {
            upsertProfile(connection, profile);
        }
    }

    public synchronized long loadOrCreateTokens(UUID uuid) throws SQLException {
        try (Connection connection = openConnection()) {
            Long existingBalance = loadTokenBalance(connection, uuid);
            if (existingBalance != null) {
                return existingBalance;
            }

            long seededBalance = plugin.getCoins(uuid);
            upsertTokenBalance(connection, uuid, seededBalance);
            return seededBalance;
        }
    }

    public synchronized void saveTokens(UUID uuid, long amount) throws SQLException {
        try (Connection connection = openConnection()) {
            upsertTokenBalance(connection, uuid, amount);
        }
    }

    public String getBackendName() {
        return "shared-" + provider;
    }

    @Override
    public void close() {
    }

    private Connection openConnection() throws SQLException {
        Properties properties = new Properties();
        if (!username.isEmpty()) {
            properties.setProperty("user", username);
        }
        if (!password.isEmpty()) {
            properties.setProperty("password", password);
        }
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private void ensureDriver() throws SQLException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("PostgreSQL JDBC driver is not available in the plugin jar.", exception);
        }
    }

    private void ensureSchema() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement createProfiles = connection.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS " + profilesTableName + " (" +
                     "uuid VARCHAR(36) PRIMARY KEY, " +
                     "last_seen_name VARCHAR(64) NOT NULL, " +
                     "rank_name VARCHAR(64) NOT NULL, " +
                     "group_name VARCHAR(64) NOT NULL, " +
                     "discord_link VARCHAR(128), " +
                     "playtime_hours BIGINT NOT NULL DEFAULT 0, " +
                     "updated_at BIGINT NOT NULL" +
                 ")");
             PreparedStatement createTokens = connection.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS " + tokensTableName + " (" +
                     "uuid VARCHAR(36) PRIMARY KEY, " +
                     "token_balance BIGINT NOT NULL DEFAULT 0, " +
                     "updated_at BIGINT NOT NULL" +
                 ")")) {
            createProfiles.executeUpdate();
            createTokens.executeUpdate();
        }
    }

    private NetworkPlayerProfile loadProfile(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT last_seen_name, rank_name, group_name, discord_link, playtime_hours FROM " + profilesTableName + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new NetworkPlayerProfile(
                    uuid,
                    resultSet.getString("last_seen_name"),
                    resultSet.getString("rank_name"),
                    resultSet.getString("group_name"),
                    resultSet.getString("discord_link"),
                    resultSet.getLong("playtime_hours")
                );
            }
        }
    }

    private void upsertProfile(Connection connection, NetworkPlayerProfile profile) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + profilesTableName + " (uuid, last_seen_name, rank_name, group_name, discord_link, playtime_hours, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (uuid) DO UPDATE SET " +
                "last_seen_name = EXCLUDED.last_seen_name, " +
                "rank_name = EXCLUDED.rank_name, " +
                "group_name = EXCLUDED.group_name, " +
                "discord_link = EXCLUDED.discord_link, " +
                "playtime_hours = EXCLUDED.playtime_hours, " +
                "updated_at = EXCLUDED.updated_at")) {
            statement.setString(1, profile.uuid().toString());
            statement.setString(2, normalizePlayerName(profile.lastSeenName(), profile.uuid()));
            statement.setString(3, normalizeText(profile.rank(), "default"));
            statement.setString(4, normalizeText(profile.group(), "default"));
            if (profile.discordLink() == null || profile.discordLink().isBlank()) {
                statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
                statement.setString(5, profile.discordLink().trim());
            }
            statement.setLong(6, Math.max(0L, Math.round(profile.playtimeHours())));
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private Long loadTokenBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT token_balance FROM " + tokensTableName + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return resultSet.getLong("token_balance");
            }
        }
    }

    private void upsertTokenBalance(Connection connection, UUID uuid, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + tokensTableName + " (uuid, token_balance, updated_at) VALUES (?, ?, ?) " +
                "ON CONFLICT (uuid) DO UPDATE SET token_balance = EXCLUDED.token_balance, updated_at = EXCLUDED.updated_at")) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, amount);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private NetworkPlayerProfile createSeedProfile(UUID uuid) {
        String uuidKey = uuid.toString();
        String lastSeenName = plugin.getDataConfig().getString("last_seen_name." + uuidKey, uuidKey);
        return new NetworkPlayerProfile(
            uuid,
            normalizePlayerName(lastSeenName, uuid),
            normalizeText(plugin.getPlayerRank(uuid), "default"),
            normalizeText(plugin.getPlayerGroup(uuid), "default"),
            normalizeNullableText(plugin.getDiscordLink(uuid)),
            Math.max(0L, plugin.getPlaytimeHours(uuid))
        );
    }

    private String buildJdbcUrl() throws SQLException {
        if (!"postgresql".equals(provider)) {
            throw new SQLException("Unsupported staged shared database provider: " + provider + ". Only postgresql is implemented right now.");
        }

        String sslMode = plugin.isNetworkSharedDatabaseSslEnabled() ? "require" : "disable";
        return "jdbc:postgresql://" + plugin.getNetworkSharedDatabaseHost() + ':' + plugin.getNetworkSharedDatabasePort() + '/' + plugin.getNetworkSharedDatabaseName() + "?sslmode=" + sslMode;
    }

    private String normalizeProvider(String rawProvider) {
        String normalized = rawProvider == null ? "postgresql" : rawProvider.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "postgresql" : normalized;
    }

    private String sanitizeTablePrefix(String rawPrefix) {
        String prefix = rawPrefix == null ? "drowsy_" : rawPrefix.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        return prefix.isEmpty() ? "drowsy_" : prefix;
    }

    private String normalizePlayerName(String value, UUID uuid) {
        String normalized = normalizeText(value, uuid.toString());
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
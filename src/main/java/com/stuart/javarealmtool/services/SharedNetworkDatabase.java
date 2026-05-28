package com.stuart.javarealmtool.services;

import com.stuart.javarealmtool.JavaRealmTool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final String punishmentsTableName;
    private final String warningsTableName;
    private final String notesTableName;

    public SharedNetworkDatabase(JavaRealmTool plugin) throws SQLException {
        this.plugin = plugin;
        this.provider = normalizeProvider(plugin.getNetworkSharedDatabaseProvider());
        this.jdbcUrl = buildJdbcUrl();
        this.username = Objects.requireNonNullElse(plugin.getNetworkSharedDatabaseUsername(), "").trim();
        this.password = Objects.requireNonNullElse(plugin.getNetworkSharedDatabasePassword(), "");

        String tablePrefix = sanitizeTablePrefix(plugin.getNetworkSharedDatabaseTablePrefix());
        this.profilesTableName = tablePrefix + "network_profiles";
        this.tokensTableName = tablePrefix + "network_tokens";
        this.punishmentsTableName = tablePrefix + "network_punishments";
        this.warningsTableName = tablePrefix + "network_warnings";
        this.notesTableName = tablePrefix + "network_notes";

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

            long seededBalance = plugin.getLocalCoins(uuid);
            upsertTokenBalance(connection, uuid, seededBalance);
            return seededBalance;
        }
    }

    public synchronized void saveTokens(UUID uuid, long amount) throws SQLException {
        try (Connection connection = openConnection()) {
            upsertTokenBalance(connection, uuid, amount);
        }
    }

    public synchronized Long loadPunishmentExpiry(UUID uuid) throws SQLException {
        NetworkPunishment punishment = loadPunishment(uuid);
        return punishment != null ? punishment.expiresAt() : null;
    }

    public synchronized Map<UUID, Long> loadAllPunishmentExpiries() throws SQLException {
        Map<UUID, Long> punishments = new LinkedHashMap<>();
        for (Map.Entry<UUID, NetworkPunishment> entry : loadAllPunishments().entrySet()) {
            if (entry.getValue() != null) {
                punishments.put(entry.getKey(), entry.getValue().expiresAt());
            }
        }
        return punishments;
    }

    public synchronized void savePunishmentExpiry(UUID uuid, long expiryTimestamp) throws SQLException {
        NetworkPunishment existing = loadPunishment(uuid);
        savePunishment(new NetworkPunishment(
            uuid,
            expiryTimestamp,
            existing != null ? existing.reason() : null,
            existing != null ? existing.actor() : null,
            existing != null && existing.createdAt() > 0L ? existing.createdAt() : System.currentTimeMillis()
        ));
    }

    public synchronized NetworkPunishment loadPunishment(UUID uuid) throws SQLException {
        try (Connection connection = openConnection()) {
            return loadPunishment(connection, uuid);
        }
    }

    public synchronized Map<UUID, NetworkPunishment> loadAllPunishments() throws SQLException {
        try (Connection connection = openConnection()) {
            return loadAllPunishments(connection);
        }
    }

    public synchronized void savePunishment(NetworkPunishment punishment) throws SQLException {
        try (Connection connection = openConnection()) {
            upsertPunishment(connection, punishment);
        }
    }

    public synchronized List<NetworkWarning> loadWarnings(UUID uuid) throws SQLException {
        try (Connection connection = openConnection()) {
            return loadWarnings(connection, uuid);
        }
    }

    public synchronized Map<UUID, List<NetworkWarning>> loadAllWarnings() throws SQLException {
        try (Connection connection = openConnection()) {
            return loadAllWarnings(connection);
        }
    }

    public synchronized void saveWarnings(UUID uuid, List<NetworkWarning> warnings) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                replaceWarnings(connection, uuid, warnings);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public synchronized NetworkWarning insertWarning(NetworkWarning warning) throws SQLException {
        try (Connection connection = openConnection()) {
            return insertWarning(connection, warning);
        }
    }

    public synchronized List<String> loadNotes(UUID uuid) throws SQLException {
        try (Connection connection = openConnection()) {
            return loadNotes(connection, uuid);
        }
    }

    public synchronized Map<UUID, List<String>> loadAllNotes() throws SQLException {
        try (Connection connection = openConnection()) {
            return loadAllNotes(connection);
        }
    }

    public synchronized void saveNotes(UUID uuid, List<String> notes) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                replaceNotes(connection, uuid, notes);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
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
                 ")");
             PreparedStatement createPunishments = connection.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS " + punishmentsTableName + " (" +
                     "uuid VARCHAR(36) PRIMARY KEY, " +
                     "expires_at BIGINT NOT NULL, " +
                     "reason_text TEXT, " +
                     "actor_name VARCHAR(64), " +
                     "created_at BIGINT NOT NULL DEFAULT 0, " +
                     "updated_at BIGINT NOT NULL" +
                 ")");
             PreparedStatement createWarnings = connection.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS " + warningsTableName + " (" +
                     "uuid VARCHAR(36) NOT NULL, " +
                     "warning_number INTEGER NOT NULL, " +
                     "reason_text TEXT NOT NULL, " +
                     "issued_by VARCHAR(64), " +
                     "created_at BIGINT NOT NULL, " +
                     "updated_at BIGINT NOT NULL, " +
                     "PRIMARY KEY (uuid, warning_number)" +
                 ")");
             PreparedStatement createNotes = connection.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS " + notesTableName + " (" +
                     "uuid VARCHAR(36) NOT NULL, " +
                     "note_index INTEGER NOT NULL, " +
                     "note_text TEXT NOT NULL, " +
                     "updated_at BIGINT NOT NULL, " +
                     "PRIMARY KEY (uuid, note_index)" +
                 ")")) {
            createProfiles.executeUpdate();
            createTokens.executeUpdate();
            createPunishments.executeUpdate();
            createWarnings.executeUpdate();
            createNotes.executeUpdate();

            try (PreparedStatement alterPunishmentsReason = connection.prepareStatement(
                     "ALTER TABLE " + punishmentsTableName + " ADD COLUMN IF NOT EXISTS reason_text TEXT");
                 PreparedStatement alterPunishmentsActor = connection.prepareStatement(
                     "ALTER TABLE " + punishmentsTableName + " ADD COLUMN IF NOT EXISTS actor_name VARCHAR(64)");
                 PreparedStatement alterPunishmentsCreated = connection.prepareStatement(
                     "ALTER TABLE " + punishmentsTableName + " ADD COLUMN IF NOT EXISTS created_at BIGINT NOT NULL DEFAULT 0")) {
                alterPunishmentsReason.executeUpdate();
                alterPunishmentsActor.executeUpdate();
                alterPunishmentsCreated.executeUpdate();
            }
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

    private NetworkPunishment loadPunishment(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT expires_at, reason_text, actor_name, created_at FROM " + punishmentsTableName + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new NetworkPunishment(
                    uuid,
                    resultSet.getLong("expires_at"),
                    resultSet.getString("reason_text"),
                    resultSet.getString("actor_name"),
                    resultSet.getLong("created_at")
                );
            }
        }
    }

    private Map<UUID, NetworkPunishment> loadAllPunishments(Connection connection) throws SQLException {
        Map<UUID, NetworkPunishment> punishments = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT uuid, expires_at, reason_text, actor_name, created_at FROM " + punishmentsTableName);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                punishments.put(uuid, new NetworkPunishment(
                    uuid,
                    resultSet.getLong("expires_at"),
                    resultSet.getString("reason_text"),
                    resultSet.getString("actor_name"),
                    resultSet.getLong("created_at")
                ));
            }
        }
        return punishments;
    }

    private void upsertPunishment(Connection connection, NetworkPunishment punishment) throws SQLException {
        if (punishment == null || punishment.expiresAt() <= 0L) {
            try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + punishmentsTableName + " WHERE uuid = ?")) {
                delete.setString(1, punishment != null ? punishment.uuid().toString() : null);
                delete.executeUpdate();
            }
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + punishmentsTableName + " (uuid, expires_at, reason_text, actor_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (uuid) DO UPDATE SET expires_at = EXCLUDED.expires_at, reason_text = EXCLUDED.reason_text, actor_name = EXCLUDED.actor_name, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at")) {
            statement.setString(1, punishment.uuid().toString());
            statement.setLong(2, punishment.expiresAt());
            if (punishment.reason() == null || punishment.reason().isBlank()) {
                statement.setNull(3, Types.VARCHAR);
            } else {
                statement.setString(3, punishment.reason().trim());
            }
            if (punishment.actor() == null || punishment.actor().isBlank()) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, normalizePlayerName(punishment.actor(), punishment.uuid()));
            }
            statement.setLong(5, Math.max(0L, punishment.createdAt()));
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private List<NetworkWarning> loadWarnings(Connection connection, UUID uuid) throws SQLException {
        List<NetworkWarning> warnings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT warning_number, reason_text, issued_by, created_at FROM " + warningsTableName + " WHERE uuid = ? ORDER BY warning_number ASC")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    warnings.add(new NetworkWarning(
                        uuid,
                        resultSet.getInt("warning_number"),
                        resultSet.getString("reason_text"),
                        resultSet.getString("issued_by"),
                        resultSet.getLong("created_at")
                    ));
                }
            }
        }
        return warnings;
    }

    private Map<UUID, List<NetworkWarning>> loadAllWarnings(Connection connection) throws SQLException {
        Map<UUID, List<NetworkWarning>> warningsByPlayer = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT uuid, warning_number, reason_text, issued_by, created_at FROM " + warningsTableName + " ORDER BY uuid ASC, warning_number ASC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                warningsByPlayer.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(new NetworkWarning(
                    uuid,
                    resultSet.getInt("warning_number"),
                    resultSet.getString("reason_text"),
                    resultSet.getString("issued_by"),
                    resultSet.getLong("created_at")
                ));
            }
        }
        return warningsByPlayer;
    }

    private void replaceWarnings(Connection connection, UUID uuid, List<NetworkWarning> warnings) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM " + warningsTableName + " WHERE uuid = ?")) {
            delete.setString(1, uuid.toString());
            delete.executeUpdate();
        }

        if (warnings == null || warnings.isEmpty()) {
            return;
        }

        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO " + warningsTableName + " (uuid, warning_number, reason_text, issued_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            long updatedAt = System.currentTimeMillis();
            for (int index = 0; index < warnings.size(); index++) {
                NetworkWarning warning = warnings.get(index);
                int warningNumber = warning.warningNumber() > 0 ? warning.warningNumber() : index + 1;
                insert.setString(1, uuid.toString());
                insert.setInt(2, warningNumber);
                insert.setString(3, normalizeText(warning.reason(), "No reason"));
                if (warning.issuedBy() == null || warning.issuedBy().isBlank()) {
                    insert.setNull(4, Types.VARCHAR);
                } else {
                    insert.setString(4, normalizePlayerName(warning.issuedBy(), uuid));
                }
                insert.setLong(5, warning.createdAt() > 0L ? warning.createdAt() : System.currentTimeMillis());
                insert.setLong(6, updatedAt);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private NetworkWarning insertWarning(Connection connection, NetworkWarning warning) throws SQLException {
        int warningNumber = warning.warningNumber() > 0 ? warning.warningNumber() : nextWarningNumber(connection, warning.uuid());
        NetworkWarning persistedWarning = new NetworkWarning(
            warning.uuid(),
            warningNumber,
            normalizeText(warning.reason(), "No reason"),
            normalizeNullableText(warning.issuedBy()),
            warning.createdAt() > 0L ? warning.createdAt() : System.currentTimeMillis()
        );

        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO " + warningsTableName + " (uuid, warning_number, reason_text, issued_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, persistedWarning.uuid().toString());
            insert.setInt(2, persistedWarning.warningNumber());
            insert.setString(3, persistedWarning.reason());
            if (persistedWarning.issuedBy() == null || persistedWarning.issuedBy().isBlank()) {
                insert.setNull(4, Types.VARCHAR);
            } else {
                insert.setString(4, normalizePlayerName(persistedWarning.issuedBy(), persistedWarning.uuid()));
            }
            insert.setLong(5, persistedWarning.createdAt());
            insert.setLong(6, System.currentTimeMillis());
            insert.executeUpdate();
        }
        return persistedWarning;
    }

    private int nextWarningNumber(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COALESCE(MAX(warning_number), 0) + 1 AS next_warning_number FROM " + warningsTableName + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("next_warning_number");
                }
            }
        }
        return 1;
    }

    private List<String> loadNotes(Connection connection, UUID uuid) throws SQLException {
        List<String> notes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT note_text FROM " + notesTableName + " WHERE uuid = ? ORDER BY note_index ASC")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    notes.add(resultSet.getString("note_text"));
                }
            }
        }
        return notes;
    }

    private Map<UUID, List<String>> loadAllNotes(Connection connection) throws SQLException {
        Map<UUID, List<String>> notesByPlayer = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT uuid, note_text FROM " + notesTableName + " ORDER BY uuid ASC, note_index ASC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                notesByPlayer.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(resultSet.getString("note_text"));
            }
        }
        return notesByPlayer;
    }

    private void replaceNotes(Connection connection, UUID uuid, List<String> notes) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM " + notesTableName + " WHERE uuid = ?")) {
            delete.setString(1, uuid.toString());
            delete.executeUpdate();
        }

        if (notes == null || notes.isEmpty()) {
            return;
        }

        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO " + notesTableName + " (uuid, note_index, note_text, updated_at) VALUES (?, ?, ?, ?)")) {
            long updatedAt = System.currentTimeMillis();
            for (int index = 0; index < notes.size(); index++) {
                insert.setString(1, uuid.toString());
                insert.setInt(2, index);
                insert.setString(3, Objects.requireNonNullElse(notes.get(index), ""));
                insert.setLong(4, updatedAt);
                insert.addBatch();
            }
            insert.executeBatch();
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
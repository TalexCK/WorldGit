package com.worldgit.database;

import com.worldgit.ai.PlayerAiSecretRecord;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerAiSecretRepository {

    private final DatabaseManager databaseManager;

    public PlayerAiSecretRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public void save(PlayerAiSecretRecord record) {
        try {
            databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO player_ai_secrets (
                            player_uuid, player_name, secret_hash, secret_salt,
                            hash_iterations, ai_allowed, created_at, rotated_at, last_used_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(player_uuid) DO UPDATE SET
                            player_name = excluded.player_name,
                            secret_hash = excluded.secret_hash,
                            secret_salt = excluded.secret_salt,
                            hash_iterations = excluded.hash_iterations,
                            ai_allowed = excluded.ai_allowed,
                            rotated_at = excluded.rotated_at,
                            last_used_at = excluded.last_used_at
                        """
                )) {
                    statement.setString(1, record.playerUuid().toString());
                    statement.setString(2, record.playerName());
                    statement.setString(3, record.secretHash());
                    statement.setString(4, record.secretSalt());
                    statement.setInt(5, record.hashIterations());
                    statement.setInt(6, record.aiAllowed() ? 1 : 0);
                    statement.setLong(7, record.createdAt().toEpochMilli());
                    statement.setLong(8, record.rotatedAt().toEpochMilli());
                    if (record.lastUsedAt() == null) {
                        statement.setObject(9, null);
                    } else {
                        statement.setLong(9, record.lastUsedAt().toEpochMilli());
                    }
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("保存 AI Secret 失败", exception);
        }
    }

    public Optional<PlayerAiSecretRecord> findByPlayerUuid(UUID playerUuid) {
        try {
            return databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT player_uuid, player_name, secret_hash, secret_salt,
                               hash_iterations, ai_allowed, created_at, rotated_at, last_used_at
                        FROM player_ai_secrets
                        WHERE player_uuid = ?
                        """
                )) {
                    statement.setString(1, playerUuid.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(map(resultSet));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 AI Secret 失败", exception);
        }
    }

    public void touchLastUsed(UUID playerUuid, Instant usedAt) {
        try {
            databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE player_ai_secrets SET last_used_at = ? WHERE player_uuid = ?"
                )) {
                    statement.setLong(1, usedAt.toEpochMilli());
                    statement.setString(2, playerUuid.toString());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("更新 AI Secret 使用时间失败", exception);
        }
    }

    private PlayerAiSecretRecord map(ResultSet resultSet) throws SQLException {
        long lastUsedAt = resultSet.getLong("last_used_at");
        return new PlayerAiSecretRecord(
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"),
                resultSet.getString("secret_hash"),
                resultSet.getString("secret_salt"),
                resultSet.getInt("hash_iterations"),
                resultSet.getInt("ai_allowed") != 0,
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                Instant.ofEpochMilli(resultSet.getLong("rotated_at")),
                resultSet.wasNull() ? null : Instant.ofEpochMilli(lastUsedAt)
        );
    }
}

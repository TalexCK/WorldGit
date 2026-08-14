package com.worldgit.database;

import com.worldgit.ai.AiPreviewSessionRecord;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AiPreviewSessionRepository {

    private final DatabaseManager databaseManager;

    public AiPreviewSessionRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public void save(AiPreviewSessionRecord record) {
        try {
            databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO ai_preview_sessions (
                            preview_branch_id, source_branch_id, player_uuid,
                            source_world_name, source_x, source_y, source_z, source_yaw, source_pitch,
                            created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(preview_branch_id) DO UPDATE SET
                            source_branch_id = excluded.source_branch_id,
                            player_uuid = excluded.player_uuid,
                            source_world_name = excluded.source_world_name,
                            source_x = excluded.source_x,
                            source_y = excluded.source_y,
                            source_z = excluded.source_z,
                            source_yaw = excluded.source_yaw,
                            source_pitch = excluded.source_pitch,
                            created_at = excluded.created_at
                        """
                )) {
                    statement.setString(1, record.previewBranchId());
                    statement.setString(2, record.sourceBranchId());
                    statement.setString(3, record.playerUuid().toString());
                    statement.setString(4, record.sourceWorldName());
                    if (record.sourceX() == null) {
                        statement.setObject(5, null);
                    } else {
                        statement.setDouble(5, record.sourceX());
                    }
                    if (record.sourceY() == null) {
                        statement.setObject(6, null);
                    } else {
                        statement.setDouble(6, record.sourceY());
                    }
                    if (record.sourceZ() == null) {
                        statement.setObject(7, null);
                    } else {
                        statement.setDouble(7, record.sourceZ());
                    }
                    if (record.sourceYaw() == null) {
                        statement.setObject(8, null);
                    } else {
                        statement.setFloat(8, record.sourceYaw());
                    }
                    if (record.sourcePitch() == null) {
                        statement.setObject(9, null);
                    } else {
                        statement.setFloat(9, record.sourcePitch());
                    }
                    statement.setLong(10, record.createdAt().toEpochMilli());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("保存 AI 预览会话失败", exception);
        }
    }

    public Optional<AiPreviewSessionRecord> findByPreviewBranchId(String previewBranchId) {
        try {
            return databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT preview_branch_id, source_branch_id, player_uuid,
                               source_world_name, source_x, source_y, source_z, source_yaw, source_pitch,
                               created_at
                        FROM ai_preview_sessions
                        WHERE preview_branch_id = ?
                        """
                )) {
                    statement.setString(1, previewBranchId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(map(resultSet));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 AI 预览会话失败", exception);
        }
    }

    public List<AiPreviewSessionRecord> findByPlayerUuid(UUID playerUuid) {
        try {
            return databaseManager.withConnection(connection -> {
                List<AiPreviewSessionRecord> records = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT preview_branch_id, source_branch_id, player_uuid,
                               source_world_name, source_x, source_y, source_z, source_yaw, source_pitch,
                               created_at
                        FROM ai_preview_sessions
                        WHERE player_uuid = ?
                        ORDER BY created_at DESC
                        """
                )) {
                    statement.setString(1, playerUuid.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            records.add(map(resultSet));
                        }
                    }
                }
                return records;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("读取玩家 AI 预览会话失败", exception);
        }
    }

    public List<AiPreviewSessionRecord> findAll() {
        try {
            return databaseManager.withConnection(connection -> {
                List<AiPreviewSessionRecord> records = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT preview_branch_id, source_branch_id, player_uuid,
                               source_world_name, source_x, source_y, source_z, source_yaw, source_pitch,
                               created_at
                        FROM ai_preview_sessions
                        ORDER BY created_at DESC
                        """
                ); ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        records.add(map(resultSet));
                    }
                }
                return records;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 AI 预览会话列表失败", exception);
        }
    }

    public void delete(String previewBranchId) {
        try {
            databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM ai_preview_sessions WHERE preview_branch_id = ?"
                )) {
                    statement.setString(1, previewBranchId);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("删除 AI 预览会话失败", exception);
        }
    }

    private AiPreviewSessionRecord map(ResultSet resultSet) throws SQLException {
        return new AiPreviewSessionRecord(
                resultSet.getString("preview_branch_id"),
                resultSet.getString("source_branch_id"),
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("source_world_name"),
                getNullableDouble(resultSet, "source_x"),
                getNullableDouble(resultSet, "source_y"),
                getNullableDouble(resultSet, "source_z"),
                getNullableFloat(resultSet, "source_yaw"),
                getNullableFloat(resultSet, "source_pitch"),
                Instant.ofEpochMilli(resultSet.getLong("created_at"))
        );
    }

    private Double getNullableDouble(ResultSet resultSet, String columnName) throws SQLException {
        double value = resultSet.getDouble(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private Float getNullableFloat(ResultSet resultSet, String columnName) throws SQLException {
        float value = resultSet.getFloat(columnName);
        return resultSet.wasNull() ? null : value;
    }
}

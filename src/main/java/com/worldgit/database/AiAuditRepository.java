package com.worldgit.database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AiAuditRepository {

    private final DatabaseManager databaseManager;

    public AiAuditRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public void append(
            String sessionId,
            UUID playerUuid,
            String branchId,
            String provider,
            String model,
            String eventType,
            String payload,
            Instant createdAt
    ) {
        try {
            databaseManager.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO ai_audit_logs (
                            session_id, player_uuid, branch_id, provider, model,
                            event_type, payload, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """
                )) {
                    statement.setString(1, sessionId);
                    statement.setString(2, playerUuid.toString());
                    statement.setString(3, branchId);
                    statement.setString(4, provider);
                    statement.setString(5, model);
                    statement.setString(6, eventType);
                    statement.setString(7, payload == null ? "" : payload);
                    statement.setLong(8, createdAt.toEpochMilli());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("写入 AI 审计日志失败", exception);
        }
    }
}

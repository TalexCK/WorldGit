package com.worldgit.database;

import com.worldgit.model.QueueEntry;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 排队仓储。
 */
public final class QueueRepository {

    private final DatabaseManager databaseManager;

    public QueueRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public void enqueue(QueueEntry entry) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO queue_entries (
                        player_uuid, player_name, main_world,
                        min_x, min_y, min_z, max_x, max_y, max_z, queued_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        player_name = excluded.player_name,
                        main_world = excluded.main_world,
                        min_x = excluded.min_x,
                        min_y = excluded.min_y,
                        min_z = excluded.min_z,
                        max_x = excluded.max_x,
                        max_y = excluded.max_y,
                        max_z = excluded.max_z,
                        queued_at = excluded.queued_at
                    """
            )) {
                bindEntry(statement, entry);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Optional<QueueEntry> findByPlayer(UUID playerUuid) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, player_uuid, player_name, main_world, min_x, min_y, min_z, max_x, max_y, max_z, queued_at
                    FROM queue_entries
                    WHERE player_uuid = ?
                    """
            )) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapEntry(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public List<QueueEntry> listAll() throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<QueueEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, player_uuid, player_name, main_world, min_x, min_y, min_z, max_x, max_y, max_z, queued_at
                    FROM queue_entries
                    ORDER BY queued_at ASC, id ASC
                    """
            ); ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(mapEntry(resultSet));
                }
            }
            return entries;
        });
    }

    public List<QueueEntry> findByMainWorld(String mainWorld) throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<QueueEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, player_uuid, player_name, main_world, min_x, min_y, min_z, max_x, max_y, max_z, queued_at
                    FROM queue_entries
                    WHERE main_world = ?
                    ORDER BY queued_at ASC, id ASC
                    """
            )) {
                statement.setString(1, mainWorld);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(mapEntry(resultSet));
                    }
                }
            }
            return entries;
        });
    }

    public List<QueueEntry> findConflicts(String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<QueueEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, player_uuid, player_name, main_world, min_x, min_y, min_z, max_x, max_y, max_z, queued_at
                    FROM queue_entries
                    WHERE main_world = ?
                      AND NOT (max_x < ? OR min_x > ? OR max_y < ? OR min_y > ? OR max_z < ? OR min_z > ?)
                    ORDER BY queued_at ASC, id ASC
                    """
            )) {
                statement.setString(1, mainWorld);
                statement.setInt(2, minX);
                statement.setInt(3, maxX);
                statement.setInt(4, minY);
                statement.setInt(5, maxY);
                statement.setInt(6, minZ);
                statement.setInt(7, maxZ);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(mapEntry(resultSet));
                    }
                }
            }
            return entries;
        });
    }

    public int deleteByPlayer(UUID playerUuid) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM queue_entries WHERE player_uuid = ?"
            )) {
                statement.setString(1, playerUuid.toString());
                return statement.executeUpdate();
            }
        });
    }

    public long countByPlayer(UUID playerUuid) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) AS total FROM queue_entries WHERE player_uuid = ?"
            )) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong("total") : 0L;
                }
            }
        });
    }

    public long countByMainWorld(String mainWorld) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) AS total FROM queue_entries WHERE main_world = ?"
            )) {
                statement.setString(1, mainWorld);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong("total") : 0L;
                }
            }
        });
    }

    public void insert(QueueEntry entry) {
        try {
            enqueue(entry);
        } catch (SQLException exception) {
            throw new IllegalStateException("保存排队条目失败", exception);
        }
    }

    public List<QueueEntry> findOverlapping(String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        try {
            return findConflicts(mainWorld, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询重叠排队条目失败", exception);
        }
    }

    public void deleteByPlayerQuietly(UUID playerUuid) {
        try {
            deleteByPlayer(playerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("删除排队条目失败", exception);
        }
    }

    public long countByPlayerQuietly(UUID playerUuid) {
        try {
            return countByPlayer(playerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("统计排队条目失败", exception);
        }
    }

    public Optional<QueueEntry> findByPlayerQuietly(UUID playerUuid) {
        try {
            return findByPlayer(playerUuid);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询排队条目失败", exception);
        }
    }

    private void bindEntry(PreparedStatement statement, QueueEntry entry) throws SQLException {
        statement.setString(1, entry.playerUuid().toString());
        statement.setString(2, entry.playerName());
        statement.setString(3, entry.mainWorld());
        statement.setInt(4, requireCoordinate(entry.minX(), "minX"));
        statement.setInt(5, requireCoordinate(entry.minY(), "minY"));
        statement.setInt(6, requireCoordinate(entry.minZ(), "minZ"));
        statement.setInt(7, requireCoordinate(entry.maxX(), "maxX"));
        statement.setInt(8, requireCoordinate(entry.maxY(), "maxY"));
        statement.setInt(9, requireCoordinate(entry.maxZ(), "maxZ"));
        statement.setLong(10, entry.queuedAt().toEpochMilli());
    }

    private QueueEntry mapEntry(ResultSet resultSet) throws SQLException {
        return new QueueEntry(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"),
                resultSet.getString("main_world"),
                getNullableInteger(resultSet, "min_x"),
                getNullableInteger(resultSet, "min_y"),
                getNullableInteger(resultSet, "min_z"),
                getNullableInteger(resultSet, "max_x"),
                getNullableInteger(resultSet, "max_y"),
                getNullableInteger(resultSet, "max_z"),
                Instant.ofEpochMilli(resultSet.getLong("queued_at"))
        );
    }

    private int requireCoordinate(Integer value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("排队区域坐标不能为空: " + name);
        }
        return value;
    }

    private Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}

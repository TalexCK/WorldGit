package com.worldgit.database;

import com.worldgit.model.WorldCommit;
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
 * 主世界版本与提交记录仓储。
 */
public final class RevisionRepository {

    private final DatabaseManager databaseManager;

    public RevisionRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public long getHeadRevision(String mainWorld) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement insertStatement = connection.prepareStatement(
                    """
                    INSERT INTO world_heads (main_world, head_revision)
                    VALUES (?, 0)
                    ON CONFLICT(main_world) DO NOTHING
                    """
            )) {
                insertStatement.setString(1, mainWorld);
                insertStatement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT head_revision FROM world_heads WHERE main_world = ?"
            )) {
                statement.setString(1, mainWorld);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong("head_revision") : 0L;
                }
            }
        });
    }

    public Optional<WorldCommit> findByBranchId(String branchId) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, main_world, revision, branch_id, commit_kind, min_x, min_y, min_z,
                           max_x, max_y, max_z, author_uuid, author_name, message, created_at
                    FROM world_commits
                    WHERE branch_id = ?
                    """
            )) {
                statement.setString(1, branchId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapCommit(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public WorldCommit appendCommit(
            String mainWorld,
            String branchId,
            String commitKind,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            UUID authorUuid,
            String authorName,
            String message,
            Instant createdAt
    ) throws SQLException {
        return databaseManager.withTransactionResult(connection -> {
            if (branchId != null && !branchId.isBlank()) {
                try (PreparedStatement existingStatement = connection.prepareStatement(
                        """
                        SELECT id, main_world, revision, branch_id, commit_kind, min_x, min_y, min_z,
                               max_x, max_y, max_z, author_uuid, author_name, message, created_at
                        FROM world_commits
                        WHERE branch_id = ?
                        """
                )) {
                    existingStatement.setString(1, branchId);
                    try (ResultSet resultSet = existingStatement.executeQuery()) {
                        if (resultSet.next()) {
                            return mapCommit(resultSet);
                        }
                    }
                }
            }

            try (PreparedStatement insertHeadStatement = connection.prepareStatement(
                    """
                    INSERT INTO world_heads (main_world, head_revision)
                    VALUES (?, 0)
                    ON CONFLICT(main_world) DO NOTHING
                    """
            )) {
                insertHeadStatement.setString(1, mainWorld);
                insertHeadStatement.executeUpdate();
            }

            long currentHead;
            try (PreparedStatement readHeadStatement = connection.prepareStatement(
                    "SELECT head_revision FROM world_heads WHERE main_world = ?"
            )) {
                readHeadStatement.setString(1, mainWorld);
                try (ResultSet resultSet = readHeadStatement.executeQuery()) {
                    currentHead = resultSet.next() ? resultSet.getLong("head_revision") : 0L;
                }
            }

            long newRevision = currentHead + 1L;
            try (PreparedStatement updateHeadStatement = connection.prepareStatement(
                    "UPDATE world_heads SET head_revision = ? WHERE main_world = ?"
            )) {
                updateHeadStatement.setLong(1, newRevision);
                updateHeadStatement.setString(2, mainWorld);
                updateHeadStatement.executeUpdate();
            }

            try (PreparedStatement insertCommitStatement = connection.prepareStatement(
                    """
                    INSERT INTO world_commits (
                        main_world, revision, branch_id, commit_kind,
                        min_x, min_y, min_z, max_x, max_y, max_z,
                        author_uuid, author_name, message, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """
            )) {
                insertCommitStatement.setString(1, mainWorld);
                insertCommitStatement.setLong(2, newRevision);
                insertCommitStatement.setString(3, normalizeNullable(branchId));
                insertCommitStatement.setString(4, commitKind);
                insertCommitStatement.setInt(5, minX);
                insertCommitStatement.setInt(6, minY);
                insertCommitStatement.setInt(7, minZ);
                insertCommitStatement.setInt(8, maxX);
                insertCommitStatement.setInt(9, maxY);
                insertCommitStatement.setInt(10, maxZ);
                insertCommitStatement.setString(11, authorUuid == null ? null : authorUuid.toString());
                insertCommitStatement.setString(12, authorName);
                insertCommitStatement.setString(13, message);
                insertCommitStatement.setLong(14, createdAt.toEpochMilli());
                insertCommitStatement.executeUpdate();
            }

            return new WorldCommit(
                    0L,
                    mainWorld,
                    newRevision,
                    normalizeNullable(branchId),
                    commitKind,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    authorUuid,
                    authorName,
                    message,
                    createdAt
            );
        });
    }

    public List<WorldCommit> findOverlappingSince(
            String mainWorld,
            long afterRevision,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<WorldCommit> commits = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, main_world, revision, branch_id, commit_kind, min_x, min_y, min_z,
                           max_x, max_y, max_z, author_uuid, author_name, message, created_at
                    FROM world_commits
                    WHERE main_world = ?
                      AND revision > ?
                      AND NOT (
                          max_x < ? OR min_x > ?
                          OR max_y < ? OR min_y > ?
                          OR max_z < ? OR min_z > ?
                      )
                    ORDER BY revision ASC
                    """
            )) {
                statement.setString(1, mainWorld);
                statement.setLong(2, afterRevision);
                statement.setInt(3, minX);
                statement.setInt(4, maxX);
                statement.setInt(5, minY);
                statement.setInt(6, maxY);
                statement.setInt(7, minZ);
                statement.setInt(8, maxZ);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        commits.add(mapCommit(resultSet));
                    }
                }
            }
            return commits;
        });
    }

    public long getHeadRevisionUnchecked(String mainWorld) {
        try {
            return getHeadRevision(mainWorld);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询主世界版本失败", exception);
        }
    }

    public Optional<WorldCommit> findByBranchIdUnchecked(String branchId) {
        try {
            return findByBranchId(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询提交记录失败", exception);
        }
    }

    public WorldCommit appendCommitUnchecked(
            String mainWorld,
            String branchId,
            String commitKind,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            UUID authorUuid,
            String authorName,
            String message,
            Instant createdAt
    ) {
        try {
            return appendCommit(
                    mainWorld,
                    branchId,
                    commitKind,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    authorUuid,
                    authorName,
                    message,
                    createdAt
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("写入提交记录失败", exception);
        }
    }

    public List<WorldCommit> findOverlappingSinceUnchecked(
            String mainWorld,
            long afterRevision,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        try {
            return findOverlappingSince(mainWorld, afterRevision, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询主线更新失败", exception);
        }
    }

    private WorldCommit mapCommit(ResultSet resultSet) throws SQLException {
        return new WorldCommit(
                resultSet.getLong("id"),
                resultSet.getString("main_world"),
                resultSet.getLong("revision"),
                resultSet.getString("branch_id"),
                resultSet.getString("commit_kind"),
                resultSet.getInt("min_x"),
                resultSet.getInt("min_y"),
                resultSet.getInt("min_z"),
                resultSet.getInt("max_x"),
                resultSet.getInt("max_y"),
                resultSet.getInt("max_z"),
                parseUuid(resultSet.getString("author_uuid")),
                resultSet.getString("author_name"),
                resultSet.getString("message"),
                Instant.ofEpochMilli(resultSet.getLong("created_at"))
        );
    }

    private UUID parseUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

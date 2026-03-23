package com.worldgit.database;

import com.worldgit.model.BranchSyncInfo;
import com.worldgit.model.BranchSyncState;
import com.worldgit.model.ConflictGroup;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 分支同步与冲突仓储。
 */
public final class BranchSyncRepository {

    private final DatabaseManager databaseManager;

    public BranchSyncRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "数据库管理器不能为空");
    }

    public Optional<BranchSyncInfo> findByBranchId(String branchId) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT branch_id, base_revision, last_rebased_revision, last_reviewed_revision,
                           sync_state, snapshot_path, pending_rebase_revision, pending_snapshot_path,
                           working_snapshot_path, unresolved_group_count, stale_reason
                    FROM branch_sync_meta
                    WHERE branch_id = ?
                    """
            )) {
                statement.setString(1, branchId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapSyncInfo(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public void save(BranchSyncInfo info) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO branch_sync_meta (
                        branch_id, base_revision, last_rebased_revision, last_reviewed_revision,
                        sync_state, snapshot_path, pending_rebase_revision, pending_snapshot_path,
                        working_snapshot_path, unresolved_group_count, stale_reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(branch_id) DO UPDATE SET
                        base_revision = excluded.base_revision,
                        last_rebased_revision = excluded.last_rebased_revision,
                        last_reviewed_revision = excluded.last_reviewed_revision,
                        sync_state = excluded.sync_state,
                        snapshot_path = excluded.snapshot_path,
                        pending_rebase_revision = excluded.pending_rebase_revision,
                        pending_snapshot_path = excluded.pending_snapshot_path,
                        working_snapshot_path = excluded.working_snapshot_path,
                        unresolved_group_count = excluded.unresolved_group_count,
                        stale_reason = excluded.stale_reason
                    """
            )) {
                bindSyncInfo(statement, info);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public List<ConflictGroup> listConflictGroups(String branchId) throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<ConflictGroup> groups = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, branch_id, group_index, min_x, min_y, min_z, max_x, max_y, max_z,
                           block_count, status, resolution, detail_path, created_at, resolved_at
                    FROM conflict_groups
                    WHERE branch_id = ?
                    ORDER BY group_index ASC
                    """
            )) {
                statement.setString(1, branchId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        groups.add(mapConflictGroup(resultSet));
                    }
                }
            }
            return groups;
        });
    }

    public Optional<ConflictGroup> findConflictGroup(String branchId, int groupIndex) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT id, branch_id, group_index, min_x, min_y, min_z, max_x, max_y, max_z,
                           block_count, status, resolution, detail_path, created_at, resolved_at
                    FROM conflict_groups
                    WHERE branch_id = ? AND group_index = ?
                    """
            )) {
                statement.setString(1, branchId);
                statement.setInt(2, groupIndex);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapConflictGroup(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public void replaceConflictGroups(String branchId, List<ConflictGroup> groups) throws SQLException {
        databaseManager.withTransactionResult(connection -> {
            try (PreparedStatement deleteStatement = connection.prepareStatement(
                    "DELETE FROM conflict_groups WHERE branch_id = ?"
            )) {
                deleteStatement.setString(1, branchId);
                deleteStatement.executeUpdate();
            }

            try (PreparedStatement insertStatement = connection.prepareStatement(
                    """
                    INSERT INTO conflict_groups (
                        branch_id, group_index, min_x, min_y, min_z, max_x, max_y, max_z,
                        block_count, status, resolution, detail_path, created_at, resolved_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """
            )) {
                for (ConflictGroup group : groups) {
                    insertStatement.setString(1, group.branchId());
                    insertStatement.setInt(2, group.groupIndex());
                    insertStatement.setInt(3, group.minX());
                    insertStatement.setInt(4, group.minY());
                    insertStatement.setInt(5, group.minZ());
                    insertStatement.setInt(6, group.maxX());
                    insertStatement.setInt(7, group.maxY());
                    insertStatement.setInt(8, group.maxZ());
                    insertStatement.setInt(9, group.blockCount());
                    insertStatement.setString(10, group.status());
                    insertStatement.setString(11, group.resolution());
                    insertStatement.setString(12, group.detailPath());
                    insertStatement.setLong(13, group.createdAt().toEpochMilli());
                    setNullableInstant(insertStatement, 14, group.resolvedAt());
                    insertStatement.addBatch();
                }
                insertStatement.executeBatch();
            }
            return null;
        });
    }

    public void markConflictGroupResolved(String branchId, int groupIndex, String resolution, Instant resolvedAt) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE conflict_groups
                    SET status = 'RESOLVED', resolution = ?, resolved_at = ?
                    WHERE branch_id = ? AND group_index = ?
                    """
            )) {
                statement.setString(1, resolution);
                statement.setLong(2, resolvedAt.toEpochMilli());
                statement.setString(3, branchId);
                statement.setInt(4, groupIndex);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void deleteConflictGroups(String branchId) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM conflict_groups WHERE branch_id = ?"
            )) {
                statement.setString(1, branchId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void upsertRebaseJournal(String branchId, String phase, Instant updatedAt) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO rebase_journal (branch_id, phase, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(branch_id) DO UPDATE SET
                        phase = excluded.phase,
                        updated_at = excluded.updated_at
                    """
            )) {
                statement.setString(1, branchId);
                statement.setString(2, phase);
                statement.setLong(3, updatedAt.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Optional<String> findRebasePhase(String branchId) throws SQLException {
        return databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT phase
                    FROM rebase_journal
                    WHERE branch_id = ?
                    """
            )) {
                statement.setString(1, branchId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.ofNullable(resultSet.getString("phase")) : Optional.empty();
                }
            }
        });
    }

    public List<String> listIncompleteRebaseBranchIds() throws SQLException {
        return databaseManager.withConnection(connection -> {
            List<String> branchIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT branch_id
                    FROM rebase_journal
                    ORDER BY updated_at ASC, id ASC
                    """
            ); ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    branchIds.add(resultSet.getString("branch_id"));
                }
            }
            return branchIds;
        });
    }

    public void deleteRebaseJournal(String branchId) throws SQLException {
        databaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM rebase_journal WHERE branch_id = ?"
            )) {
                statement.setString(1, branchId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public BranchSyncInfo findByBranchIdUnchecked(String branchId) {
        try {
            return findByBranchId(branchId)
                    .orElseThrow(() -> new IllegalStateException("分支同步信息不存在: " + branchId));
        } catch (SQLException exception) {
            throw new IllegalStateException("查询分支同步信息失败", exception);
        }
    }

    public Optional<BranchSyncInfo> findOptionalUnchecked(String branchId) {
        try {
            return findByBranchId(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询分支同步信息失败", exception);
        }
    }

    public void saveUnchecked(BranchSyncInfo info) {
        try {
            save(info);
        } catch (SQLException exception) {
            throw new IllegalStateException("保存分支同步信息失败", exception);
        }
    }

    public List<ConflictGroup> listConflictGroupsUnchecked(String branchId) {
        try {
            return listConflictGroups(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询冲突分组失败", exception);
        }
    }

    public Optional<ConflictGroup> findConflictGroupUnchecked(String branchId, int groupIndex) {
        try {
            return findConflictGroup(branchId, groupIndex);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询冲突分组失败", exception);
        }
    }

    public void replaceConflictGroupsUnchecked(String branchId, List<ConflictGroup> groups) {
        try {
            replaceConflictGroups(branchId, groups);
        } catch (SQLException exception) {
            throw new IllegalStateException("保存冲突分组失败", exception);
        }
    }

    public void markConflictGroupResolvedUnchecked(String branchId, int groupIndex, String resolution, Instant resolvedAt) {
        try {
            markConflictGroupResolved(branchId, groupIndex, resolution, resolvedAt);
        } catch (SQLException exception) {
            throw new IllegalStateException("更新冲突分组状态失败", exception);
        }
    }

    public void deleteConflictGroupsUnchecked(String branchId) {
        try {
            deleteConflictGroups(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("删除冲突分组失败", exception);
        }
    }

    public void upsertRebaseJournalUnchecked(String branchId, String phase, Instant updatedAt) {
        try {
            upsertRebaseJournal(branchId, phase, updatedAt);
        } catch (SQLException exception) {
            throw new IllegalStateException("写入 rebase 日志失败", exception);
        }
    }

    public List<String> listIncompleteRebaseBranchIdsUnchecked() {
        try {
            return listIncompleteRebaseBranchIds();
        } catch (SQLException exception) {
            throw new IllegalStateException("查询待恢复 rebase 列表失败", exception);
        }
    }

    public void deleteRebaseJournalUnchecked(String branchId) {
        try {
            deleteRebaseJournal(branchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("删除 rebase 日志失败", exception);
        }
    }

    private BranchSyncInfo mapSyncInfo(ResultSet resultSet) throws SQLException {
        return new BranchSyncInfo(
                resultSet.getString("branch_id"),
                resultSet.getLong("base_revision"),
                getNullableLong(resultSet, "last_rebased_revision"),
                getNullableLong(resultSet, "last_reviewed_revision"),
                BranchSyncState.fromDbValue(resultSet.getString("sync_state")),
                resultSet.getString("snapshot_path"),
                getNullableLong(resultSet, "pending_rebase_revision"),
                resultSet.getString("pending_snapshot_path"),
                resultSet.getString("working_snapshot_path"),
                resultSet.getInt("unresolved_group_count"),
                resultSet.getString("stale_reason")
        );
    }

    private ConflictGroup mapConflictGroup(ResultSet resultSet) throws SQLException {
        return new ConflictGroup(
                resultSet.getLong("id"),
                resultSet.getString("branch_id"),
                resultSet.getInt("group_index"),
                resultSet.getInt("min_x"),
                resultSet.getInt("min_y"),
                resultSet.getInt("min_z"),
                resultSet.getInt("max_x"),
                resultSet.getInt("max_y"),
                resultSet.getInt("max_z"),
                resultSet.getInt("block_count"),
                resultSet.getString("status"),
                resultSet.getString("resolution"),
                resultSet.getString("detail_path"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                getNullableInstant(resultSet, "resolved_at")
        );
    }

    private void bindSyncInfo(PreparedStatement statement, BranchSyncInfo info) throws SQLException {
        statement.setString(1, info.branchId());
        statement.setLong(2, info.baseRevision());
        setNullableLong(statement, 3, info.lastRebasedRevision());
        setNullableLong(statement, 4, info.lastReviewedRevision());
        statement.setString(5, info.syncState().dbValue());
        statement.setString(6, info.snapshotPath());
        setNullableLong(statement, 7, info.pendingRebaseRevision());
        statement.setString(8, info.pendingSnapshotPath());
        statement.setString(9, info.workingSnapshotPath());
        statement.setInt(10, info.unresolvedGroupCount());
        statement.setString(11, info.staleReason());
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    private void setNullableInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }

    private Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant getNullableInstant(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
